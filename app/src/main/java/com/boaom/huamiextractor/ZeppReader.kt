package com.boaom.huamiextractor

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * 从 Zepp(华米) 应用的沙箱内把明文 SQLite 库拷出来解析。
 *
 * 关键事实（2026-11 实测）：
 *  - 库文件头是 "SQLite format 3"，**没有任何加密**，不需要 SQLCipher
 *  - 库在 /data/data/com.huami.watch.hmwatchmanager/databases/
 *    HealthMatrix_1099044607.db  健康矩阵（睡眠分期/心率/血氧）
 *    origin_db_<hash>             运动记录（TRACKRECORD，含力量训练逐组）
 *  - 是 WAL 模式，**必须连 -wal / -shm 一起拷**，否则数据不全或损坏
 */
object ZeppReader {

    const val PKG = "com.huami.watch.hmwatchmanager"
    const val DB_DIR = "/data/data/$PKG/databases"

    private const val TAG = "ZeppReader"

    /** 需要搬运的库（正则匹配文件名） */
    private val WANTED = listOf(
        Regex("^HealthMatrix_.*\\.db$"),
        Regex("^origin_db_.*$")
    )

    /** 拷库：连 -wal/-shm 一起，落到本应用的私有目录 */
    fun pullDatabases(targetDir: File): List<File> {
        if (!targetDir.exists()) targetDir.mkdirs()
        val listing = ShizukuGate.runShell("ls -1 $DB_DIR 2>/dev/null")
        val names = listing.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val picks = mutableListOf<String>()
        for (n in names) {
            val base = n.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")
            if (WANTED.any { it.matches(base) }) picks.add(n)
        }
        if (picks.isEmpty()) throw IllegalStateException("没找到目标库，内容:\n$listing")

        val dst = targetDir.absolutePath
        // 逐个 cp，失败不中断
        val cmd = picks.joinToString("; ") { "cp -f '$DB_DIR/$it' '$dst/'" } +
            "; chmod 700 '$dst'; ls -1 '$dst'"
        val after = ShizukuGate.runShell(cmd)
        val copied = after.lines().map { it.trim() }.filter { it.isNotEmpty() }
        Log.i(TAG, "copied ${copied.size} files: $copied")
        return copied.map { File(targetDir, it) }.filter { it.exists() }
    }

    fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    // ---------- 睡眠 ----------

    /**
     * 睡眠分期。
     * sampleType=11003 的每一段是一条记录：
     *   startDate/endDate = 当天 00:00 与当天某时刻（endDate 是真实结束时刻）
     *   ExtraData startOffset/endOffset = 相对"当日 12 点前基准"的分钟偏移
     *   CategorySamples.value = 阶段码 4浅 5深 8REM 7清醒
     */
    data class SleepNight(
        val day: String,
        val sleepStartMs: Long,
        val sleepEndMs: Long,
        val lightMin: Int,
        val deepMin: Int,
        val remMin: Int,
        val awakeMin: Int,
        val avgHr: Int,
        val avgSpo2: Int,
        val hrSamples: Int
    ) {
        val asleepMin: Int get() = lightMin + deepMin + remMin
    }

    private const val STAGE_LIGHT = 4
    private const val STAGE_DEEP = 5
    private const val STAGE_REM = 8
    private const val STAGE_AWAKE = 7

    fun readSleep(db: SQLiteDatabase, days: Int = 28): List<SleepNight> {
        val cutoff = System.currentTimeMillis() - days * 86400000L
        val sql = """
            SELECT s.startDate, s.endDate, cs.value,
                   ev.numericalValue
            FROM Samples s
            JOIN CategorySamples cs ON cs.dataId = s.dataId
            LEFT JOIN ExtraDataValues ev ON ev.objectId = s.dataId
                 AND ev.keyId = (SELECT keyId FROM ExtraDataKeys WHERE key='endOffset')
            WHERE s.sampleType = 11003 AND s.startDate >= ?
            ORDER BY s.startDate
        """.trimIndent()
        val byDay = LinkedHashMap<String, MutableMap<Int, Int>>()
        val segEnd = LinkedHashMap<String, Long>()
        db.rawQuery(sql, arrayOf(cutoff.toString())).use { c ->
            while (c.moveToNext()) {
                val start = c.getLong(0)
                val end = c.getLong(1)
                val stage = c.getInt(2)
                val day = dayKey(start)
                val map = byDay.getOrPut(day) { mutableMapOf() }
                // 用 endOffset - startOffset 推段长；endDate 已含真实结束时刻
                val durMin = durationMinutes(db, c.getLong(0), end)
                map[stage] = (map[stage] ?: 0) + durMin
                segEnd[day] = maxOf(segEnd[day] ?: 0L, end)
            }
        }
        return byDay.map { (day, stages) ->
            val end = segEnd[day] ?: 0L
            val start = end - (stages.values.sum() * 60000L)
            val hr = readRangeAvg(db, 17001, start, end)
            val spo2 = readRangeAvg(db, 4001, start, end)
            SleepNight(
                day = day,
                sleepStartMs = start,
                sleepEndMs = end,
                lightMin = stages[STAGE_LIGHT] ?: 0,
                deepMin = stages[STAGE_DEEP] ?: 0,
                remMin = stages[STAGE_REM] ?: 0,
                awakeMin = stages[STAGE_AWAKE] ?: 0,
                avgHr = hr.first,
                avgSpo2 = spo2.first,
                hrSamples = hr.second
            )
        }.sortedBy { it.day }
    }

    /** 单段睡眠分钟数：优先用 ExtraData 的 endOffset-startOffset */
    private fun durationMinutes(db: SQLiteDatabase, dataId: Long, endDate: Long): Int {
        val off = db.rawQuery(
            """
            SELECT k.key, ev.numericalValue
            FROM ExtraDataValues ev JOIN ExtraDataKeys k ON k.keyId = ev.keyId
            WHERE ev.objectId = ? AND k.key IN ('startOffset','endOffset')
            """.trimIndent(), arrayOf(dataId.toString())
        ).use { c ->
            val m = HashMap<String, Int>()
            while (c.moveToNext()) m[c.getString(0)] = c.getInt(1)
            val s = m["startOffset"] ?: return 0
            val e = m["endOffset"] ?: return 0
            return (e - s).coerceAtLeast(0)
        }
    }

    /** 区间内某 sampleType 的均值与样本数 */
    private fun readRangeAvg(db: SQLiteDatabase, type: Int, from: Long, to: Long): Pair<Int, Int> {
        db.rawQuery(
            """
            SELECT AVG(q.quantity), COUNT(*) FROM Samples s
            JOIN QuantitySamples q ON q.dataId = s.dataId
            WHERE s.sampleType = ? AND s.startDate BETWEEN ? AND ? AND q.quantity > 0
            """.trimIndent(), arrayOf(type.toString(), from.toString(), to.toString())
        ).use { c ->
            c.moveToFirst()
            return Pair(c.getInt(0), c.getInt(1))
        }
    }

    // ---------- 运动 / 力量训练 ----------

    data class Workout(
        val day: String,
        val type: Int,
        val typeName: String,
        val durSec: Int,
        val avgHr: Int,
        val maxHr: Int,
        val calories: Double,
        val totalGroup: Int,
        val exerciseLoad: Int,
        val te: Double,
        val rpe: Int,
        val restMs: Long,
        val sets: List<Pair<Int, Int>>   // actionType -> reps
    )

    private val TYPE_NAME = mapOf(
        1 to "户外跑", 6 to "健走", 8 to "越野跑", 9 to "室内跑", 13 to "越野跑",
        40 to "徒步", 52 to "力量训练", 63 to "跳绳", 107 to "划船机"
    )

    fun readWorkouts(db: SQLiteDatabase, days: Int = 60): List<Workout> {
        val cutoff = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(System.currentTimeMillis() - days * 86400000L))
        val out = ArrayList<Workout>()
        db.rawQuery(
            "SELECT DATE, TYPE, COSTTIME, AVGHR, MAX_HR, CAL, TOTAL_GROUP, ORIGINAL_JSON_DATA " +
                "FROM TRACKRECORD WHERE DATE >= ? ORDER BY DATE DESC",
            arrayOf(cutoff)
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getString(0) ?: continue
                val type = c.getInt(1)
                val json = c.getString(7) ?: ""
                var totalGroup = c.getInt(6)
                var load = 0; var te = 0.0; var rpe = -1; var restMs = 0L
                val sets = ArrayList<Pair<Int, Int>>()
                try {
                    val j = org.json.JSONObject(json)
                    totalGroup = j.optInt("total_group", totalGroup)
                    load = j.optInt("exercise_load", 0)
                    te = j.optDouble("te", 0.0)
                    rpe = j.optInt("rpe", -1)
                    restMs = j.optLong("restTimeBetweenGroupsWithMillis", 0L)
                    val g = j.optString("strength_training_group", "")
                    if (g.isNotEmpty()) {
                        val arr = org.json.JSONArray(g)
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            sets.add(o.optInt("actionType") to o.optInt("count"))
                        }
                    }
                } catch (_: Exception) { }
                out.add(Workout(
                    day = day, type = type,
                    typeName = TYPE_NAME[type] ?: "类型$type",
                    durSec = c.getInt(2), avgHr = c.getInt(3), maxHr = c.getInt(4),
                    calories = c.getDouble(5), totalGroup = totalGroup,
                    exerciseLoad = load, te = te, rpe = rpe, restMs = restMs, sets = sets
                ))
            }
        }
        return out
    }

    private fun dayKey(ms: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date(ms))
}

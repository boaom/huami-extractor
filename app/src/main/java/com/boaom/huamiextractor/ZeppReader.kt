package com.boaom.huamiextractor

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从 Zepp(华米) 应用的沙箱内把明文 SQLite 库拷出来解析。
 *
 * 关键事实（2026-11 实测，勿凭直觉改）：
 *  - 库文件头是 "SQLite format 3"，**没有任何加密**，不需要 SQLCipher
 *  - 库在 /data/data/com.huami.watch.hmwatchmanager/databases/
 *      HealthMatrix_<uid>.db   健康矩阵（睡眠分期 / 心率 / 血氧）
 *      origin_db_<hash>        运动记录（TRACKRECORD，含力量训练逐组）
 *  - 是 WAL 模式，**必须连 -wal / -shm 一起拷**，否则数据不全或损坏
 *  - TRACKRECORD 里**没有 TOTAL_GROUP 这一列**，组数只在 ORIGINAL_JSON_DATA 里
 *  - 睡眠分段的 endDate 就是**绝对结束时刻**；startOffset/endOffset 是分钟偏移，
 *    基准 = startDate 当天零点 **减一天**。段长 = endOffset - startOffset，
 *    段起点 = endDate - 段长（这样跨零点不会算错）
 */
object ZeppReader {

    const val PKG = "com.huami.watch.hmwatchmanager"
    const val DB_DIR = "/data/data/$PKG/databases"

    private const val TAG = "ZeppReader"
    private val FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val WANTED = listOf(
        Regex("^HealthMatrix_.*\\.db$"),
        Regex("^origin_db_.*$")
    )

    /** 拷库：连 -wal/-shm 一起，落到本应用私有目录 */
    fun pullDatabases(targetDir: File): List<File> {
        if (!targetDir.exists()) targetDir.mkdirs()
        val listing = ShizukuGate.runShell("ls -1 $DB_DIR 2>/dev/null")
        val names = listing.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val picks = LinkedHashSet<String>()
        for (n in names) {
            val base = n.removeSuffix("-wal").removeSuffix("-shm").removeSuffix("-journal")
            if (WANTED.any { it.matches(base) }) picks.add(n)
        }
        if (picks.isEmpty()) throw IllegalStateException("没找到目标库，目录内容:\n$listing")

        val dst = targetDir.absolutePath
        val cmd = picks.joinToString("; ") { "cp -f '$DB_DIR/$it' '$dst/'" } +
            "; chmod 700 '$dst'; ls -1 '$dst'"
        val after = ShizukuGate.runShell(cmd)
        val copied = after.lines().map { it.trim() }.filter { it.isNotEmpty() }
        Log.i(TAG, "copied ${copied.size} files")
        return copied.map { File(targetDir, it) }.filter { it.exists() }
    }

    fun open(file: File): SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)

    // ---------------- 睡眠 ----------------

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
        val deepPct: Int get() = if (asleepMin > 0) (deepMin * 100 / asleepMin) else 0
    }

    /** 单段睡眠 */
    private class Seg(val day: String, val start: Long, val end: Long, val stage: Int)

    private const val STAGE_LIGHT = 4
    private const val STAGE_DEEP = 5
    private const val STAGE_REM = 8
    private const val STAGE_AWAKE = 7

    fun readSleep(db: SQLiteDatabase, days: Int = 28): List<SleepNight> {
        val cutoff = System.currentTimeMillis() - days * 86400000L
        val sql = """
            SELECT s.startDate, s.endDate, cs.value,
                   es.numericalValue, ee.numericalValue
            FROM Samples s
            JOIN CategorySamples cs ON cs.dataId = s.dataId
            LEFT JOIN ExtraDataValues es ON es.objectId = s.dataId
                 AND es.keyId = (SELECT keyId FROM ExtraDataKeys WHERE key = 'startOffset')
            LEFT JOIN ExtraDataValues ee ON ee.objectId = s.dataId
                 AND ee.keyId = (SELECT keyId FROM ExtraDataKeys WHERE key = 'endOffset')
            WHERE s.sampleType = 11003 AND s.startDate >= ?
            ORDER BY s.startDate, es.numericalValue
        """.trimIndent()

        val segs = ArrayList<Seg>()
        db.rawQuery(sql, arrayOf(cutoff.toString())).use { c ->
            while (c.moveToNext()) {
                val startDate = c.getLong(0)
                val endDate = c.getLong(1)
                val stage = c.getInt(2)
                val so = c.getInt(3)
                val eo = c.getInt(4)
                val durMin = (eo - so).coerceAtLeast(0)
                val segStart = endDate - durMin * 60000L
                segs.add(Seg(FMT.format(java.util.Date(startDate)), segStart, endDate, stage))
            }
        }
        if (segs.isEmpty()) return emptyList()

        val byDay = segs.groupBy { it.day }
        return byDay.map { (day, list) ->
            val stages = list.groupingBy { it.stage }.fold(0) { acc, g -> acc + g.sumOf { (it.end - it.start) / 60000L }.toInt() }
            val s = list.minOf { it.start }
            val e = list.maxOf { it.end }
            val hr = rangeAvg(db, 17001, s, e)
            val spo2 = rangeAvg(db, 4001, s, e)
            SleepNight(
                day = day, sleepStartMs = s, sleepEndMs = e,
                lightMin = stages[STAGE_LIGHT] ?: 0,
                deepMin = stages[STAGE_DEEP] ?: 0,
                remMin = stages[STAGE_REM] ?: 0,
                awakeMin = stages[STAGE_AWAKE] ?: 0,
                avgHr = hr.first, avgSpo2 = spo2.first, hrSamples = hr.second
            )
        }.sortedBy { it.day }
    }

    /** 区间内某 sampleType 的 (均值, 样本数) */
    private fun rangeAvg(db: SQLiteDatabase, type: Int, from: Long, to: Long): Pair<Int, Int> {
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

    // ---------------- 运动 / 力量训练 ----------------

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
        val sets: List<Pair<Int, Int>>
    )

    private val TYPE_NAME = mapOf(
        1 to "户外跑", 6 to "健走", 8 to "越野跑", 9 to "室内跑", 13 to "越野跑",
        40 to "徒步", 52 to "力量训练", 63 to "跳绳", 107 to "划船机"
    )

    fun readWorkouts(db: SQLiteDatabase, days: Int = 60): List<Workout> {
        val cutoff = FMT.format(java.util.Date(System.currentTimeMillis() - days * 86400000L))
        val out = ArrayList<Workout>()
        // 注意：TRACKRECORD 无 TOTAL_GROUP 列，组数只从 JSON 取
        db.rawQuery(
            "SELECT DATE, TYPE, COSTTIME, AVGHR, MAX_HR, CAL, ORIGINAL_JSON_DATA " +
                "FROM TRACKRECORD WHERE DATE >= ? ORDER BY DATE DESC",
            arrayOf(cutoff)
        ).use { c ->
            while (c.moveToNext()) {
                val day = c.getString(0) ?: continue
                val type = c.getInt(1)
                val json = c.getString(6) ?: ""
                var totalGroup = 0; var load = 0; var te = 0.0; var rpe = -1; var restMs = 0L
                val sets = ArrayList<Pair<Int, Int>>()
                try {
                    val j = org.json.JSONObject(json)
                    totalGroup = j.optInt("total_group", 0)
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
}

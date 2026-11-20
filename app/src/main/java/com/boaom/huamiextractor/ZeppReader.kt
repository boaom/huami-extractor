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
 *      origin_db_<32位hash>    运动记录（TRACKRECORD，含力量训练逐组）
 *    同目录还有 HealthMatrix_0.db / origin_db_0 / origin_db_default 等**空壳或无关库**，
 *    只能按名字精确定位，别用 startsWith 匹配
 *  - 是 WAL 模式，**必须连 -wal / -shm 一起拷**，否则数据不全或损坏
 *  - TRACKRECORD 里**没有 TOTAL_GROUP 这一列**，组数只在 ORIGINAL_JSON_DATA 里
 *  - 睡眠分段的 endDate 就是**绝对结束时刻**；startOffset/endOffset 是分钟偏移，
 *    基准 = startDate 当天零点 **减一天**。段长 = endOffset - startOffset，
 *    段起点 = endDate - 段长（这样跨零点不会算错）
 *  - root 拷出来的文件属主是 root，**App 进程读不了**，必须 chown 回自己 + chmod
 */
object ZeppReader {

    const val PKG = "com.huami.watch.hmwatchmanager"
    const val DB_DIR = "/data/data/$PKG/databases"

    private const val TAG = "ZeppReader"
    private val FMT = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** 真正要用的库（精确匹配，排除空壳库） */
    private val RE_HEALTH = Regex("^HealthMatrix_[1-9]\\d*\\.db$")
    private val RE_ORIGIN = Regex("^origin_db_[0-9a-f]{32}$")

    /**
     * 拷库：连 -wal/-shm 一起，落到本应用私有目录。
     * 关键：拷完必须 chown 回本 App 的 uid 并放开权限，否则 root 建的文件 App 自己读不了。
     */
    fun pullDatabases(targetDir: File): List<File> {
        if (!targetDir.exists()) targetDir.mkdirs()
        val listing = ShizukuGate.runShell("ls -1 $DB_DIR 2>/dev/null")
        val names = listing.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // 找出主库文件，再把它们的 -wal/-shm/-journal 一并带上
        val primaries = names.filter { RE_HEALTH.matches(it) || RE_ORIGIN.matches(it) }
        if (primaries.isEmpty()) throw IllegalStateException("没找到目标库，目录内容:\n$listing")

        val picks = LinkedHashSet<String>()
        for (p in primaries) {
            picks.add(p)
            names.filter { it.startsWith(p) && it != p }.forEach { picks.add(it) }
        }

        val dst = targetDir.absolutePath
        val uid = android.os.Process.myUid()
        val gid = android.os.Process.myGid()
        val cmd = StringBuilder()
        picks.forEach { cmd.append("cp -f '$DB_DIR/$it' '$dst/' 2>/dev/null; ") }
        // 关键修复：root 拷的文件默认 root:root 600，App 读不了 → chown + chmod
        // chown 对某些 root 沙箱不可用，所以同时用 chmod 兜底
        cmd.append("chown -R $uid:$gid '$dst' 2>/dev/null; ")
        cmd.append("chmod 755 '$dst' 2>/dev/null; ")
        cmd.append("chmod 666 '$dst'/* 2>/dev/null; ")
        cmd.append("chmod 644 '$dst'/* 2>/dev/null; ")
        cmd.append("ls -l '$dst'")

        val after = ShizukuGate.runShell(cmd.toString())
        val copied = after.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("total") }
            .map { it.split(/\s+/).lastOrNull() ?: "" }
            .filter { it.isNotEmpty() }
        Log.i(TAG, "copied ${copied.size} files")
        return copied.map { File(targetDir, it) }.filter { it.exists() }
    }

    /** 选真正的健康矩阵库（排除 HealthMatrix_0.db） */
    private fun pickHealth(files: List<File>): File? =
        files.filter { RE_HEALTH.matches(it.name) }
            .maxByOrNull { it.length() }

    /** 选真正的 origin_db（排除 origin_db_0 / origin_db_default） */
    private fun pickOrigin(files: List<File>): File? =
        files.filter { RE_ORIGIN.matches(it.name) }
            .maxByOrNull { it.length() }

    /**
     * 打开库。
     * 用 READWRITE 而不是 READONLY：WAL 模式下只读连接需要能写 -shm，
     * 刚 chown 过来的副本 shm 是 root 建的要先修好；同时 RW 打开能让 SQLite
     * 自动做 WAL 恢复，避免"database disk image is malformed"。
     */
    fun open(file: File): SQLiteDatabase {
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            Log.w(TAG, "RW 打开失败，退回只读: ${e.message}")
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        }
    }

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

        data class Seg2(val day: String, val start: Long, val end: Long, val stage: Int)

        val segs = ArrayList<Seg2>()
        db.rawQuery(sql, arrayOf(cutoff.toString())).use { c ->
            while (c.moveToNext()) {
                val startDate = c.getLong(0)
                val endDate = c.getLong(1)
                val stage = c.getInt(2)
                val so = c.getInt(3)
                val eo = c.getInt(4)
                val durMin = (eo - so).coerceAtLeast(0)
                val segStart = endDate - durMin * 60000L
                segs.add(Seg2(FMT.format(java.util.Date(startDate)), segStart, endDate, stage))
            }
        }
        if (segs.isEmpty()) return emptyList()

        val byDay = segs.groupBy { it.day }
        return byDay.map { (day, list) ->
            val stages = HashMap<Int, Int>()
            for (seg in list) {
                val mins = ((seg.end - seg.start) / 60000L).toInt()
                stages[seg.stage] = (stages[seg.stage] ?: 0) + mins
            }
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

    fun findHealthMatrix(files: List<File>): File? = pickHealth(files)
    fun findOriginDb(files: List<File>): File? = pickOrigin(files)
}

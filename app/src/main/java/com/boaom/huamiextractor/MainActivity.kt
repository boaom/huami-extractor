package com.boaom.huamiextractor

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)

        findViewById<Button>(R.id.btnCheck).setOnClickListener { checkShizuku() }
        findViewById<Button>(R.id.btnPull).setOnClickListener { pullAndAnalyze() }
        findViewById<Button>(R.id.btnExport).setOnClickListener { exportLatest() }
    }

    private fun append(s: String) {
        log.append(s).append('\n')
        runOnUiThread { logView.text = log.toString() }
    }

    private fun checkShizuku() {
        append("== Shizuku 检查")
        if (!ShizukuGate.binderAlive()) {
            append("✗ Shizuku 服务未运行。请启动 Shizuku（Magisk 环境可直接给 root）。")
            return
        }
        if (!ShizukuGate.permissionGranted()) {
            append("…需要授权，正在请求")
            ShizukuGate.requestPermission()
            append("请在弹窗/通知里点「允许」，再点一次检查")
            return
        }
        append("✓ 已授权。执行身份：\n" + ShizukuGate.deviceInfo())
    }

    private fun pullAndAnalyze() {
        if (!ShizukuGate.permissionGranted()) {
            append("✗ 先点「检查 Shizuku」并授权")
            return
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "zepp")
                    append("== 拷贝 Zepp 数据库")
                    val files = ZeppReader.pullDatabases(dir)
                    append("已拷 ${files.size} 个文件：" + files.joinToString(", ") { it.name })

                    val hm = files.firstOrNull { it.name.startsWith("HealthMatrix") }
                    val origin = files.firstOrNull { it.name.startsWith("origin_db") }
                    if (hm == null || origin == null) throw IllegalStateException("缺少关键库")

                    val hmDb = ZeppReader.open(hm)
                    val sleep = ZeppReader.readSleep(hmDb, 28)
                    hmDb.close()
                    append("== 睡眠：${sleep.size} 晚")
                    sleep.takeLast(7).forEach {
                        append("  ${it.day}  睡 ${it.asleepMin / 60}h${it.asleepMin % 60}m" +
                                "  深 ${it.deepMin}m  REM ${it.remMin}m  浅 ${it.lightMin}m" +
                                "  夜均心率 ${it.avgHr}")
                    }

                    val od = ZeppReader.open(origin)
                    val works = ZeppReader.readWorkouts(od, 60)
                    od.close()
                    val strength = works.filter { it.type == 52 }.sortedBy { it.day }
                    append("== 力量训练：${strength.size} 场")
                    strength.takeLast(5).forEach {
                        append("  ${it.day}  ${it.totalGroup} 组  ${it.durSec / 60} 分" +
                                "  心率 ${it.avgHr}/${it.maxHr}  负荷 ${it.exerciseLoad}" +
                                (if (it.rpe in 6..10) "  RPE ${it.rpe}" else "") +
                                "  组间休息 ${it.restMs / 60000} 分")
                    }

                    val ready = PlanEngine.assess(sleep)
                    append("== 恢复评估：${ready.level}")
                    ready.reasons.forEach { append("  · $it") }

                    val sessionIdx = strength.size
                    val split = PlanEngine.splitForSession(sessionIdx)
                    val last = strength.lastOrNull()
                    val plan = PlanEngine.plan(split, ready, last, emptyMap())
                    append("== 今日课表：${plan.split}（预计 ${plan.estMin} 分钟）")
                    plan.moves.forEach {
                        append("  ${it.name}  ${it.sets}×${it.repsLo}-${it.repsHi}  @RPE${it.targetRpe}  休息 ${it.restSec}s")
                    }
                    if (plan.note.isNotEmpty()) append("  ! ${plan.note}")

                    last?.sets?.groupBy { it.first }?.toSortedMap()?.forEach { (code, st) ->
                        append("  动作码 $code：${st.size} 组，均次 " +
                                "${st.map { it.second }.average().toInt()} → " +
                                PlanEngine.progression(st, emptyMap()))
                    }

                    writeJson(dir, sleep, works, ready)
                    append("== 已导出 JSON：${File(dir, "export.json").absolutePath}")
                } catch (e: Exception) {
                    append("✗ 失败：${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    private fun writeJson(dir: File, sleep: List<ZeppReader.SleepNight>, works: List<ZeppReader.Workout>, ready: PlanEngine.Readiness) {
        val root = org.json.JSONObject()
        val sl = org.json.JSONArray()
        sleep.forEach {
            sl.put(org.json.JSONObject()
                .put("day", it.day)
                .put("sleepStartMs", it.sleepStartMs)
                .put("sleepEndMs", it.sleepEndMs)
                .put("asleepMin", it.asleepMin)
                .put("lightMin", it.lightMin)
                .put("deepMin", it.deepMin)
                .put("remMin", it.remMin)
                .put("awakeMin", it.awakeMin)
                .put("avgHr", it.avgHr)
                .put("avgSpo2", it.avgSpo2))
        }
        val wl = org.json.JSONArray()
        works.forEach {
            val sets = org.json.JSONArray()
            it.sets.forEach { (a, r) -> sets.put(org.json.JSONArray().put(a).put(r)) }
            wl.put(org.json.JSONObject()
                .put("day", it.day).put("type", it.type).put("typeName", it.typeName)
                .put("durSec", it.durSec).put("avgHr", it.avgHr).put("maxHr", it.maxHr)
                .put("calories", it.calories).put("totalGroup", it.totalGroup)
                .put("exerciseLoad", it.exerciseLoad).put("rpe", it.rpe)
                .put("sets", sets))
        }
        root.put("readiness", org.json.JSONObject()
            .put("level", ready.level)
            .put("reasons", org.json.JSONArray(ready.reasons))
            .put("capRpe", ready.capRpe)
            .put("reduceBigSets", ready.reduceBigSets)
            .put("noWeightIncrease", ready.noWeightIncrease))
        root.put("sleep", sl)
        root.put("workouts", wl)
        File(dir, "export.json").writeText(root.toString(2))
    }

    private fun exportLatest() {
        val f = File(filesDir, "zepp/export.json")
        if (!f.exists()) {
            append("✗ 还没有导出，先点「拉取并分析」")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(i, "导出华米数据"))
    }
}

package com.boaom.huamiextractor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 训练计划引擎（谭成义 × 凯圣王 三分化框架）
 *
 * 原则：
 *  - 三分化按"每肌群每周约 2 次"排：推/拉/腿 循环，5 天工作日 → PPL/PP 交替
 *  - 大肌群 4-6 组、小肌群 2-4 组
 *  - 强度用 RPE 而不是固定 %1RM
 *  - 单次 ≤ 60 分钟、总组数 ≤ 20，拒绝垃圾容量
 *  - 渐进：次数达标 → 加重量；掉组数 → 减载
 *  - 恢复闸门：睡眠不足则主项减组、压 RPE 上限
 */
object PlanEngine {

    data class Move(
        val key: String,        // 动作键（动作码或自定义名）
        val name: String,
        val sets: Int,
        val repsLo: Int,
        val repsHi: Int,
        val targetRpe: Double,
        val restSec: Int,
        val big: Boolean,
        val kg: Double? = null
    )

    data class SplitPlan(
        val split: String,
        val moves: List<Move>,
        val note: String,
        val readiness: String,
        val estMin: Int
    )

    // 模板：动作名、组数、次数区间、目标 RPE、组间休息(秒)、是否大肌群
    private val PUSH = listOf(
        Move("bench", "杠铃卧推", 4, 5, 8, 7.5, 180, true),
        Move("ohp", "肩推", 3, 6, 10, 8.0, 150, true),
        Move("row_ts", "胸支撑划船", 2, 8, 12, 7.5, 90, false),
        Move("lat_raise", "侧平举", 3, 10, 15, 8.5, 75, false),
        Move("pushdown", "绳索下压", 3, 10, 15, 8.5, 75, false)
    )
    private val PULL = listOf(
        Move("pulldown", "高位下拉", 4, 6, 10, 7.5, 150, true),
        Move("row", "杠铃/器械划船", 4, 6, 10, 8.0, 150, true),
        Move("facepull", "面拉", 3, 12, 15, 8.0, 75, false),
        Move("curl", "弯举", 3, 8, 12, 8.5, 75, false)
    )
    private val LEGS = listOf(
        Move("squat", "深蹲", 4, 5, 8, 8.0, 240, true),
        Move("rdl", "罗马尼亚硬拉", 3, 6, 10, 7.5, 180, true),
        Move("legcurl", "腿弯举", 3, 10, 15, 8.5, 90, false),
        Move("calf", "提踵", 3, 12, 20, 9.0, 60, false)
    )

    /** 5 个工作日的循环：推拉腿推拉 → 下一轮腿推拉腿推，保证每肌群两周 3-4 次 */
    private val ROTATION = listOf("推", "拉", "腿", "推", "拉")

    private fun template(split: String) = when (split) {
        "推" -> PUSH; "拉" -> PULL; else -> LEGS
    }

    /** 距离基准日的天数 → 循环位（0 起） */
    fun splitForSession(index: Int): String = ROTATION[index % ROTATION.size]

    /**
     * 恢复评估：拿近 28 天睡眠做基线，看昨晚偏离多少。
     * 规则（个人基线，不用固定阈值）：
     *  - 少睡 ≥ 90 分钟 或 深睡降 ≥ 30% → 主项减 1 组，RPE 上限压到 7.5
     *  - 连续两晚不达标 → 今天不加重量
     *  - 连续三晚不达标 → 建议改轻量恢复
     */
    data class Readiness(
        val level: String,          // 好 / 一般 / 差
        val reasons: List<String>,
        val capRpe: Double,
        val reduceBigSets: Boolean,
        val noWeightIncrease: Boolean,
        val suggestLight: Boolean
    )

    fun assess(sleep: List<ZeppReader.SleepNight>): Readiness {
        if (sleep.isEmpty()) return Readiness("未知", listOf("没有睡眠数据"), 9.0, false, false, false)
        val last = sleep.last()
        val base = sleep.dropLast(1).takeLast(28)
        val reasons = ArrayList<String>()
        if (base.isEmpty()) return Readiness("一般", listOf("基线样本不足"), 8.5, false, false, false)

        val baseMin = base.map { it.asleepMin }.average()
        val baseDeep = base.map { it.deepMin }.average().coerceAtLeast(1.0)
        val shortBy = baseMin - last.asleepMin
        val deepDrop = (baseDeep - last.deepMin) / baseDeep

        val recent = sleep.takeLast(3).takeLast(2)   // 昨晚 + 前一晚
        val badNights = recent.count { it.asleepMin < baseMin - 60 || it.deepMin < baseDeep * 0.7 }

        var capRpe = 9.0
        var reduce = false
        var noWeight = false
        var light = false
        var level = "好"

        if (shortBy >= 90) {
            reasons.add("睡眠比基线少 ${shortBy.roundToInt()} 分钟（基线 ${baseMin.roundToInt()} 分）")
            capRpe = 7.5; reduce = true; level = "差"
        } else if (shortBy >= 45) {
            reasons.add("睡眠略少 ${shortBy.roundToInt()} 分钟")
            capRpe = 8.0; level = "一般"
        } else {
            reasons.add("睡眠达标（${last.asleepMin} 分钟）")
        }
        if (deepDrop >= 0.30) {
            reasons.add("深睡 ${last.deepMin} 分，比基线降 ${(deepDrop * 100).roundToInt()}%")
            if (level == "好") level = "一般"
            capRpe = min(capRpe, 8.0)
        } else {
            reasons.add("深睡 ${last.deepMin} 分，质量不差")
        }
        if (badNights >= 2) {
            reasons.add("连续两晚恢复不足")
            noWeight = true; reduce = true; capRpe = min(capRpe, 7.5); level = "差"
        } else if (badNights == 1) {
            reasons.add("前一晚恢复一般")
            noWeight = true
        }
        if (badNights >= 2 && sleep.size >= 3) {
            light = true
            reasons.add("建议今天改轻量恢复，别硬顶")
        }
        return Readiness(level, reasons, capRpe, reduce, noWeight, light)
    }

    /** 生成某天的课表 */
    fun plan(
        split: String,
        readiness: Readiness,
        lastStrength: ZeppReader.Workout?,
        kgTable: Map<String, Double>
    ): SplitPlan {
        val notes = ArrayList<String>()
        val moves = template(split).map { m ->
            var sets = m.sets
            var rpe = m.targetRpe
            if (readiness.reduceBigSets && m.big && sets > 3) {
                sets -= 1
                notes.add("${m.name} 减 1 组（恢复不足）")
            }
            rpe = min(rpe, readiness.capRpe)
            m.copy(sets = sets, targetRpe = rpe, kg = kgTable[m.key])
        }
        var est = 0
        for (m in moves) est += m.sets * (m.restSec + 40) / 60
        val totalSets = moves.sumOf { it.sets }
        if (totalSets > 20) notes.add("总组数 $totalSets 超过 20，需要砍量")
        if (est > 60) notes.add("预计 $est 分钟超 60 分钟上限，需压缩休息或减组")
        if (readiness.noWeightIncrease) notes.add("今天不加重量，维持次数")
        if (readiness.suggestLight) notes.add("恢复连续不足：建议只做主项 3 组 + 轻有氧")

        // 上次表现参考
        lastStrength?.let {
            notes.add("上次（${it.day}）：${it.totalGroup} 组 / ${it.durSec / 60} 分钟 / 均心率 ${it.avgHr}")
            if (it.rpe in 6..10) notes.add("上次自评 RPE ${it.rpe}")
        }
        return SplitPlan(split, moves, notes.joinToString("；"), readiness.level, est)
    }

    /** 渐进建议：基于动作码统计的次数表现 */
    fun progression(sets: List<Pair<Int, Int>>, kgTable: Map<String, Double>): String {
        if (sets.isEmpty()) return "首次：选能干净完成目标次数、RPE 约 7.5 的重量"
        val reps = sets.map { it.second }
        val avg = reps.average()
        val best = reps.max()
        return when {
            avg >= 12 -> "均 ${avg.roundToInt()} 次已到窗口上限 → 加重量（下次回到 5-8 次）"
            best >= 12 && avg < 10 -> "最好一组 ${best} 次但均次偏低 → 同重量把每组推上去"
            else -> "均 ${avg.roundToInt()} 次在窗口内 → 保持，单组冲上限"
        }
    }
}

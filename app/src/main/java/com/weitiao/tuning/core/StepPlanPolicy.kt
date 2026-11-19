package com.weitiao.tuning.core

/** Pure daily planning arithmetic. Times are milliseconds in the same local day's window. */
internal object StepPlanPolicy {
    fun dailyTarget(center: Int, jitterPct: Int, seed: Long, epochDay: Long, hardCap: Int): Int {
        val cap = hardCap.coerceAtLeast(1)
        val base = center.coerceIn(1, 1_000_000).toLong()
        val span = base * jitterPct.coerceIn(0, 50) / 100
        val random = java.util.Random(seed xor (epochDay * 1_000_003L))
        return (base + random.nextInt((span * 2 + 1).toInt()) - span).coerceIn(1L, cap.toLong()).toInt()
    }

    fun planned(realToday: Long, now: Long, startAt: Long, endAt: Long,
                startReal: Long, startValue: Long, peak: Long, target: Int): Long {
        val floor = maxOf(realToday, startValue, peak)
        if (target <= startValue || endAt <= startAt) return floor
        val p = ((now.toDouble() - startAt) / (endAt.toDouble() - startAt)).coerceIn(0.0, 1.0)
        val remain = target.toLong() - startValue
        val walked = (realToday - startReal).coerceIn(0L, remain)
        return maxOf(floor, startValue + (remain * p + walked * (1 - p)).toLong())
    }

    fun natural(realToday: Long, baselineReal: Long, peak: Long, multiplier: Int, hardCap: Long): Long {
        val cap = hardCap.coerceAtLeast(1L)
        val delta = (realToday - baselineReal).coerceIn(0L, cap)
        val gain = delta * multiplier.coerceIn(1, 10_000)
        return minOf(cap, maxOf(realToday, peak.coerceIn(0L, cap) + gain))
    }
}

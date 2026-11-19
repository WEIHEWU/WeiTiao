package com.weitiao.tuning.core

import java.time.Instant
import java.time.ZoneId
import java.security.SecureRandom

/** Daily business values, not hardware cumulative sensor values. Upload echoes never count as walking. */
internal class ExactStepEngine(
    private val load: () -> Map<String, Long>,
    private val save: (Map<String, Long>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val seedFactory: () -> Long = { SecureRandom().nextLong() },
) {
    @Synchronized
    fun evaluate(original: Long, cfg: TuningConfig, isRead: Boolean): Long {
        if (!cfg.stepEnabled || cfg.stepMode != TuningConfig.MODE_PLAN) return original
        if (isRead) {
            if (original < -1L) return original
        } else {
            if (original <= 0L) return original
        }
        val state = load().toMutableMap()
        val now = clock()
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
        val day = date.year * 10000L + date.monthValue * 100L + date.dayOfMonth
        fun minuteAt(minute: Int) = date.atStartOfDay().plusMinutes(minute.toLong())
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val start = minuteAt(cfg.stepWindowStart)
        val end = minuteAt(cfg.stepWindowEnd)
        val cap = cfg.stepMaximum.coerceAtLeast(1).toLong()
        val sameDay = state["exact_day"] == day
        val seed = state["exact_seed"] ?: seedFactory()
        val normalizedOriginal = if (isRead && original == -1L) {
            // Bytecode of verified read entry d.a() returns -1L when SportKv has no today record yet.
            if (sameDay) (state["exact_real"] ?: 0L).coerceAtLeast(0L) else 0L
        } else {
            original
        }
        if (!sameDay) {
            val priorDelivered = if (state["plan_day"] == day) state["step_current_delivered"] ?: 0L else 0L
            state.clear()
            state["exact_seed"] = seed
            state["exact_day"] = day
            state["exact_peak"] = maxOf(normalizedOriginal, priorDelivered)
            state["exact_real"] = normalizedOriginal
            state["exact_read_seen"] = if (isRead) 1 else 0
        }
        val oldReal = state["exact_real"] ?: normalizedOriginal
        // Only the unmodified return of the read method is real input. d(J) often receives our own a() result.
        val real = if (isRead) normalizedOriginal else oldReal
        state["exact_real"] = real
        if (isRead) state["exact_read_seen"] = 1
        var peak = state["exact_peak"] ?: normalizedOriginal
        if (!isRead) peak = maxOf(peak, normalizedOriginal)
        val signature = listOf(cfg.stepTarget, cfg.stepJitter, cfg.stepWindowStart, cfg.stepWindowEnd,
            cfg.stepAfterWindow, cap, cfg.stepGeneration).joinToString(":").hashCode().toLong()
        val rebuild = !sameDay || state["exact_signature"] != signature || state["exact_schema"] != 1L ||
            (state["exact_target"] ?: 0L) !in 1L..cap || state["exact_end"] != end ||
            (state["exact_start"] ?: 0L) !in start..end
        if (rebuild) {
            state["exact_schema"] = 1
            state["exact_signature"] = signature
            state["exact_start"] = maxOf(now, start).coerceAtMost(end)
            state["exact_end"] = end
            state["exact_start_real"] = real
            state["exact_start_value"] = peak
            state["exact_target"] = StepPlanPolicy.dailyTarget(cfg.stepTarget, cfg.stepJitter, seed, date.toEpochDay(), cap.toInt()).toLong()
            state["exact_phase"] = if (now < end) 1 else 2
            state["exact_natural_real"] = real
            // Preserve the observed walk in a settings-changing event outside the window.
            if (sameDay && isRead && (now < start || now >= end) && !(now >= end && cfg.stepAfterWindow == TuningConfig.AFTER_FREEZE)) {
                peak = maxOf(peak, (state["exact_peak"] ?: peak) + (real - oldReal).coerceAtLeast(0L))
            }
        } else if (now >= start && now < end) {
            peak = StepPlanPolicy.planned(real, now, state.getValue("exact_start"), end,
                state.getValue("exact_start_real"), state.getValue("exact_start_value"), peak, state.getValue("exact_target").toInt())
            state["exact_natural_real"] = real
        } else {
            if (now >= end && state["exact_phase"] == 1L) {
                peak = maxOf(peak, state.getValue("exact_target"))
                state["exact_phase"] = 2
                // The first reading across the boundary establishes a baseline; its time distribution is unknown.
                state["exact_natural_real"] = real
            } else if (!(now >= end && cfg.stepAfterWindow == TuningConfig.AFTER_FREEZE)) {
                peak = StepPlanPolicy.natural(real, state["exact_natural_real"] ?: real, peak, 1, cap)
            }
            state["exact_natural_real"] = real
        }
        // Already delivered values above a newly lowered cap cannot be withdrawn, but add no more.
        val result = if ((state["exact_peak"] ?: 0L) > cap) state.getValue("exact_peak") else peak.coerceAtMost(cap)
        state["exact_peak"] = result
        state["plan_day"] = day
        state["plan_gen"] = cfg.stepGeneration
        state["daily_target"] = state.getValue("exact_target")
        state["step_real_today"] = real
        state["step_current_delivered"] = result
        save(state) // Caller returns original on failure. No partially advanced in-memory state.
        return result
    }
}

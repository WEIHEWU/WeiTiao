package com.weitiao.tuning.core

/** Persisted hook diagnostics. Heartbeat health never proves that steps were modified. */
data class StepHookStatus(
    val hookStatus: String = "",
    val hookAliveAt: Long = 0L,
    val hookVersion: String = "",
    val wechatVersion: String = "",
    val lastOriginal: Long? = null,
    val lastEffective: Long? = null,
    val lastError: String = "",
    val lastBackend: String = "",
    val backendReason: String = "",
    val lastEventAt: Long = 0L,
    val lastErrorAt: Long = 0L,
    val lastEventSynthetic: Boolean = false,
    val configMode: String = "",
    val configBackend: String = "",
    val configGeneration: Long? = null,
) {
    val hasStepEvent: Boolean get() = lastEventAt > 0L && lastOriginal != null && lastEffective != null

    fun heartbeatHealth(hasRoot: Boolean, nowMillis: Long): HeartbeatHealth =
        heartbeatHealth(hasRoot, hookAliveAt, nowMillis)

    fun isConfigApplied(expected: TuningConfig): Boolean =
        configMode == expected.stepMode &&
            configBackend == expected.stepBackend &&
            configGeneration == expected.stepGeneration

    companion object {
        fun fromValues(strings: Map<String, String>, longs: Map<String, Long>, booleans: Map<String, Boolean>) = StepHookStatus(
            hookStatus = strings["hook_status"].orEmpty(),
            hookAliveAt = longs["hook_alive_at"] ?: 0L,
            hookVersion = strings["hook_version"].orEmpty(),
            wechatVersion = strings["wechat_version"].orEmpty(),
            lastOriginal = longs["last_original"],
            lastEffective = longs["last_effective"],
            lastError = strings["last_error"].orEmpty(),
            lastBackend = strings["last_backend"].orEmpty(),
            backendReason = strings["backend_reason"].orEmpty(),
            lastEventAt = longs["last_event_at"] ?: 0L,
            lastErrorAt = longs["last_error_at"] ?: 0L,
            lastEventSynthetic = booleans["last_event_synthetic"] ?: false,
            configMode = strings["config_mode"].orEmpty(),
            configBackend = strings["config_backend"].orEmpty(),
            configGeneration = longs["config_generation"] ?: longs["plan_gen"]?.takeIf { it > 0L },
        )
    }
}

enum class HeartbeatHealth { UNKNOWN, HEALTHY, STALE, DEAD }

/** Boundaries are inclusive; a future timestamp is unknown (clock changed). */
fun heartbeatHealth(hasRoot: Boolean, aliveAt: Long, nowMillis: Long): HeartbeatHealth = when {
    !hasRoot || aliveAt <= 0L || nowMillis < aliveAt -> HeartbeatHealth.UNKNOWN
    nowMillis - aliveAt >= 15L * 60_000L -> HeartbeatHealth.DEAD
    nowMillis - aliveAt >= 5L * 60_000L -> HeartbeatHealth.STALE
    else -> HeartbeatHealth.HEALTHY
}

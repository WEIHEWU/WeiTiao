package com.weitiao.tuning.core

import android.content.SharedPreferences

/** Written only by the WeChat main process. Heartbeats do not imply server acceptance. */
internal class StepHookReporter(
    private val prefs: SharedPreferences,
    private val moduleVersion: String,
    private val wechatVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastEventWrittenAt = 0L
    private var lastErrorWrittenAt = 0L

    @Synchronized
    fun heartbeat(
        enabled: Boolean,
        backend: String,
        reason: String,
        ready: Boolean,
        configMode: String = "",
        configBackend: String = "",
        configGeneration: Long = 0L,
    ) {
        prefs.edit()
            .putString("hook_status", if (!enabled) "DISABLED" else if (ready) "LOADED" else "WAITING_FOR_SENSOR")
            .putLong("hook_alive_at", clock())
            .putString("hook_version", moduleVersion)
            .putString("wechat_version", wechatVersion)
            .putString("last_backend", backend)
            .putString("backend_reason", reason.take(300))
            .putString("config_mode", configMode)
            .putString("config_backend", configBackend)
            .putLong("config_generation", configGeneration)
            .apply()
    }

    @Synchronized
    fun event(
        original: Float,
        effective: Float,
        synthetic: Boolean,
        configMode: String = "",
        configBackend: String = "",
        configGeneration: Long = 0L,
    ) {
        val now = clock()
        if (lastEventWrittenAt > 0L && now >= lastEventWrittenAt && now - lastEventWrittenAt < 30_000L) return
        lastEventWrittenAt = now
        val editor = prefs.edit()
            .putLong("last_event_at", now)
            .putLong("last_original", original.toLong())
            .putLong("last_effective", effective.toLong())
            .putBoolean("last_event_synthetic", synthetic)
        if (configMode.isNotBlank()) editor.putString("config_mode", configMode)
        if (configBackend.isNotBlank()) editor.putString("config_backend", configBackend)
        if (configGeneration > 0L) editor.putLong("config_generation", configGeneration)
        editor.apply()
    }

    @Synchronized
    fun error(error: Throwable) {
        val now = clock()
        if (lastErrorWrittenAt > 0L && now >= lastErrorWrittenAt && now - lastErrorWrittenAt < 60_000L) return
        lastErrorWrittenAt = now
        prefs.edit().putString("last_error", "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(300))
            .putLong("last_error_at", now).apply()
    }
}

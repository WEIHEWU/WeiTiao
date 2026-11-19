package com.weitiao.tuning.core

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class StepHookReporterTest {
    private class Store {
        val values = mutableMapOf<String, Any?>()
        var writes = 0
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "apply" -> { writes++; null }
                method.name == "commit" -> { writes++; true }
                else -> error("Unexpected editor call: ${method.name}")
            }
        } as SharedPreferences.Editor
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "edit" -> editor
                else -> error("Unexpected preferences call: ${method.name}")
            }
        } as SharedPreferences
    }

    @Test fun heartbeatsPersistWithoutEventsAndDoNotFabricateEventState() {
        val store = Store()
        var now = 1_000L
        val reporter = StepHookReporter(store.prefs, "module", "wechat") { now }
        reporter.heartbeat(true, "SENSOR", "fallback", false)
        assertEquals("WAITING_FOR_SENSOR", store.values["hook_status"])
        assertEquals(now, store.values["hook_alive_at"])
        assertEquals("module", store.values["hook_version"])
        assertEquals("wechat", store.values["wechat_version"])
        assertFalse(store.values.containsKey("last_event_at"))
        assertFalse(store.values.containsKey("last_effective"))
        now += 60_000L
        reporter.heartbeat(true, "SENSOR", "", true)
        assertEquals("LOADED", store.values["hook_status"])
        assertEquals(now, store.values["hook_alive_at"])
        reporter.heartbeat(false, "SENSOR", "", false)
        assertEquals("DISABLED", store.values["hook_status"])
    }

    @Test fun eventThrottleDoesNotThrottleHeartbeatAndAcceptsBoundary() {
        val store = Store()
        var now = 1_000L
        val reporter = StepHookReporter(store.prefs, "1", "8") { now }
        reporter.event(100f, 200f, false)
        now += 29_999L
        reporter.event(101f, 201f, true)
        assertEquals(100L, store.values["last_original"])
        assertEquals(1, store.writes)
        reporter.heartbeat(true, "SENSOR", "", true)
        assertEquals(now, store.values["hook_alive_at"])
        assertEquals(1_000L, store.values["last_event_at"])
        now++
        reporter.event(102f, 202f, true)
        assertEquals(102L, store.values["last_original"])
        assertEquals(202L, store.values["last_effective"])
        assertEquals(true, store.values["last_event_synthetic"])
    }

    @Test fun errorsAreRateLimitedIndependentlyAndRecoverAfterClockRollback() {
        val store = Store()
        var now = 100_000L
        val reporter = StepHookReporter(store.prefs, "1", "8") { now }
        reporter.error(IllegalStateException("first"))
        now += 59_999L
        reporter.error(IllegalStateException("second"))
        assertEquals(1, store.writes)
        reporter.event(1f, 2f, false)
        assertEquals(2, store.writes)
        now++
        reporter.error(IllegalStateException("third"))
        assertTrue((store.values["last_error"] as String).contains("third"))
        now = 1_000L
        reporter.error(IllegalStateException("rollback"))
        assertEquals(now, store.values["last_error_at"])
    }

    @Test fun heartbeatAndEventRecordActualReadConfigFields() {
        val store = Store()
        var now = 10_000L
        val reporter = StepHookReporter(store.prefs, "1.6.0", "8.0.80/3200") { now }
        reporter.heartbeat(
            enabled = true,
            backend = "EXACT",
            reason = "ok",
            ready = true,
            configMode = "PLAN",
            configBackend = "AUTO",
            configGeneration = 32L,
        )
        assertEquals("PLAN", store.values["config_mode"])
        assertEquals("AUTO", store.values["config_backend"])
        assertEquals(32L, store.values["config_generation"])

        reporter.event(
            original = -1f,
            effective = 0f,
            synthetic = false,
            configMode = "PLAN",
            configBackend = "EXACT",
            configGeneration = 33L,
        )
        assertEquals("PLAN", store.values["config_mode"])
        assertEquals("EXACT", store.values["config_backend"])
        assertEquals(33L, store.values["config_generation"])
    }
}

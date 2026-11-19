package com.weitiao.tuning.core

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TuningConfigTest {
    @Test
    fun defaultsAndConstructorCompatibility() {
        val config = TuningConfig()
        assertEquals(TuningConfig.BACKEND_SENSOR, config.stepBackend)
        assertEquals(TuningConfig.MODE_PLAN, config.stepMode)
        assertEquals(20_000, config.stepTarget)
        assertEquals(10, config.stepJitter)
        assertEquals(420, config.stepWindowStart)
        assertEquals(1230, config.stepWindowEnd)
        assertEquals(TuningConfig.AFTER_NATURAL, config.stepAfterWindow)
        assertEquals(TuningConfig.MODE_MULTIPLIER, TuningConfig(stepAuto = false).stepMode)
        assertEquals(TuningConfig.MODE_LEGACY_AUTO, TuningConfig(stepMode = TuningConfig.MODE_LEGACY_AUTO).stepMode)
    }

    @Test
    fun oldPrefsMigrateModeAndOnlyExplicitEndHour() {
        assertEquals(TuningConfig.MODE_PLAN, TuningConfig.fromPrefs(prefs()).stepMode)
        assertEquals(1230, TuningConfig.fromPrefs(prefs()).stepWindowEnd)
        assertEquals(TuningConfig.MODE_MULTIPLIER,
            TuningConfig.fromPrefs(prefs(TuningConfig.KEY_STEP_AUTO to false)).stepMode)
        assertEquals(TuningConfig.MODE_PLAN,
            TuningConfig.fromPrefs(prefs(TuningConfig.KEY_STEP_AUTO to true)).stepMode)
        assertEquals(1440, TuningConfig.fromPrefs(prefs(TuningConfig.KEY_STEP_END_HOUR to 0)).stepWindowEnd)
        assertEquals(1200, TuningConfig.fromPrefs(prefs(TuningConfig.KEY_STEP_END_HOUR to 20)).stepWindowEnd)
    }

    @Test
    fun newPrefsOverrideLegacyMigration() {
        val config = TuningConfig.fromPrefs(prefs(
            TuningConfig.KEY_STEP_AUTO to false,
            TuningConfig.KEY_STEP_MODE to TuningConfig.MODE_PLAN,
            TuningConfig.KEY_STEP_END_HOUR to 0,
            TuningConfig.KEY_STEP_WINDOW_END to 1230,
        ))
        assertEquals(TuningConfig.MODE_PLAN, config.stepMode)
        assertEquals(1230, config.stepWindowEnd)
        assertEquals(0, config.stepEndHour)
    }

    @Test
    fun invalidPlanningPrefsAreNormalized() {
        val config = TuningConfig.fromPrefs(prefs(
            TuningConfig.KEY_STEP_MODE to "invalid",
            TuningConfig.KEY_STEP_TARGET to -1,
            TuningConfig.KEY_STEP_JITTER to 80,
            TuningConfig.KEY_STEP_WINDOW_START to 1500,
            TuningConfig.KEY_STEP_WINDOW_END to -3,
            TuningConfig.KEY_STEP_AFTER_WINDOW to "invalid",
        ))
        assertEquals(TuningConfig.MODE_PLAN, config.stepMode)
        assertEquals(1, config.stepTarget)
        assertEquals(50, config.stepJitter)
        assertEquals(1439, config.stepWindowStart)
        assertEquals(1440, config.stepWindowEnd)
        assertEquals(TuningConfig.AFTER_NATURAL, config.stepAfterWindow)
    }

    @Test
    fun unrelatedAndRepeatedSavesKeepGenerationEvenWithStaleCaller() {
        val old = TuningConfig(stepGeneration = 12L)
        val changes = listOf(
            old.copy(antiRecall = true), old.copy(brightScreenFastCharge = true),
            old.copy(passThrough = true), old.copy(passThroughLimit = 40),
            old.copy(thermalCutoffCelsius = 46), old.copy(stepGeneration = 0L),
        )
        changes.forEach { assertEquals(12L, it.prepareForSave(old).stepGeneration) }
        val saved = old.copy(stepTarget = 21_000).prepareForSave(old)
        assertEquals(13L, saved.stepGeneration)
        assertEquals(13L, saved.copy(stepGeneration = 0L).prepareForSave(saved).stepGeneration)
    }

    @Test
    fun everyStepSettingInvalidatesGeneration() {
        val old = TuningConfig(stepGeneration = 7L)
        listOf(
            old.copy(stepEnabled = true), old.copy(stepMode = TuningConfig.MODE_MULTIPLIER),
            old.copy(stepAuto = false), old.copy(stepMultiplier = 4), old.copy(stepMaximum = 30_000),
            old.copy(stepEndHour = 20), old.copy(stepTarget = 25_000), old.copy(stepJitter = 15),
            old.copy(stepWindowStart = 480), old.copy(stepWindowEnd = 1320),
            old.copy(stepAfterWindow = TuningConfig.AFTER_FREEZE),
            old.copy(stepBackend = TuningConfig.BACKEND_AUTO), old.copy(stepBackend = TuningConfig.BACKEND_EXACT),
        ).forEach { assertEquals(8L, it.prepareForSave(old).stepGeneration) }
    }

    @Test
    fun writerRoundTripsAllNewAndLegacyFields() {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val original = TuningConfig(stepEnabled = true, stepAuto = false, stepMode = TuningConfig.MODE_LEGACY_AUTO,
            stepTarget = 23_456, stepJitter = 17, stepWindowStart = 421, stepWindowEnd = 1231,
            stepAfterWindow = TuningConfig.AFTER_FREEZE, stepGeneration = 9L, stepBackend = TuningConfig.BACKEND_EXACT,
            stepMultiplier = 7, stepMaximum = 40_000, stepEndHour = 0)
        TuningConfig.write(editor, original)
        assertEquals(original, TuningConfig.fromPrefs(prefs(*values.toList().toTypedArray())))
        assertTrue(values.containsKey(TuningConfig.KEY_STEP_AUTO))
        assertTrue(values.containsKey(TuningConfig.KEY_STEP_END_HOUR))
    }

    @Test
    fun backendDefaultsAndInvalidValuesNormalizeToSensor() {
        assertEquals(TuningConfig.BACKEND_SENSOR, TuningConfig.fromPrefs(prefs()).stepBackend)
        assertEquals(TuningConfig.BACKEND_SENSOR,
            TuningConfig.fromPrefs(prefs(TuningConfig.KEY_STEP_BACKEND to "invalid")).stepBackend)
        for (backend in listOf(TuningConfig.BACKEND_SENSOR, TuningConfig.BACKEND_AUTO, TuningConfig.BACKEND_EXACT)) {
            assertEquals(backend, TuningConfig.fromPrefs(prefs(TuningConfig.KEY_STEP_BACKEND to backend)).stepBackend)
        }
        val old = TuningConfig(stepGeneration = 4L)
        assertEquals(4L, old.copy(stepBackend = "invalid").prepareForSave(old).stepGeneration)
    }

    @Test
    fun verifyRemoteConfigMatchesDetectsModeBackendAndGenerationMismatches() {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val expected = TuningConfig(
            stepEnabled = true,
            stepMode = TuningConfig.MODE_PLAN,
            stepBackend = TuningConfig.BACKEND_EXACT,
            stepTarget = 7_000,
            stepGeneration = 32L,
        )
        assertFalse(TuningPrefs.verifyRemoteConfigMatches(prefs(), expected))

        TuningConfig.write(editor, expected)
        val remote = prefs(*values.toList().toTypedArray())
        assertTrue(TuningPrefs.verifyRemoteConfigMatches(remote, expected))
        assertFalse(TuningPrefs.verifyRemoteConfigMatches(remote, expected.copy(stepGeneration = 33L)))
        assertFalse(TuningPrefs.verifyRemoteConfigMatches(remote, expected.copy(stepMode = TuningConfig.MODE_LEGACY_AUTO)))
        assertFalse(TuningPrefs.verifyRemoteConfigMatches(remote, expected.copy(stepBackend = TuningConfig.BACKEND_SENSOR)))
    }

    @Test
    fun resolveRemoteSyncStateDistinguishesSyncedUnsyncedAndUnknown() {
        val values = mutableMapOf<String, Any?>()
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when {
                method.name.startsWith("put") -> { values[args!![0] as String] = args[1]; proxy }
                method.name == "commit" -> true
                else -> null
            }
        } as SharedPreferences.Editor
        val expected = TuningConfig(
            stepEnabled = true,
            stepMode = TuningConfig.MODE_PLAN,
            stepBackend = TuningConfig.BACKEND_EXACT,
            stepTarget = 10_300,
            stepGeneration = 68L,
        )
        TuningConfig.write(editor, expected)
        val matchingRemote = prefs(*values.toList().toTypedArray())

        assertEquals(
            true,
            TuningPrefs.resolveRemoteSyncState(
                local = prefs(TuningPrefs.KEY_REMOTE_SYNC_OK to true),
                remote = matchingRemote,
                expected = expected,
            ),
        )
        assertEquals(
            false,
            TuningPrefs.resolveRemoteSyncState(
                local = prefs(TuningPrefs.KEY_REMOTE_SYNC_OK to true),
                remote = matchingRemote,
                expected = expected.copy(stepGeneration = 69L),
            ),
        )
        assertEquals(
            false,
            TuningPrefs.resolveRemoteSyncState(
                local = prefs(CscConstants.KEY_LOCAL_SAVED_AT to 100L, TuningPrefs.KEY_REMOTE_SYNC_OK to false),
                remote = null,
                expected = expected,
            ),
        )
        assertNull(
            TuningPrefs.resolveRemoteSyncState(
                local = prefs(CscConstants.KEY_LOCAL_SAVED_AT to 100L, TuningPrefs.KEY_REMOTE_SYNC_OK to true),
                remote = null,
                expected = expected,
            ),
        )
        assertNull(
            TuningPrefs.resolveRemoteSyncState(
                local = prefs(),
                remote = null,
                expected = expected,
            ),
        )
    }

    private fun prefs(vararg entries: Pair<String, Any?>): SharedPreferences {
        val values = entries.toMap()
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values
                "getBoolean", "getInt", "getLong", "getString" -> values[args!![0]] ?: args[1]
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        } as SharedPreferences
    }
}

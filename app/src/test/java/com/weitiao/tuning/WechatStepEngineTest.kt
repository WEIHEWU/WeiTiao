package com.weitiao.tuning

import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscSpoof
import com.weitiao.tuning.core.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatStepEngineTest {

    private fun createTestEngine(): WechatHooks.WechatStepEngine {
        return WechatHooks.WechatStepEngine { _, _ -> }
    }

    @Test
    fun stepEngineStrictlyCapsStepsUnderMaximum() {
        val engine = createTestEngine()
        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 100, // 100x multiplier
            stepMaximum = 80_000,
            stepAuto = false,
            stepEndHour = 24,
        )

        var raw = 1000f
        var lastOutput = 0f

        // Initial event
        var output = engine.processStepCounter(raw, config)
        assertTrue("Initial output should be positive", output >= 0f)
        lastOutput = output

        // Walk 1000 steps with 100x multiplier = +100,000 attempted steps
        raw += 1000f
        output = engine.processStepCounter(raw, config)

        assertTrue("Output must be monotonically increasing: $output >= $lastOutput", output >= lastOutput)

        // Keep walking a lot
        for (i in 1..50) {
            raw += 500f
            val newOutput = engine.processStepCounter(raw, config)
            assertTrue("Output must be strictly non-decreasing: $newOutput >= $output", newOutput >= output)
            output = newOutput
        }

        assertTrue("Final output must be finite", output.isFinite())
    }

    @Test
    fun stepEngineMaintainsMonotonicityOnDeviceReboot() {
        val engine = createTestEngine()
        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 10,
            stepMaximum = 60_000,
            stepAuto = false,
        )

        var raw = 15000f
        var output1 = engine.processStepCounter(raw, config)

        raw += 500f
        var output2 = engine.processStepCounter(raw, config)
        assertTrue(output2 >= output1)

        // SIMULATE DEVICE REBOOT: raw hardware sensor resets to 10f
        val rebootRaw = 10f
        var outputAfterReboot = engine.processStepCounter(rebootRaw, config)

        assertTrue(
            "Output must NOT drop after reboot: outputAfterReboot ($outputAfterReboot) >= output2 ($output2)",
            outputAfterReboot >= output2,
        )

        // Walking after reboot resumes smoothly
        val outputAfterRebootWalk = engine.processStepCounter(rebootRaw + 100f, config)
        assertTrue(
            "Output after walking post-reboot must increase smoothly",
            outputAfterRebootWalk >= outputAfterReboot,
        )
    }

    @Test
    fun stepEngineAutoModeStrictlyCapsAtMaximum() {
        val engine = createTestEngine()
        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 100,
            stepMaximum = 50_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        val raw = 2000f
        // Simulate 1000 sensor events in auto mode
        var last = 0f
        for (i in 1..1000) {
            val out = engine.processStepCounter(raw, config)
            assertTrue("Auto mode output must be non-decreasing: $out >= $last", out >= last)
            last = out
        }

        // Today's added steps = last - raw. Must NOT exceed stepMaximum (50,000)!
        val todaySteps = last - raw
        assertTrue("Today's steps ($todaySteps) must not exceed 50,000", todaySteps <= 50_000f)
        assertTrue("Today's steps ($todaySteps) should reach maximum 50,000", todaySteps == 50_000f)
    }

    @Test
    fun stepEngineFreezesAtEndHour() {
        val engine = createTestEngine()
        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 50,
            stepMaximum = 50_000,
            stepEndHour = 0, // 0 = don't stop / 24h
        )

        val out1 = engine.processStepCounter(1000f, config)
        val out2 = engine.processStepCounter(1500f, config)
        assertTrue(out2 > out1)

        val out3 = engine.processStepCounter(1600f, config)
        assertTrue("Subsequent steps increase monotonically", out3 >= out2)
    }

    @Test
    fun stepEngineRealStepMultiplierWorksFor1xAndNx() {
        val engine1x = createTestEngine()
        val config1x = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 1,
            stepMaximum = 50_000,
            stepAuto = false,
            stepEndHour = 24,
        )
        val base1 = engine1x.processStepCounter(1000f, config1x)
        val after100Steps1x = engine1x.processStepCounter(1100f, config1x)
        assertTrue("1x multiplier must add 100 steps", (after100Steps1x - base1) == 100f)

        val engine10x = createTestEngine()
        val config10x = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 10,
            stepMaximum = 50_000,
            stepAuto = false,
            stepEndHour = 24,
        )
        val base10 = engine10x.processStepCounter(1000f, config10x)
        val after100Steps10x = engine10x.processStepCounter(1100f, config10x)
        assertTrue("10x multiplier must add 1000 steps", (after100Steps10x - base10) == 1000f)
    }

    @Test
    fun liveWechatHookEstablishesZeroJumpBaselineAndIncrementsSmoothly() {
        val engine = createTestEngine()
        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 50,
            stepMaximum = 20_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        val values = floatArrayOf(3500f)
        // Event #1: must establish baseline with 0 spike so WeChat sets preSensorStep = 3500
        engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
        assertEquals(3500f, values[0], 0.01f)

        // Subsequent events: each event must add <= 2000 steps smoothly until reaching 20,000
        var prev = values[0]
        var wechatAccumulatedToday = 0f
        for (i in 1..25) {
            values[0] = 3500f // stationary phone
            engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
            val diff = values[0] - prev
            assertTrue("Per-event diff ($diff) must be non-negative and <= 2000", diff in 0f..2000f)
            wechatAccumulatedToday += diff
            prev = values[0]
        }

        assertEquals(20_000f, wechatAccumulatedToday, 0.01f)
        assertFalse("Auto tick should stop once maximum is reached", engine.shouldDispatchAutoTick(config))
    }

    @Test
    fun liveWechatHookBuffersLargeMultiplierStepGainsSmoothly() {
        val engine = createTestEngine()
        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 500,
            stepMaximum = 50_000,
            stepAuto = false,
            stepEndHour = 24,
        )

        // Event #1: baseline at 1000f
        val values = floatArrayOf(1000f)
        engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
        assertEquals(1000f, values[0], 0.01f)

        // Event #2: walk 20 real steps (+10,000 target steps)
        values[0] = 1020f
        engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
        // First chunk must be capped at 2,000 so WeChat doesn't reject it as a spike
        assertEquals(3000f, values[0], 0.01f)
        assertTrue("Pending steps should keep auto ticker active", engine.shouldDispatchAutoTick(config))

        // 4 subsequent ticks drain the remaining 8,000 buffered steps in 2,000-step chunks
        for (tick in 1..4) {
            values[0] = 1020f
            engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
            assertEquals(1000f + (tick + 1) * 2000f, values[0], 0.01f)
        }
        assertFalse("All pending steps drained", engine.shouldDispatchAutoTick(config))
    }

    @Test
    fun cscSpoofExcludesWechatInGlobalDefaultMode() {
        val defaultAllAppsConfig = CscConfig(
            enabled = true,
            spoofDevice = true,
            deviceName = "iPhone Duo",
            deviceTargetFilterEnabled = false,
            deviceTargetPackages = emptySet(),
        )
        assertFalse(
            "WeChat must be excluded from global device spoof by default",
            CscSpoof.isDeviceSpoofTarget(defaultAllAppsConfig, "com.tencent.mm"),
        )
        assertTrue(
            "Other apps should still be spoofed in global default mode",
            CscSpoof.isDeviceSpoofTarget(defaultAllAppsConfig, "com.ss.android.ugc.aweme"),
        )

        val explicitWechatConfig = CscConfig(
            enabled = true,
            spoofDevice = true,
            deviceName = "iPhone Duo",
            deviceTargetFilterEnabled = true,
            deviceTargetPackages = setOf("com.tencent.mm"),
        )
        assertTrue(
            "WeChat can still be spoofed when explicitly added to deviceTargetPackages",
            CscSpoof.isDeviceSpoofTarget(explicitWechatConfig, "com.tencent.mm"),
        )
    }
}

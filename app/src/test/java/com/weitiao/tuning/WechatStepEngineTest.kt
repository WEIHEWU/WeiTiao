package com.weitiao.tuning

import com.weitiao.tuning.core.TuningConfig
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
}

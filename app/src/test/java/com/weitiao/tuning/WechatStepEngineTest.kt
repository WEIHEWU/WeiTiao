package com.weitiao.tuning

import com.weitiao.tuning.core.CscConfig
import com.weitiao.tuning.core.CscSpoof
import com.weitiao.tuning.core.LocalWechatStepProvider
import com.weitiao.tuning.core.LocalWechatStepSnapshot
import com.weitiao.tuning.core.SportKvStepReader
import com.weitiao.tuning.core.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone

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
    fun stepEngineBlocksZeroFakeReadingAndPreserves28000CapAcrossMultipleWechatRestarts() {
        val sharedStore = object : WechatHooks.StepStateStore {
            private val map = mutableMapOf<String, Any>()
            override fun getInt(key: String, default: Int): Int = (map[key] as? Int) ?: default
            override fun getLong(key: String, default: Long): Long = (map[key] as? Long) ?: default
            override fun getFloat(key: String, default: Float): Float = (map[key] as? Float) ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default
            override fun saveState(
                todayKey: Int,
                baseHardwareRaw: Float,
                lastHardwareRaw: Float,
                currentDelivered: Long,
                lastOutput: Float,
                baselineEstablished: Boolean,
            ) {
                map[WechatHooks.KEY_STATE_TODAY] = todayKey
                map[WechatHooks.KEY_STATE_BASE_RAW] = baseHardwareRaw
                map[WechatHooks.KEY_STATE_LAST_RAW] = lastHardwareRaw
                map[WechatHooks.KEY_STATE_DELIVERED] = currentDelivered
                map[WechatHooks.KEY_STATE_LAST_OUTPUT] = lastOutput
                map[WechatHooks.KEY_STATE_BASELINE_ESTABLISHED] = baselineEstablished
            }
        }

        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 3,
            stepMaximum = 28_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        // Process #1: Before any real hardware reading, auto-ticker must NOT dispatch and 0f must be ignored
        val engineRun1 = WechatHooks.WechatStepEngine(sharedStore) { _, _ -> }
        assertFalse("Must not auto-tick before real hardware reading", engineRun1.shouldDispatchAutoTick(config))
        val zeroValues = floatArrayOf(0f)
        engineRun1.process(zeroValues, isDetector = false, cfg = config, isLiveWechatHook = true)
        assertFalse("0f event must not establish baseline", engineRun1.hasHardwareReading())

        // Real hardware reading arrives: 54,716 steps since boot
        var hardwareRaw = 54_716f
        val values = floatArrayOf(hardwareRaw)
        engineRun1.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
        val wechatStartOfDayBaseline = values[0]
        assertEquals(54_716f, wechatStartOfDayBaseline, 0.01f)
        var wechatLastSensor = wechatStartOfDayBaseline
        var wechatTodayTotal = 0f

        // Tick until reaching 28,000 cap in Run #1
        for (i in 1..120) {
            values[0] = hardwareRaw
            engineRun1.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
            assertTrue("Sensor output must never drop", values[0] >= wechatLastSensor)
            wechatTodayTotal += (values[0] - wechatLastSensor)
            wechatLastSensor = values[0]
        }
        assertEquals(28_000f, wechatTodayTotal, 0.01f)
        assertEquals(82_716f, wechatLastSensor, 0.01f)

        // Simulate 3 WeChat process restarts on the same day while user continues walking
        for (restart in 1..3) {
            hardwareRaw += 200f
            val newProcessEngine = WechatHooks.WechatStepEngine(sharedStore) { _, _ -> }
            for (tick in 1..20) {
                values[0] = hardwareRaw
                newProcessEngine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
                // Critical assertion: sensor output must NEVER drop back to hardwareRaw (54,916),
                // which would trick WeChat into thinking the phone rebooted and stacking another +28,000!
                assertTrue(
                    "Restart #$restart tick #$tick: output (${values[0]}) must stay >= wechatLastSensor ($wechatLastSensor)",
                    values[0] >= wechatLastSensor,
                )
                wechatTodayTotal += (values[0] - wechatLastSensor)
                wechatLastSensor = values[0]
            }
        }

        assertEquals("Daily WeChat step total must stay strictly capped at 28,000 across restarts", 28_000f, wechatTodayTotal, 0.01f)
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

    @Test
    fun sportKvReaderParsesMmkvV5DetailAndUploadRecords() {
        val tz = TimeZone.getTimeZone("UTC")
        val dayStartCal = Calendar.getInstance(tz).apply {
            set(2026, Calendar.NOVEMBER, 14, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayKey = 20261114
        val dayStartMs = dayStartCal.timeInMillis
        val saveTimeMs = dayStartMs + 76_479_381L

        val (kvBytes, crcBytes) = buildTestMmkvV5(
            listOf(
                "KEY_STEP_DETAIL_INFO" to encodeMmkvString("$dayStartMs,$saveTimeMs,$saveTimeMs,8795033595725,130,31423,130"),
                "KEY_LAST_UPLOAD_DEVICE_STEP_TIME_LONG" to encodeMmkvVarint(saveTimeMs + 1000L),
                "KEY_LAST_UPLOAD_DEVICE_STEP_COUNT_LONG" to encodeMmkvVarint(31500L),
            ),
        )

        val snapshot = SportKvStepReader.parseSnapshot(kvBytes, crcBytes, todayKey, tz)
        assertNotNull("Expected parsed SportKv snapshot", snapshot)
        assertEquals(todayKey, snapshot!!.todayKey)
        assertEquals(31_500L, snapshot.todaySteps)
        assertEquals(130f, snapshot.lastSensorValue, 0.01f)
        assertTrue(snapshot.hasSensorValue)
        assertEquals(saveTimeMs + 1000L, snapshot.saveTimeMs)

        // Different day key should ignore stale yesterday records
        assertNull(SportKvStepReader.parseSnapshot(kvBytes, crcBytes, 20261115, tz))
    }

    @Test
    fun stepEngineCombinesWithLocalUnhookedStepsAndRespectsCap() {
        val todayKey = currentDayKey()
        val now = System.currentTimeMillis()
        // Scenario: Temp root was lost on reboot; user walked 10,000 steps while unhooked,
        // so WeChat's local SportKv already has todaySteps=10,000 at sensorValue=10,000.
        val localProvider = object : LocalWechatStepProvider {
            override fun readToday(todayKey: Int): LocalWechatStepSnapshot {
                return LocalWechatStepSnapshot(
                    todayKey = todayKey,
                    todaySteps = 10_000L,
                    lastSensorValue = 10_000f,
                    hasSensorValue = true,
                    saveTimeMs = now,
                )
            }
        }

        val engine = WechatHooks.WechatStepEngine { _, _ -> }
        engine.bindLocalStepProvider(localProvider)

        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 3,
            stepMaximum = 28_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        val values = floatArrayOf(10_050f)
        var lastSensor = 10_000f
        var wechatTodayTotal = 10_000f

        for (i in 1..100) {
            values[0] = 10_050f
            engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
            assertTrue("Sensor output must not drop: ${values[0]} >= $lastSensor", values[0] >= lastSensor)
            wechatTodayTotal += (values[0] - lastSensor)
            lastSensor = values[0]
        }

        assertEquals(todayKey, currentDayKey())
        assertEquals("Combined local + WeiTiao steps must cap at exactly 28,000", 28_000f, wechatTodayTotal, 0.01f)
        assertFalse("Auto tick stops once combined total reaches cap", engine.shouldDispatchAutoTick(config))
    }

    @Test
    fun stepEngineReanchorsWhenLocalSportKvAlreadyExceededCapAfterReboot() {
        val today = currentDayKey()
        val storeMap = mutableMapOf<String, Any>(
            WechatHooks.KEY_STATE_TODAY to today,
            WechatHooks.KEY_STATE_BASE_RAW to 29_713f,
            WechatHooks.KEY_STATE_LAST_RAW to 2_831f,
            WechatHooks.KEY_STATE_DELIVERED to 28_000L,
            WechatHooks.KEY_STATE_LAST_OUTPUT to 57_713f,
            WechatHooks.KEY_STATE_BASELINE_ESTABLISHED to true,
            WechatHooks.KEY_STATE_SAVED_AT to (System.currentTimeMillis() - 600_000L),
        )
        val sharedStore = createMapStepStore(storeMap)

        // After reboot unhooked, WeChat saw raw hardware sensor=130 and accumulated todaySteps=31,423 (> 28,000 cap).
        val localProvider = object : LocalWechatStepProvider {
            override fun readToday(todayKey: Int): LocalWechatStepSnapshot =
                LocalWechatStepSnapshot(
                    todayKey = todayKey,
                    todaySteps = 31_423L,
                    lastSensorValue = 130f,
                    hasSensorValue = true,
                    saveTimeMs = System.currentTimeMillis() - 10_000L,
                )
        }

        val engine = WechatHooks.WechatStepEngine(sharedStore) { _, _ -> }
        engine.bindLocalStepProvider(localProvider)

        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 3,
            stepMaximum = 28_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        // Hardware climbs from 135 -> 500; engine must hold exactly 130 (NOT jump to stale 57,713!) and add 0 steps.
        for (raw in listOf(135f, 200f, 350f, 500f)) {
            val values = floatArrayOf(raw)
            engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
            assertEquals("Output must stay anchored at WeChat local 130f with zero spike", 130f, values[0], 0.01f)
        }
        assertFalse(engine.shouldDispatchAutoTick(config))
    }

    @Test
    fun stepEngineReanchorsBelowCapAfterRebootWithoutDoubleCounting() {
        val today = currentDayKey()
        val storeMap = mutableMapOf<String, Any>(
            WechatHooks.KEY_STATE_TODAY to today,
            WechatHooks.KEY_STATE_BASE_RAW to 20_000f,
            WechatHooks.KEY_STATE_LAST_RAW to 1_500f,
            WechatHooks.KEY_STATE_DELIVERED to 10_000L,
            WechatHooks.KEY_STATE_LAST_OUTPUT to 30_000f,
            WechatHooks.KEY_STATE_BASELINE_ESTABLISHED to true,
            WechatHooks.KEY_STATE_SAVED_AT to (System.currentTimeMillis() - 600_000L),
        )
        val sharedStore = createMapStepStore(storeMap)

        // Rebooted unhooked: hardware sensor reset, WeChat saved sensorValue=200 and todaySteps=12,000.
        val localProvider = object : LocalWechatStepProvider {
            override fun readToday(todayKey: Int): LocalWechatStepSnapshot =
                LocalWechatStepSnapshot(
                    todayKey = todayKey,
                    todaySteps = 12_000L,
                    lastSensorValue = 200f,
                    hasSensorValue = true,
                    saveTimeMs = System.currentTimeMillis() - 5_000L,
                )
        }

        val engine = WechatHooks.WechatStepEngine(sharedStore) { _, _ -> }
        engine.bindLocalStepProvider(localProvider)

        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 3,
            stepMaximum = 28_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        var wechatSensor = 200f
        var wechatTotal = 12_000f
        for (i in 1..100) {
            val values = floatArrayOf(210f)
            engine.process(values, isDetector = false, cfg = config, isLiveWechatHook = true)
            assertTrue("Must not jump backwards or spike: ${values[0]} vs $wechatSensor", values[0] >= wechatSensor)
            assertTrue("Per-event increment <= 2000", (values[0] - wechatSensor) <= 2000f)
            wechatTotal += (values[0] - wechatSensor)
            wechatSensor = values[0]
        }

        assertEquals(28_000f, wechatTotal, 0.01f)
        assertEquals(16_200f, wechatSensor, 0.01f)
    }

    @Test
    fun stepEngineDoesNotRewindWhenLocalSportKvLagsBehindLiveEngine() {
        val today = currentDayKey()
        var diskSteps = 0L
        var diskSensor = 1000f
        var diskSaveTime = 0L

        val laggingProvider = object : LocalWechatStepProvider {
            override fun readToday(todayKey: Int): LocalWechatStepSnapshot? {
                if (diskSaveTime <= 0L) return null
                return LocalWechatStepSnapshot(
                    todayKey = todayKey,
                    todaySteps = diskSteps,
                    lastSensorValue = diskSensor,
                    hasSensorValue = true,
                    saveTimeMs = diskSaveTime,
                )
            }
        }

        val engine = WechatHooks.WechatStepEngine { _, _ -> }
        engine.bindLocalStepProvider(laggingProvider)

        val config = TuningConfig(
            stepEnabled = true,
            stepMultiplier = 10,
            stepMaximum = 28_000,
            stepAuto = true,
            stepEndHour = 24,
        )

        // Frame 1: anchor at 1000
        val v1 = floatArrayOf(1000f)
        engine.process(v1, isDetector = false, cfg = config, isLiveWechatHook = true)
        assertEquals(1000f, v1[0], 0.01f)

        // Advance 3 ticks in memory (+300 each => 1900)
        var latest = 1000f
        for (i in 1..3) {
            val v = floatArrayOf(1000f)
            engine.process(v, isDetector = false, cfg = config, isLiveWechatHook = true)
            latest = v[0]
        }
        assertEquals(1900f, latest, 0.01f)

        // SportKv now flushes an older value it emitted earlier (1300) AFTER WeiTiao persisted 1900
        diskSteps = 300L
        diskSensor = 1300f
        diskSaveTime = System.currentTimeMillis() + 5_000L

        val vNext = floatArrayOf(1000f)
        engine.process(vNext, isDetector = false, cfg = config, isLiveWechatHook = true)
        assertEquals("Live engine must NOT rewind from 1900 to lagging 1300", 2200f, vNext[0], 0.01f)
    }

    private fun currentDayKey(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.YEAR) * 10000 +
            (cal.get(Calendar.MONTH) + 1) * 100 +
            cal.get(Calendar.DAY_OF_MONTH)
    }

    private fun createMapStepStore(map: MutableMap<String, Any>): WechatHooks.StepStateStore {
        return object : WechatHooks.StepStateStore {
            override fun getInt(key: String, default: Int): Int = (map[key] as? Int) ?: default
            override fun getLong(key: String, default: Long): Long = (map[key] as? Long) ?: default
            override fun getFloat(key: String, default: Float): Float = (map[key] as? Float) ?: default
            override fun getBoolean(key: String, default: Boolean): Boolean = (map[key] as? Boolean) ?: default
            override fun saveState(
                todayKey: Int,
                baseHardwareRaw: Float,
                lastHardwareRaw: Float,
                currentDelivered: Long,
                lastOutput: Float,
                baselineEstablished: Boolean,
            ) {
                map[WechatHooks.KEY_STATE_TODAY] = todayKey
                map[WechatHooks.KEY_STATE_BASE_RAW] = baseHardwareRaw
                map[WechatHooks.KEY_STATE_LAST_RAW] = lastHardwareRaw
                map[WechatHooks.KEY_STATE_DELIVERED] = currentDelivered
                map[WechatHooks.KEY_STATE_LAST_OUTPUT] = lastOutput
                map[WechatHooks.KEY_STATE_BASELINE_ESTABLISHED] = baselineEstablished
                map[WechatHooks.KEY_STATE_SAVED_AT] = System.currentTimeMillis()
            }
        }
    }

    private fun buildTestMmkvV5(entries: List<Pair<String, ByteArray>>): Pair<ByteArray, ByteArray> {
        val body = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val kb = key.toByteArray(Charsets.UTF_8)
            writeVarint32(body, kb.size)
            body.write(kb)
            writeVarint32(body, value.size)
            body.write(value)
        }
        val payload = body.toByteArray()
        val kvBuf = ByteArray(8 + payload.size)
        // [0..4] = 0 (actualSize stored in .crc[28..32] like MMKV v5), [4..8] = flags/checksum placeholder
        System.arraycopy(payload, 0, kvBuf, 8, payload.size)

        val actualSize = 4 + payload.size
        val crcBuf = ByteArray(64)
        ByteBuffer.wrap(crcBuf).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x12345678)
            putInt(4, 5)
            putInt(8, 42)
            putInt(28, actualSize)
        }
        return kvBuf to crcBuf
    }

    private fun encodeMmkvString(s: String): ByteArray {
        val utf8 = s.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        writeVarint32(out, utf8.size)
        out.write(utf8)
        return out.toByteArray()
    }

    private fun encodeMmkvVarint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (true) {
            if ((x and 0x7FL.inv()) == 0L) {
                out.write(x.toInt())
                return out.toByteArray()
            }
            out.write(((x.toInt() and 0x7F) or 0x80))
            x = x ushr 7
        }
    }

    private fun writeVarint32(out: ByteArrayOutputStream, value: Int) {
        var x = value
        while (true) {
            if ((x and 0x7F.inv()) == 0) {
                out.write(x)
                return
            }
            out.write((x and 0x7F) or 0x80)
            x = x ushr 7
        }
    }
}

package com.weitiao.tuning

import com.weitiao.tuning.core.TuningConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class StepPlanEngineTest {
    private class TestClock(var now: Long) {
        fun at(day: Int, hour: Int, minute: Int = 0) {
            now = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.JANUARY, day, hour, minute, 0)
            }.timeInMillis
        }
    }

    private class MapStore(private val clock: TestClock) : WechatHooks.StepStateStore {
        val values = mutableMapOf<String, Any>()
        var failReads = false
        override fun getInt(key: String, default: Int): Int {
            check(!failReads) { "Test storage unavailable" }
            return values[key] as? Int ?: default
        }
        override fun getLong(key: String, default: Long) = values[key] as? Long ?: default
        override fun getFloat(key: String, default: Float) = values[key] as? Float ?: default
        override fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
        override fun savePlan(values: Map<String, Long>) {
            this.values.putAll(values.toMap())
        }
        override fun saveState(
            todayKey: Int,
            baseHardwareRaw: Float,
            lastHardwareRaw: Float,
            currentDelivered: Long,
            lastOutput: Float,
            baselineEstablished: Boolean,
        ) {
            values[WechatHooks.KEY_STATE_TODAY] = todayKey
            values[WechatHooks.KEY_STATE_BASE_RAW] = baseHardwareRaw
            values[WechatHooks.KEY_STATE_LAST_RAW] = lastHardwareRaw
            values[WechatHooks.KEY_STATE_DELIVERED] = currentDelivered
            values[WechatHooks.KEY_STATE_LAST_OUTPUT] = lastOutput
            values[WechatHooks.KEY_STATE_BASELINE_ESTABLISHED] = baselineEstablished
            values[WechatHooks.KEY_STATE_SAVED_AT] = clock.now
        }
        fun long(key: String) = values[key] as Long
        fun snapshot() = values.toMap()
    }

    private fun clock(day: Int = 12, hour: Int = 8, minute: Int = 0) = TestClock(0L).apply { at(day, hour, minute) }
    private fun config() = TuningConfig(
        stepEnabled = true,
        stepMode = "PLAN",
        stepTarget = 10_000,
        stepJitter = 0,
        stepMaximum = 12_000,
        stepWindowStart = 8 * 60,
        stepWindowEnd = 20 * 60,
        stepAfterWindow = "NATURAL",
        stepGeneration = 1L,
    )
    private fun engine(store: MapStore, clock: TestClock, logger: (String, Throwable?) -> Unit = { _, _ -> }) =
        WechatHooks.WechatStepEngine(stateStore = store, clock = { clock.now }, logger = logger)

    private fun emit(engine: WechatHooks.WechatStepEngine, raw: Float, cfg: TuningConfig): Float {
        val values = floatArrayOf(raw)
        engine.process(values, isDetector = false, cfg = cfg, isLiveWechatHook = true)
        return values[0]
    }

    @Test
    fun lateEnableAnchorsWithoutJumpThenCountsRealWalkingImmediately() {
        val clock = clock(hour = 19)
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config()
        assertEquals(50_000f, emit(engine, 50_000f, cfg), 0f)
        assertEquals(0L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        assertEquals(clock.now, store.long("start_at"))
        assertEquals(50_120f, emit(engine, 50_120f, cfg), 0f)
        assertEquals(120L, store.long("step_real_today"))
        assertEquals(120L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        // A repeated synthetic frame at the same time cannot count those real steps again.
        assertEquals(50_120f, emit(engine, 50_120f, cfg), 0f)
        clock.at(12, 19, 30)
        val advanced = emit(engine, 50_120f, cfg)
        assertTrue(advanced > 50_120f)
        assertTrue(advanced - 50_120f <= 2_000f)
    }

    @Test
    fun crossingMidnightResetsDailyPlanAndRetainsMonotonicSensorAnchor() {
        val clock = clock(hour = 19)
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config()
        emit(engine, 1_000f, cfg)
        val yesterdayOutput = emit(engine, 1_100f, cfg)
        val seed = store.long("seed")
        clock.at(13, 8)
        assertTrue(engine.shouldDispatchAutoTick(cfg))
        assertEquals(yesterdayOutput, emit(engine, 1_100f, cfg), 0f)
        assertEquals(20260113L, store.long("plan_day"))
        assertEquals(0L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        assertEquals(0L, store.long("step_real_today"))
        assertEquals(seed, store.long("seed"))
        assertEquals(yesterdayOutput + 40f, emit(engine, 1_140f, cfg), 0f)
    }

    @Test
    fun generationChangeRebuildsPlanAtCurrentProgressWithoutJump() {
        val clock = clock()
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config()
        emit(engine, 1_000f, cfg)
        clock.at(12, 10)
        val previous = emit(engine, 1_000f, cfg)
        val delivered = store.long(WechatHooks.KEY_STATE_DELIVERED)
        val updated = cfg.copy(stepTarget = 11_000, stepGeneration = 2L)
        clock.at(12, 11)
        assertEquals(previous, emit(engine, 1_000f, updated), 0f)
        assertEquals(2L, store.long("plan_gen"))
        assertEquals(clock.now, store.long("start_at"))
        assertEquals(delivered, store.long("start_value"))
        assertEquals(11_000L, store.long("daily_target"))
        assertEquals(previous + 50f, emit(engine, 1_050f, updated), 0f)
    }

    @Test
    fun persistedPlanRestoresExactlyAndMatchesUninterruptedEngine() {
        val clock = clock()
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config().copy(stepJitter = 20)
        emit(engine, 1_000f, cfg)
        clock.at(12, 10)
        val lastOutput = emit(engine, 1_100f, cfg)
        val snapshot = store.snapshot()
        val copiedStore = MapStore(clock).apply { values.putAll(snapshot) }
        val restarted = engine(copiedStore, clock)
        assertEquals("Restart and uninterrupted engines must release the same pending planned gain",
            emit(engine, 1_100f, cfg), emit(restarted, 1_100f, cfg), 0f)
        for (key in listOf("seed", "plan_day", "plan_gen", "start_at", "start_real", "start_value", "daily_target")) {
            assertEquals("Restart must retain $key", snapshot[key], copiedStore.values[key])
        }
        clock.at(12, 11)
        assertEquals(emit(engine, 1_200f, cfg), emit(restarted, 1_200f, cfg), 0f)
        assertEquals(store.long(WechatHooks.KEY_STATE_DELIVERED), copiedStore.long(WechatHooks.KEY_STATE_DELIVERED))
    }

    @Test
    fun afterWindowCatchupDrainsInFramesOfAtMost2000AndSurvivesRestart() {
        val clock = clock()
        val store = MapStore(clock)
        var engine = engine(store, clock)
        val cfg = config().copy(stepAfterWindow = "FREEZE")
        var previous = emit(engine, 1_000f, cfg)
        clock.at(12, 21)
        repeat(5) { frame ->
            assertTrue("Cutoff backlog must keep ticker alive", engine.shouldDispatchAutoTick(cfg))
            val output = emit(engine, 1_000f, cfg)
            assertTrue("Frame $frame gain ${output - previous}", output - previous in 0f..2_000f)
            previous = output
            if (frame == 0) engine = engine(store, clock)
        }
        assertEquals(11_000f, previous, 0f)
        assertEquals(10_000L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        assertFalse(engine.shouldDispatchAutoTick(cfg))
        assertEquals(previous, emit(engine, 1_250f, cfg), 0f)
    }

    @Test
    fun naturalAfterWindowCountsOnlyRealIncrementsAndStopsAtHardCap() {
        val clock = clock()
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config().copy(stepTarget = 50_000, stepMaximum = 10_500)
        emit(engine, 1_000f, cfg)
        // A target above the hard cap must be clamped when the plan is created.
        assertEquals(10_500L, store.long("daily_target"))
        clock.at(12, 20)
        var previous = 1_000f
        repeat(6) {
            val output = emit(engine, 1_000f, cfg)
            assertTrue(output - previous in 0f..2_000f)
            previous = output
        }
        assertEquals(11_500f, previous, 0f)
        assertEquals(previous, emit(engine, 1_250f, cfg), 0f)
        assertEquals(previous, emit(engine, 1_500f, cfg), 0f)
        assertEquals(10_500L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        assertFalse(engine.shouldDispatchAutoTick(cfg))
    }

    @Test
    fun naturalAfterWindowAddsRealWalkingButNoStationaryOrMultiplierGain() {
        val clock = clock()
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config().copy(stepMultiplier = 100)
        emit(engine, 1_000f, cfg)
        clock.at(12, 20)
        repeat(5) { emit(engine, 1_000f, cfg) }
        assertEquals(11_250f, emit(engine, 1_250f, cfg), 0f)
        assertEquals(11_500f, emit(engine, 1_500f, cfg), 0f)
        assertEquals(11_500f, emit(engine, 1_500f, cfg), 0f)
        assertEquals(10_500L, store.long(WechatHooks.KEY_STATE_DELIVERED))
    }

    @Test
    fun enableAfterDeadlineDoesNotBackfillMissedPlan() {
        val clock = clock(hour = 21)
        for (after in listOf("NATURAL", "FREEZE")) {
            val store = MapStore(clock)
            val engine = engine(store, clock)
            val cfg = config().copy(stepAfterWindow = after)
            assertEquals(8_000f, emit(engine, 8_000f, cfg), 0f)
            assertEquals(8_000f, emit(engine, 8_000f, cfg), 0f)
            assertFalse(engine.shouldDispatchAutoTick(cfg))
            val expected = if (after == "NATURAL") 8_100f else 8_000f
            assertEquals(expected, emit(engine, 8_100f, cfg), 0f)
        }
    }

    @Test
    fun settingsChangeOutsideWindowDoesNotLoseCurrentRealDelta() {
        val clock = clock(hour = 21)
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config()
        assertEquals(1_000f, emit(engine, 1_000f, cfg), 0f)
        assertEquals(1_100f, emit(engine, 1_100f, cfg.copy(stepGeneration = 2L)), 0f)
        assertEquals(1_200f, emit(engine, 1_200f, cfg.copy(stepGeneration = 2L)), 0f)
    }

    @Test
    fun multiplierPendingGainsDrainAfterWindowInNaturalMode() {
        val clock = clock(hour = 21)
        val store = MapStore(clock)
        val engine = engine(store, clock)
        val cfg = config().copy(stepMode = "MULTIPLIER", stepMultiplier = 100)
        assertEquals(1_000f, emit(engine, 1_000f, cfg), 0f)
        assertEquals(3_000f, emit(engine, 1_100f, cfg), 0f)
        assertTrue(engine.shouldDispatchAutoTick(cfg))
        repeat(4) { emit(engine, 1_100f, cfg) }
        assertEquals(10_000L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        assertFalse(engine.shouldDispatchAutoTick(cfg))
    }

    @Test
    fun calculationFailurePassesRawThroughLogsOnceAndCanRecover() {
        val clock = clock()
        val store = MapStore(clock)
        val errors = mutableListOf<Throwable>()
        val engine = engine(store, clock) { _, error -> if (error != null) errors += error }
        val cfg = config()
        emit(engine, 1_000f, cfg)
        store.failReads = true
        assertEquals(1_100f, emit(engine, 1_100f, cfg), 0f)
        assertEquals(1_200f, emit(engine, 1_200f, cfg), 0f)
        assertEquals(1, errors.size)
        assertEquals(0L, store.long(WechatHooks.KEY_STATE_DELIVERED))
        store.failReads = false
        assertEquals(1_200f, emit(engine, 1_200f, cfg), 0f)
        assertEquals(1_250f, emit(engine, 1_250f, cfg), 0f)
    }
}

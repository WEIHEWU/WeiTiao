package com.weitiao.tuning.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ExactStepEngineTest {
    private fun at(hour: Int, day: Int = 18): Long = LocalDate.of(2026, 11, day).atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val cfg = TuningConfig(stepEnabled = true, stepTarget = 20_000, stepJitter = 0, stepMaximum = 28_000,
        stepWindowStart = 420, stepWindowEnd = 1230)
    @Test fun firstReadDoesNotJumpAndUploadEchoDoesNotCountAsReal() {
        var state = emptyMap<String, Long>(); var now = at(7)
        val engine = ExactStepEngine({state}, {state=it}, {now}, {42})
        assertEquals(1000L, engine.evaluate(1000, cfg, true))
        now = at(12)
        val output = engine.evaluate(1200, cfg, true)
        assertTrue(output > 1200)
        assertEquals(output, engine.evaluate(output, cfg, false))
        assertEquals(1200L, state["exact_real"])
        assertEquals(output, engine.evaluate(1200, cfg, true))
    }
    @Test fun lateEnableNaturalGrowthFreezeAndHardCap() {
        var state = emptyMap<String, Long>(); val now = at(21)
        val engine = ExactStepEngine({state}, {state=it}, {now}, {42})
        assertEquals(3000L, engine.evaluate(3000, cfg, true))
        assertEquals(3500L, engine.evaluate(3500, cfg, true))
        assertEquals(3500L, engine.evaluate(4000, cfg.copy(stepAfterWindow="FREEZE"), true))
        assertTrue(engine.evaluate(40_000, cfg, true) <= 28_000)
    }
    @Test fun restartContinuesAndRebootRawDropDoesNotReversePeak() {
        var state = emptyMap<String, Long>(); var now = at(7)
        fun engine() = ExactStepEngine({state}, {state=it}, {now}, {42})
        val e = engine(); e.evaluate(1000,cfg,true); now=at(12)
        val peak=e.evaluate(1100,cfg,true)
        assertEquals(peak, engine().evaluate(1100,cfg,true))
        assertTrue(engine().evaluate(10,cfg,true)>=peak)
    }
    @Test fun crossBoundaryDoesNotDoubleCountUnknownWalkAndFollowingWalkIsNatural() {
        var state = emptyMap<String, Long>(); var now=at(7)
        val e=ExactStepEngine({state},{state=it},{now},{42})
        e.evaluate(1000,cfg,true); now=at(21)
        assertEquals(20_000L,e.evaluate(1500,cfg,true))
        assertEquals(20_100L,e.evaluate(1600,cfg,true))
    }
    @Test fun savingFailureDoesNotAdvanceCommittedState() {
        var state=emptyMap<String,Long>(); var fail=false; val now=at(21)
        val e=ExactStepEngine({state},{if(fail) error("disk"); state=it},{now},{42})
        e.evaluate(1000,cfg,true); val before=state; fail=true
        try {e.evaluate(1100,cfg,true); fail("expected exception")}catch(_:IllegalStateException){}
        assertEquals(before,state)
    }
    @Test fun switchingFromSensorRetainsDeliveredFloorAndNextDayResetsDailyCount() {
        var now=at(21); val date=LocalDate.of(2026,11,18)
        var state=mapOf("plan_day" to 20261118L, "step_current_delivered" to 12000L)
        val e=ExactStepEngine({state},{state=it},{now},{42})
        assertEquals(12000L,e.evaluate(1000,cfg,true))
        assertEquals(12100L,e.evaluate(1100,cfg,true))
        now=at(21,19)
        assertEquals(200L,e.evaluate(200,cfg,true))
    }
    @Test fun settingsChangePreservesNaturalDeltaAndLoweredCapAddsNothing() {
        var state=emptyMap<String,Long>(); val now=at(21)
        val e=ExactStepEngine({state},{state=it},{now},{42})
        e.evaluate(1000,cfg,true)
        assertEquals(1100L,e.evaluate(1100,cfg.copy(stepGeneration=2),true))
        assertEquals(1100L,e.evaluate(1200,cfg.copy(stepMaximum=500),true))
    }
    @Test fun invalidReadAndUploadSentinelsPassThrough() {
        val e=ExactStepEngine({emptyMap()},{fail("unexpected write")},{at(7)},{42})
        assertEquals(-2L,e.evaluate(-2,cfg,true))
        assertEquals(Long.MIN_VALUE,e.evaluate(Long.MIN_VALUE,cfg,true))
        assertEquals(0L,e.evaluate(0,cfg,false))
        assertEquals(-1L,e.evaluate(-1,cfg,false))
        assertEquals(-100L,e.evaluate(-100,cfg,false))
        assertEquals(100L,e.evaluate(100,cfg.copy(stepEnabled=false),true))
    }

    @Test fun minusOneReadNormalizesToZeroAndAdvancesStationaryRepeats() {
        var state = emptyMap<String, Long>()
        var now = at(7)
        val engine = ExactStepEngine({ state }, { state = it }, { now }, { 42 })
        // First read returns -1L (no today SportKv record): anchors at 0L without jumping.
        assertEquals(0L, engine.evaluate(-1L, cfg, true))
        assertEquals(0L, state["exact_real"])
        assertEquals(0L, state["exact_peak"])
        // Stationary repeated reads with -1L advance smoothly along daytime plan.
        now = at(12)
        val midday = engine.evaluate(-1L, cfg, true)
        assertTrue("Expected daytime progression from 0L, got $midday", midday > 0L)
        assertEquals(0L, state["exact_real"])
        // Repeated read at exact same timestamp does not double-add.
        assertEquals(midday, engine.evaluate(-1L, cfg, true))
        // When real walking reading arrives later, real steps are tracked and transient -1L never rewinds exact_real.
        now = at(13)
        val walked = engine.evaluate(300L, cfg, true)
        assertTrue(walked > midday)
        assertEquals(300L, state["exact_real"])
        assertEquals(walked, engine.evaluate(-1L, cfg, true))
        assertEquals(300L, state["exact_real"])
    }

    @Test fun crossDayWithMinusOneResetsPlanWithoutYesterdayBleed() {
        var state = emptyMap<String, Long>()
        var now = at(7, 18)
        val engine = ExactStepEngine({ state }, { state = it }, { now }, { 42 })
        assertEquals(1000L, engine.evaluate(1000L, cfg, true))
        now = at(21, 18)
        assertEquals(20_000L, engine.evaluate(1500L, cfg, true))
        // Next day: SportKv has no today record yet so d.a() returns -1L.
        now = at(8, 19)
        assertEquals(0L, engine.evaluate(-1L, cfg, true))
        assertEquals(20261119L, state["exact_day"])
        assertEquals(0L, state["exact_real"])
        assertEquals(0L, state["exact_peak"])
        now = at(12, 19)
        val nextMidday = engine.evaluate(-1L, cfg, true)
        assertTrue(nextMidday in 1L..20_000L)
    }

    @Test fun eveningFirstEnableWithMinusOneDoesNotBackfill() {
        for (after in listOf(TuningConfig.AFTER_NATURAL, TuningConfig.AFTER_FREEZE)) {
            var state = emptyMap<String, Long>()
            var now = at(21)
            val engine = ExactStepEngine({ state }, { state = it }, { now }, { 42 })
            val eveningCfg = cfg.copy(stepAfterWindow = after)
            assertEquals(0L, engine.evaluate(-1L, eveningCfg, true))
            now = at(21) + 30L * 60_000L
            assertEquals(0L, engine.evaluate(-1L, eveningCfg, true))
            now = at(21) + 45L * 60_000L
            val expectedAfterWalk = if (after == TuningConfig.AFTER_NATURAL) 150L else 0L
            assertEquals(expectedAfterWalk, engine.evaluate(150L, eveningCfg, true))
        }
    }
}

package com.weitiao.tuning

import com.weitiao.tuning.core.StepPlanPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StepPlanPolicyTest {
    @Test
    fun dailyTargetIsStableForSameInstallationAndDay() {
        val expected = StepPlanPolicy.dailyTarget(20_000, 20, 918273L, 20_000L, 28_000)
        repeat(100) {
            assertEquals(expected, StepPlanPolicy.dailyTarget(20_000, 20, 918273L, 20_000L, 28_000))
        }
    }

    @Test
    fun dailyTargetsVaryAcrossDaysWithinConfiguredJitter() {
        val targets = (20_000L until 20_365L).map {
            StepPlanPolicy.dailyTarget(20_000, 20, 918273L, it, 28_000)
        }
        assertTrue(targets.all { it in 16_000..24_000 })
        assertTrue("Daily planning should produce a distribution, not one fixed target", targets.toSet().size > 100)
        assertTrue(targets.any { it < 20_000 })
        assertTrue(targets.any { it > 20_000 })
    }

    @Test
    fun zeroJitterUsesCenterAndAlwaysHonorsHardCap() {
        assertEquals(20_000, StepPlanPolicy.dailyTarget(20_000, 0, 1L, 1L, 28_000))
        assertEquals(12_000, StepPlanPolicy.dailyTarget(20_000, 0, 1L, 1L, 12_000))
        for (day in 1L..100L) {
            assertTrue(StepPlanPolicy.dailyTarget(20_000, 50, 3L, day, 15_000) in 1..15_000)
        }
    }

    @Test
    fun targetClampsInvalidAndExtremeInputsWithoutOverflow() {
        assertEquals(1, StepPlanPolicy.dailyTarget(Int.MIN_VALUE, -1, Long.MIN_VALUE, Long.MAX_VALUE, 0))
        assertEquals(1_000_000, StepPlanPolicy.dailyTarget(Int.MAX_VALUE, 0, Long.MAX_VALUE, 1L, Int.MAX_VALUE))
        val high = StepPlanPolicy.dailyTarget(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(high in 500_000..1_500_000)
        assertEquals(1, StepPlanPolicy.dailyTarget(20_000, 50, 1L, 1L, Int.MIN_VALUE))
    }

    @Test
    fun plannedCurveClampsBeforeStartAndReachesTargetAtEnd() {
        fun value(now: Long) = StepPlanPolicy.planned(0L, now, 1_000L, 5_000L, 0L, 1_000L, 1_000L, 9_000)
        assertEquals(1_000L, value(0L))
        assertEquals(1_000L, value(1_000L))
        assertEquals(3_000L, value(2_000L))
        assertEquals(5_000L, value(3_000L))
        assertEquals(7_000L, value(4_000L))
        assertEquals(9_000L, value(5_000L))
        assertEquals(9_000L, value(9_000L))
    }

    @Test
    fun realWalkingAdvancesCurveEarlyWithoutAddingAgainAtDeadline() {
        val stationary = StepPlanPolicy.planned(0L, 2_000L, 1_000L, 5_000L, 0L, 1_000L, 1_000L, 9_000)
        val walking = StepPlanPolicy.planned(2_000L, 2_000L, 1_000L, 5_000L, 0L, 1_000L, 1_000L, 9_000)
        assertEquals(3_000L, stationary)
        assertEquals(4_500L, walking)
        assertTrue(walking > stationary)
        assertEquals(9_000L, StepPlanPolicy.planned(2_000L, 5_000L, 1_000L, 5_000L, 0L, 1_000L, walking, 9_000))
    }

    @Test
    fun plannedCurvePreservesRealStepsAndPreviouslyDeliveredPeak() {
        assertEquals(12_000L, StepPlanPolicy.planned(12_000L, 3_000L, 1_000L, 5_000L, 0L, 1_000L, 8_000L, 9_000))
        assertEquals(8_000L, StepPlanPolicy.planned(0L, 2_000L, 1_000L, 5_000L, 0L, 1_000L, 8_000L, 9_000))
        assertEquals(2_000L, StepPlanPolicy.planned(0L, 3_000L, 1_000L, 5_000L, 0L, 2_000L, 2_000L, 1_000))
        assertEquals(2_000L, StepPlanPolicy.planned(0L, 3_000L, 5_000L, 1_000L, 0L, 2_000L, 2_000L, 9_000))
    }

    @Test
    fun naturalGrowthUsesOnlyNewRealStepsAndClampsToHardCap() {
        assertEquals(5_100L, StepPlanPolicy.natural(300L, 200L, 5_000L, 1, 10_000L))
        assertEquals(5_000L, StepPlanPolicy.natural(100L, 200L, 5_000L, 1, 10_000L))
        assertEquals(10_000L, StepPlanPolicy.natural(Long.MAX_VALUE, 0L, 5_000L, Int.MAX_VALUE, 10_000L))
        assertEquals(10_000L, StepPlanPolicy.natural(12_000L, 12_000L, 5_000L, 1, 10_000L))
        assertEquals(1L, StepPlanPolicy.natural(100L, 0L, 0L, 1, 0L))
    }
}

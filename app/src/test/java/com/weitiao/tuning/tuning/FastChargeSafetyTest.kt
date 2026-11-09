package com.weitiao.tuning.tuning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastChargeSafetyTest {

    @Test
    fun normalizeTempRejectsNegativeErrorValuesLikeMinus88Point8() {
        // SSRM / sysfs error value -888 (-88.8°C) and any negative value must NEVER become positive via abs()
        assertEquals(0, FastChargeController.normalizeTemp(-888))
        assertEquals(0, FastChargeController.normalizeTemp(-88800))
        assertEquals(0, FastChargeController.normalizeTemp(-300))
        assertEquals(0, FastChargeController.normalizeTemp(0))
        assertEquals(0, FastChargeController.normalizeTemp(null))
    }

    @Test
    fun normalizeTempHandlesTenthsHundredthsAndMillidegrees() {
        assertEquals(440, FastChargeController.normalizeTemp(440))
        assertEquals(440, FastChargeController.normalizeTemp(4400))
        assertEquals(440, FastChargeController.normalizeTemp(44000))
        assertEquals(567, FastChargeController.normalizeTemp(567))
        assertEquals(567, FastChargeController.normalizeTemp(56700))
    }

    @Test
    fun isOverheatedTripsOnBatteryOrChipAndHoldsUntilCooledDown() {
        // Case 1: Battery is only 38.0°C, but Charger IC / AP chip hits 50.0°C -> MUST trip immediately!
        val hotChipMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 380,
            maxChipTempTenths = 500,
        )
        assertTrue(
            "Chip at 50.0°C must trigger thermal cutoff even when battery is 38.0°C",
            FastChargeController.isOverheated(hotChipMetrics, cutoffCelsius = 44, previouslyOverheated = false),
        )

        // Case 2: Once overheated, if sensor returns 0 (e.g. -88.8°C error), must STAY overheated (fail-safe)
        val unreadableMetrics = FastChargeController.BatteryMetrics(
            currentMa = 3000,
            voltageMv = 4200,
            powerWatts = 12.6,
            temperatureTenths = 0,
            maxChipTempTenths = 0,
        )
        assertTrue(
            "Unreadable temperature (0) during overheat must keep thermal cutoff active",
            FastChargeController.isOverheated(unreadableMetrics, cutoffCelsius = 44, previouslyOverheated = true),
        )

        // Case 3: Battery at 44.0°C trips 44°C cutoff, stays overheated at 42.0°C, cools down at 40.5°C
        val hotBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 440, maxChipTempTenths = 450)
        assertTrue(FastChargeController.isOverheated(hotBatt, cutoffCelsius = 44, previouslyOverheated = false))

        val warmBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 420, maxChipTempTenths = 450)
        assertTrue(FastChargeController.isOverheated(warmBatt, cutoffCelsius = 44, previouslyOverheated = true))

        val cooledBatt = FastChargeController.BatteryMetrics(3000, 4200, 12.6, temperatureTenths = 405, maxChipTempTenths = 450)
        assertFalse(FastChargeController.isOverheated(cooledBatt, cutoffCelsius = 44, previouslyOverheated = true))
    }
}

package com.weitiao.tuning.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketUnlockAndPresetTest {

    @Test
    fun testMarketUnlockNumericAndOperatorResolution() {
        val config = CscConfig(
            enabled = true,
            salesCode = "TMB",
            countryCode = "US",
            simMcc = "310",
            simMnc = "260",
            simNumeric = "310260",
            simOperatorName = "T-Mobile",
            simCountryIso = "us",
            spoofSimInfo = true,
        )

        assertEquals("310260", CscSim.resolveNumeric(config))
        assertEquals("T-Mobile", CscSim.resolveOperatorName(config))
        assertEquals("us", CscSim.resolveCountryIso(config))
        assertEquals("310", CscSim.resolveMcc(config))
        assertEquals("260", CscSim.resolveMnc(config))
    }

    @Test
    fun testOmcAndGsmPropertiesHookMatching() {
        val config = CscConfig(
            enabled = true,
            salesCode = "XAA",
            countryCode = "US",
            simMcc = "310",
            simMnc = "260",
            simNumeric = "310260",
            simOperatorName = "T-Mobile",
            simCountryIso = "us",
            spoofSimInfo = true,
            spoofSalesCode = true,
            spoofCountryCode = true,
        )

        // Samsung OMC framework
        assertEquals("XAA", CscSpoof.matchProp(CscConstants.PROP_SALES_CODE, config))
        assertEquals("XAA", CscSpoof.matchProp(CscConstants.PROP_PERSIST_OMC_SALES_CODE, config))
        assertEquals("XAA", CscSpoof.matchProp(CscConstants.PROP_RO_OMC_SALES_CODE, config))
        assertEquals("US", CscSpoof.matchProp(CscConstants.PROP_COUNTRY_CODE, config))
        assertEquals("US", CscSpoof.matchProp(CscConstants.PROP_PERSIST_OMC_COUNTRY_CODE, config))

        // GSM properties
        assertEquals("310260", CscSpoof.matchProp(CscConstants.PROP_GSM_SIM_NUMERIC, config))
        assertEquals("310260", CscSpoof.matchProp(CscConstants.PROP_GSM_OPERATOR_NUMERIC, config))
        assertEquals("us", CscSpoof.matchProp(CscConstants.PROP_GSM_SIM_ISO, config))
        assertEquals("us", CscSpoof.matchProp(CscConstants.PROP_GSM_OPERATOR_ISO, config))
        assertEquals("T-Mobile", CscSpoof.matchProp(CscConstants.PROP_GSM_SIM_ALPHA, config))
        assertEquals("T-Mobile", CscSpoof.matchProp(CscConstants.PROP_GSM_OPERATOR_ALPHA, config))
    }

    @Test
    fun testCuratedHongKongPresetConsistency() {
        val hkConfig = CscConfig(
            enabled = true,
            salesCode = "TGY",
            countryCode = "HK",
            simMcc = "454",
            simMnc = "06",
            simNumeric = "45406",
            simOperatorName = "SmarTone",
            simCountryIso = "hk",
            spoofSimInfo = true,
            spoofSalesCode = true,
            spoofCountryCode = true,
        )

        assertEquals("45406", CscSim.resolveNumeric(hkConfig))
        assertEquals("SmarTone", CscSim.resolveOperatorName(hkConfig))
        assertEquals("hk", CscSim.resolveCountryIso(hkConfig))
        assertEquals("454", CscSim.resolveMcc(hkConfig))
        assertEquals("06", CscSim.resolveMnc(hkConfig))
        assertEquals("TGY", CscSpoof.matchProp("persist.omc.sales_code", hkConfig))
        assertEquals("45406", CscSpoof.matchProp("gsm.sim.operator.numeric", hkConfig))
    }
}

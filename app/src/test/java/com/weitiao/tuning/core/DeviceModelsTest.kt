package com.weitiao.tuning.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceModelsTest {

    @Test
    fun catalogHasUniqueCompleteProfiles() {
        assertTrue(DeviceModels.ALL.size >= 100)
        assertEquals(DeviceModels.ALL.size, DeviceModels.ALL.map { it.name }.distinct().size)
        DeviceModels.ALL.forEach { profile ->
            assertTrue(profile.name.isNotBlank())
            assertEquals(profile.name.trim(), profile.name)
            assertTrue(!profile.name.contains("_"))
            assertTrue(profile.model.isNotBlank())
            assertTrue(profile.device.isNotBlank())
            assertTrue(profile.productName.isNotBlank())
            assertTrue(profile.brand.isNotBlank())
            assertTrue(profile.manufacturer.isNotBlank())
        }
    }

    @Test
    fun huaweiAndPixelProfilesAreAccurate() {
        val pura70Pro = requireNotNull(DeviceModels.findByName("HUAWEI Pura 70 Pro"))
        assertEquals("HNE-AL00", pura70Pro.model)
        val pixel9Pro = requireNotNull(DeviceModels.findByName("Pixel 9 Pro"))
        assertEquals("caiman", pixel9Pro.device)
    }

    @Test
    fun importedProfileUsesItsOwnBrandAndManufacturer() {
        val profile = requireNotNull(DeviceModels.findByName("OnePlus 13"))
        val config = CscConfig(spoofDevice = true, deviceName = profile.name)

        assertEquals(profile.brand, CscSpoof.matchDeviceProp("ro.product.brand", config))
        assertEquals(profile.manufacturer, CscSpoof.matchDeviceProp("ro.product.manufacturer", config))
        assertEquals(profile.model, CscSpoof.matchDeviceProp("ro.product.model", config))
    }

    @Test
    fun customOrDynamicDeviceResolvesProperly() {
        val preset = requireNotNull(DeviceModels.findByName("Galaxy Z Fold8 Ultra"))
        assertEquals("samsung", preset.brand)
        assertEquals("Galaxy Z Fold8 Ultra", preset.name)
        assertEquals("SM-F9780", preset.model)

        val customProfile = requireNotNull(DeviceModels.findByName("Custom Phone X999"))
        assertEquals("samsung", customProfile.brand)
        assertEquals("Custom Phone X999", customProfile.name)
        assertEquals("Custom Phone X999", customProfile.model)

        val config = CscConfig(enabled = true, spoofDevice = true, deviceName = "Galaxy Z Fold8 Ultra")
        assertEquals("SM-F9780", CscSpoof.matchDeviceProp("ro.product.model", config))
        assertEquals("samsung", CscSpoof.matchDeviceProp("ro.product.brand", config))
    }

    @Test
    fun zFold8UltraFuzzyMatchesProperly() {
        val byExact = requireNotNull(DeviceModels.findByName("Galaxy Z Fold8 Ultra"))
        val byWithoutGalaxy = requireNotNull(DeviceModels.findByName("Z Fold8 Ultra"))
        val byNoSpace = requireNotNull(DeviceModels.findByName("ZFold8Ultra"))
        val byModel = requireNotNull(DeviceModels.findByName("SM-F9780"))

        assertEquals("SM-F9780", byExact.model)
        assertEquals("SM-F9780", byWithoutGalaxy.model)
        assertEquals("SM-F9780", byNoSpace.model)
        assertEquals("SM-F9780", byModel.model)

        val config = CscConfig(enabled = true, spoofDevice = true, deviceName = "Z Fold8 Ultra")
        assertEquals("SM-F9780", CscSpoof.matchDeviceProp("ro.product.model", config))
        assertEquals("q8x", CscSpoof.matchDeviceProp("ro.product.device", config))
        assertEquals("q8xzc", CscSpoof.matchDeviceProp("ro.product.name", config))
        assertEquals("samsung", CscSpoof.matchDeviceProp("ro.product.brand", config))
        assertEquals("fold,nosdcard", CscSpoof.matchDeviceProp("ro.build.characteristics", config))
        assertEquals("TRUE", CscSpoof.matchFloatingFeature("SEC_FLOATING_FEATURE_FRAMEWORK_SUPPORT_FOLDABLE_TYPE_FOLD", config))
    }

    @Test
    fun zFold8AndZFold8UltraAreDistinct() {
        val fold8 = requireNotNull(DeviceModels.findByName("Z Fold8"))
        val fold8Ultra = requireNotNull(DeviceModels.findByName("Z Fold8 Ultra"))

        assertEquals("Galaxy Z Fold8", fold8.name)
        assertEquals("SM-F9760", fold8.model)
        assertEquals("q8", fold8.device)

        assertEquals("Galaxy Z Fold8 Ultra", fold8Ultra.name)
        assertEquals("SM-F9780", fold8Ultra.model)
        assertEquals("q8x", fold8Ultra.device)
    }

    @Test
    fun legacySamsungProfileKeepsSamsungIdentity() {
        val profile = DeviceModels.findByName("Galaxy S24 Ultra")
        assertNotNull(profile)
        assertEquals("samsung", profile!!.brand)
        assertEquals("samsung", profile.manufacturer)
        assertEquals("e3q", profile.device)
        assertEquals("SM-S9280", profile.model)
    }

    @Test
    fun samsungS25ProfileHasCorrectModelAndDevice() {
        val s25 = requireNotNull(DeviceModels.findByName("Galaxy S25"))
        assertEquals("SM-S9310", s25.model)
        assertEquals("e1", s25.device)

        val s25u = requireNotNull(DeviceModels.findByName("Galaxy S25 Ultra"))
        assertEquals("SM-S9380", s25u.model)
        assertEquals("e3", s25u.device)
    }

    @Test
    fun cscSimResolveMccFallsBackToSalesCode() {
        val configTgy = CscConfig(salesCode = "TGY")
        assertEquals("454", CscSim.resolveMcc(configTgy))

        val configBri = CscConfig(salesCode = "BRI")
        assertEquals("466", CscSim.resolveMcc(configBri))

        val configKoo = CscConfig(salesCode = "KOO")
        assertEquals("450", CscSim.resolveMcc(configKoo))

        val configXaa = CscConfig(salesCode = "XAA")
        assertEquals("310", CscSim.resolveMcc(configXaa))

        val configCustomMcc = CscConfig(salesCode = "TGY", simMcc = "999")
        assertEquals("999", CscSim.resolveMcc(configCustomMcc))

        val configEmpty = CscConfig(salesCode = "")
        assertTrue(CscSim.resolveMcc(configEmpty).isNotBlank())
    }

    @Test
    fun cscSimResolveCountryIso() {
        val configTgy = CscConfig(salesCode = "TGY")
        assertEquals("hk", CscSim.resolveCountryIso(configTgy))

        val configXaa = CscConfig(salesCode = "XAA")
        assertEquals("us", CscSim.resolveCountryIso(configXaa))

        val configCustomIso = CscConfig(salesCode = "TGY", simCountryIso = "tw")
        assertEquals("tw", CscSim.resolveCountryIso(configCustomIso))
    }

    @Test
    fun nativeDeviceDetectorWorks() {
        val info = NativeDevice.detect()
        assertTrue(info.salesCode.isNotBlank())
        assertTrue(info.countryCode.isNotBlank())
        assertTrue(info.simMcc.isNotBlank())
    }

    @Test
    fun cscConstantsCountryCodesDeduplicated() {
        assertEquals("HK", CscConstants.countryCodeOf("TGY"))
        assertEquals("TW", CscConstants.countryCodeOf("BRI"))
        assertEquals("CN", CscConstants.countryCodeOf("CHC"))
        assertEquals("KR", CscConstants.countryCodeOf("KOO"))
        assertEquals("US", CscConstants.countryCodeOf("XAA"))
    }

    @Test
    fun cscSpoofMatchesSalesCodeAndCountryCode() {
        val config = CscConfig(enabled = true, salesCode = "TGY", countryCode = "HK")
        assertEquals("TGY", CscSpoof.matchProp("ro.csc.sales_code", config))
        assertEquals("HK", CscSpoof.matchProp("ro.csc.country_code", config))
        assertEquals("TGY", CscSpoof.matchRilSalesCode(config))

        val disabledConfig = CscConfig(enabled = false, salesCode = "TGY")
        assertNull(CscSpoof.matchProp("ro.csc.sales_code", disabledConfig))
    }

    @Test
    fun tuningConfigDefaults() {
        val config = TuningConfig()
        assertEquals(false, config.stepEnabled)
        assertEquals(false, config.antiRecall)
        assertEquals(false, config.brightScreenFastCharge)
        assertEquals(false, config.passThrough)
        assertEquals(20, config.passThroughLimit)
        assertEquals(80_000, config.stepMaximum)
    }

    @Test
    fun deviceModelsLookupFastAndAccurate() {
        val s25u = requireNotNull(DeviceModels.findByName("Galaxy S25 Ultra"))
        assertEquals("SM-S9380", s25u.model)

        // 通过纯小写无空格或型号查找
        val byModel = requireNotNull(DeviceModels.findByName("sm-s9380"))
        assertEquals("Galaxy S25 Ultra", byModel.name)

        val byDisplayName = requireNotNull(DeviceModels.findByName("Galaxy S25 Ultra (SM-S9380)"))
        assertEquals("Galaxy S25 Ultra", byDisplayName.name)
    }

    @Test
    fun cscCatalogSearchKeyMatchesFields() {
        val entry = com.weitiao.tuning.data.CscEntry(
            code = "TGY",
            region = "中国香港",
            countryCode = "HK",
            mcc = "454",
            country = "中国香港",
            operator = "CSL / SmarTone",
            numeric = "45400",
        )
        val list = listOf(entry)
        assertEquals(1, com.weitiao.tuning.data.CscCatalog.search(list, "TGY").size)
        assertEquals(1, com.weitiao.tuning.data.CscCatalog.search(list, "香港").size)
        assertEquals(1, com.weitiao.tuning.data.CscCatalog.search(list, "smartone").size)
        assertEquals(1, com.weitiao.tuning.data.CscCatalog.search(list, "45400").size)
        assertEquals(0, com.weitiao.tuning.data.CscCatalog.search(list, "nonexistent").size)
    }
}

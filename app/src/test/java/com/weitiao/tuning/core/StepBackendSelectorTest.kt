package com.weitiao.tuning.core

import com.tencent.mm.plugin.sport.model.d
import org.junit.Assert.*
import org.junit.Test

class StepBackendSelectorTest {
    private val version = WechatVersion("8.0.79", 3183)
    private val resolver = ExactWechatResolver()
    private val loader = javaClass.classLoader!!

    @Test fun sensorIsDefault() {
        val result = StepBackendSelector.select()
        assertEquals(StepBackend.SENSOR, result.backend)
        assertFalse(result.installExact)
        assertNull(result.exact)
    }

    @Test fun autoAndExactFallBackWithReasonsForMissingUnverifiedAndUnavailableAdaptations() {
        val unverified = resolver.probe(loader, version)
        val unavailable = resolver.probe(loader, version.copy(versionCode = 1))
        listOf(StepBackend.AUTO, StepBackend.EXACT).forEach { requested ->
            listOf(null, unverified, unavailable).forEach { resolution ->
                val selected = StepBackendSelector.select(requested, resolution)
                assertEquals(requested, selected.requested)
                assertEquals(StepBackend.SENSOR, selected.backend)
                assertFalse(selected.installExact)
                assertNull(selected.exact)
                assertTrue(selected.reason.contains("fell back to SENSOR"))
                if (resolution != null) assertTrue(selected.reason.contains(resolution.reason))
            }
        }
    }

    @Test fun verifiedDescriptorEnablesOnlyExplicitAutoOrExactRequests() {
        val digest = "c".repeat(64)
        val descriptor = VerifiedWechatDescriptor("fixture", version, digest, d::class.java.name,
            ExactMethodDescriptor("a", false), ExactMethodDescriptor("d", false), "JVM fixture semantic review")
        val verified = resolver.probe(loader, version, descriptor, digest)
        assertTrue(verified.installAllowed)
        listOf(StepBackend.AUTO, StepBackend.EXACT).forEach {
            val selected = StepBackendSelector.select(it, verified)
            assertEquals(StepBackend.EXACT, selected.backend)
            assertTrue(selected.installExact)
            assertSame(verified, selected.exact)
        }
        assertFalse(StepBackendSelector.select(resolution = verified).installExact)
        assertEquals(StepBackend.SENSOR, StepBackendSelector.select(resolution = verified).backend)
    }
}

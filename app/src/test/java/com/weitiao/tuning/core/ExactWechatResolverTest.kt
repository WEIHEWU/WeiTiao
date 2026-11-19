package com.tencent.mm.plugin.sport.model

import com.weitiao.tuning.core.*
import org.junit.Assert.*
import org.junit.Test

/** Fixtures deliberately use the reported target namespace; no WeChat APK is needed. */
class d {
    fun a(): Long = 1L
    fun d(value: Long): Boolean = value >= 0
}
class WrongReadReturn { fun a(): Int = 1; fun d(value: Long): Boolean = true }
class WrongUploadReturn { fun a(): Long = 1; fun d(value: Long): Long = value }
class WrongReadParameters { fun a(value: Long): Long = value; fun d(value: Long): Boolean = true }
class WrongUploadParameters { fun a(): Long = 1; fun d(value: Int): Boolean = true }
class BoxedRead { fun a(): Long? = 1; fun d(value: Long): Boolean = true }
class BoxedUpload { fun a(): Long = 1; fun d(value: Long?): Boolean? = true }
class OverloadedRead { fun a(): Long = 1; fun a(value: Long): Long = value; fun d(value: Long): Boolean = true }
class OverloadedUpload { fun a(): Long = 1; fun d(value: Long): Boolean = true; fun d(value: Int): Boolean = true }
class StaticMethods {
    companion object {
        @JvmStatic fun a(): Long = 1
        @JvmStatic fun d(value: Long): Boolean = true
    }
}

class ExactWechatResolverTest {
    private val loader = javaClass.classLoader!!
    private val version = WechatVersion("8.0.79", 3183)
    private val digest = "a".repeat(64)
    private val resolver = ExactWechatResolver()

    private fun descriptor(type: Class<*> = d::class.java, static: Boolean = false) = VerifiedWechatDescriptor(
        adaptationId = "jvm-fixture-review", version = version, apkSha256 = digest,
        className = type.name, read = ExactMethodDescriptor("a", static),
        upload = ExactMethodDescriptor("d", static), semanticReviewEvidence = "JVM fixture only; not a production adaptation",
    )

    @Test fun reportedVersionAndSignaturesAreOnlyAnUnverifiedCandidate() {
        val result = resolver.probe(loader, version)
        assertEquals(ExactWechatStatus.UNVERIFIED, result.status)
        assertNotNull(result.candidate)
        assertFalse(result.installAllowed)
        assertTrue(result.reason.contains("signatures do not verify"))
    }

    @Test fun reviewedApkBoundDescriptorAllowsExact() {
        val result = resolver.probe(loader, version, descriptor(), digest.uppercase())
        assertEquals(ExactWechatStatus.VERIFIED, result.status)
        assertTrue(result.installAllowed)
        assertEquals(d::class.java, result.candidate!!.read.declaringClass)
    }

    @Test fun wrongReturnTypesParametersAndBoxedTypesAreRejected() {
        listOf(WrongReadReturn::class.java, WrongUploadReturn::class.java, WrongReadParameters::class.java,
            WrongUploadParameters::class.java, BoxedRead::class.java, BoxedUpload::class.java).forEach {
            val result = resolver.probe(loader, version, descriptor(it), digest)
            assertEquals(it.name, ExactWechatStatus.UNAVAILABLE, result.status)
            assertFalse(result.installAllowed)
        }
    }

    @Test fun anyReadOrUploadOverloadIsAmbiguous() {
        listOf(OverloadedRead::class.java, OverloadedUpload::class.java).forEach {
            val result = resolver.probe(loader, version, descriptor(it), digest)
            assertFalse(result.installAllowed)
            assertTrue(result.reason.contains("ambiguous"))
        }
    }

    @Test fun staticnessMustMatchBothMethods() {
        assertFalse(resolver.probe(loader, version, descriptor(static = true), digest).installAllowed)
        assertFalse(resolver.probe(loader, version, descriptor(StaticMethods::class.java), digest).installAllowed)
        assertTrue(resolver.probe(loader, version, descriptor(StaticMethods::class.java, true), digest).installAllowed)
        assertFalse(resolver.probe(loader, version,
            descriptor().copy(upload = ExactMethodDescriptor("d", true)), digest).installAllowed)
    }

    @Test fun rejectsIncompleteOrMismatchedVerification() {
        val base = descriptor()
        listOf(base.copy(adaptationId = ""), base.copy(semanticReviewEvidence = " "),
            base.copy(version = version.copy(versionCode = 3184)),
            base.copy(version = version.copy(versionName = "8.0.80")),
            base.copy(version = version.copy(packageName = "other.package")),
            base.copy(apkSha256 = "invalid"), base.copy(apkSha256 = "b".repeat(64)),
            base.copy(className = "java.lang.String"), base.copy(read = ExactMethodDescriptor("", false)),
            base.copy(upload = ExactMethodDescriptor("a", false))).forEach {
            assertFalse(resolver.probe(loader, version, it, digest).installAllowed)
        }
        assertFalse(resolver.probe(loader, version, base).installAllowed)
        assertFalse(resolver.probe(loader, version, base, "invalid").installAllowed)
    }

    @Test fun unsupportedVersionsAndMissingClassesGiveExplicitReasons() {
        val unsupported = resolver.probe(loader, version.copy(versionCode = 3184))
        assertFalse(unsupported.installAllowed)
        assertTrue(unsupported.reason.contains("not implemented"))
        val missing = resolver.probe(loader, version, descriptor().copy(className = "com.tencent.mm.Missing"), digest)
        assertTrue(missing.reason.contains("class unavailable"))
        assertFalse(resolver.probe(loader, version.copy(packageName = "other")).installAllowed)
    }

    @Test fun futureDiscoveryHintsNeverGrantVerification() {
        val hints = ExactWechatResolver(object : ExactWechatCandidateSource {
            override fun lookup(version: WechatVersion) = ExactWechatCandidateHint(d::class.java.name, "cache hint")
        })
        val result = hints.probe(loader, version.copy(versionCode = 4000))
        assertEquals(ExactWechatStatus.UNVERIFIED, result.status)
        assertFalse(result.installAllowed)
    }
}

package com.weitiao.tuning.core

import com.tencent.mm.plugin.sport.model.WrongReadReturn
import com.tencent.mm.plugin.sport.model.d
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ApkDigestCacheTest {
    private class MemoryDigestStore : ApkDigestStore {
        var storedIdentity: String? = null
        var storedDigest: String? = null
        var throwOnRead = false
        override fun getIdentity(): String? {
            check(!throwOnRead) { "corrupted prefs" }
            return storedIdentity
        }
        override fun getDigest(): String? {
            check(!throwOnRead) { "corrupted prefs" }
            return storedDigest
        }
        override fun put(identity: String, digest: String) {
            storedIdentity = identity
            storedDigest = digest
        }
    }

    private fun sampleIdentity() = ApkDigestIdentity(
        packageName = "com.tencent.mm",
        versionName = "8.0.80",
        versionCode = 3200L,
        apkPath = "/data/app/~~abc==/com.tencent.mm-xyz==/base.apk",
        lastUpdateTime = 1_760_000_000_000L,
        fileLength = 256_000_000L,
        lastModified = 1_760_000_000_123L,
    )

    @Test
    fun cacheHitReusesDigestWithoutRecomputingAndInvalidatesOnEveryMetadataField() {
        val store = MemoryDigestStore()
        val digest1 = "a".repeat(64)
        var calls = 0
        val base = sampleIdentity()

        assertEquals(digest1, ApkDigestCache.getOrCompute(base, store) { calls++; digest1 })
        assertEquals(1, calls)

        // Identical 7-field identity hits cache.
        assertEquals(digest1, ApkDigestCache.getOrCompute(base, store) { calls++; "b".repeat(64) })
        assertEquals(1, calls)

        // Changing any of the 7 metadata fields must invalidate cache and recompute.
        val variants = listOf(
            base.copy(packageName = "com.tencent.mm.other"),
            base.copy(versionName = "8.0.81"),
            base.copy(versionCode = 3201L),
            base.copy(apkPath = "/data/app/other/base.apk"),
            base.copy(lastUpdateTime = base.lastUpdateTime + 1L),
            base.copy(fileLength = base.fileLength + 1L),
            base.copy(lastModified = base.lastModified + 1L),
        )
        variants.forEachIndexed { idx, mutated ->
            val expected = "%064x".format(idx + 1)
            val result = ApkDigestCache.getOrCompute(mutated, store) { calls++; expected }
            assertEquals("Variant #$idx must recompute", expected, result)
            assertEquals(idx + 2, calls)
        }
    }

    @Test
    fun missingCorruptedAndExceptionStoreFallsBackToRecompute() {
        val store = MemoryDigestStore()
        val base = sampleIdentity()
        val validDigest = "c".repeat(64)
        var calls = 0

        // Corrupted digest string (wrong length / non-hex) must recompute.
        store.storedIdentity = base.serialize()
        store.storedDigest = "not-a-valid-sha256"
        assertEquals(validDigest, ApkDigestCache.getOrCompute(base, store) { calls++; validDigest })
        assertEquals(1, calls)

        // Store read failure must recompute instead of throwing or trusting cache.
        store.throwOnRead = true
        val fallbackDigest = "d".repeat(64)
        assertEquals(fallbackDigest, ApkDigestCache.getOrCompute(base, store) { calls++; fallbackDigest })
        assertEquals(2, calls)
    }

    @Test
    fun incompleteMetadataNeverHitsOrWritesCache() {
        val store = MemoryDigestStore()
        val validDigest = "e".repeat(64)
        var calls = 0
        val badLength = sampleIdentity().copy(fileLength = 0L)
        val badMtime = sampleIdentity().copy(lastModified = 0L)
        val badUpdate = sampleIdentity().copy(lastUpdateTime = 0L)

        listOf(badLength, badMtime, badUpdate).forEach { invalid ->
            assertEquals(validDigest, ApkDigestCache.getOrCompute(invalid, store) { calls++; validDigest })
        }
        assertEquals(3, calls)
        assertEquals(null, store.storedIdentity)
        assertEquals(null, store.storedDigest)
    }

    @Test
    fun sha256ComputesTempPayloadAndMeasuresCacheHitVsHashComputation() {
        val tempFile = File.createTempFile("apk-digest-test", ".apk")
        try {
            val payload = ByteArray(4 * 1024 * 1024) { (it and 0xff).toByte() }
            tempFile.writeBytes(payload)

            val store = MemoryDigestStore()
            val identity = sampleIdentity().copy(
                apkPath = tempFile.absolutePath,
                fileLength = tempFile.length(),
                lastModified = tempFile.lastModified(),
            )

            val t0 = System.nanoTime()
            val firstDigest = ApkDigestCache.getOrCompute(identity, store) { ApkDigestCache.sha256Hex(tempFile) }
            val coldNanos = System.nanoTime() - t0

            val t1 = System.nanoTime()
            val cachedDigest = ApkDigestCache.getOrCompute(identity, store) {
                error("Cache hit should not recompute SHA-256")
            }
            val warmNanos = System.nanoTime() - t1

            assertEquals(64, firstDigest.length)
            assertEquals(firstDigest, cachedDigest)
            println("ApkDigestCacheTest(4MiB): cold=${coldNanos / 1_000_000.0}ms, warm=${warmNanos / 1_000_000.0}ms")
            assertTrue("Measured cold=$coldNanos ns, warm=$warmNanos ns", coldNanos > 0L && warmNanos >= 0L)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun cachedDigestNeverSkipsResolverSignatureOrStaticnessChecks() {
        val store = MemoryDigestStore()
        val loader = javaClass.classLoader!!
        val version = WechatVersion("8.0.80", 3200L)
        val digest = "f".repeat(64)
        val identity = sampleIdentity()
        val cachedDigest = ApkDigestCache.getOrCompute(identity, store) { digest }

        val badSignatureDescriptor = VerifiedWechatDescriptor(
            adaptationId = "bad-sig-fixture",
            version = version,
            apkSha256 = digest,
            className = WrongReadReturn::class.java.name,
            read = ExactMethodDescriptor("a", false),
            upload = ExactMethodDescriptor("d", false),
            semanticReviewEvidence = "fixture",
        )
        assertFalse(ExactWechatResolver().probe(loader, version, badSignatureDescriptor, cachedDigest).installAllowed)

        val wrongStaticDescriptor = badSignatureDescriptor.copy(
            className = d::class.java.name,
            read = ExactMethodDescriptor("a", true),
        )
        assertFalse(ExactWechatResolver().probe(loader, version, wrongStaticDescriptor, cachedDigest).installAllowed)

        val validDescriptor = wrongStaticDescriptor.copy(read = ExactMethodDescriptor("a", false))
        assertTrue(ExactWechatResolver().probe(loader, version, validDescriptor, cachedDigest).installAllowed)
    }
}

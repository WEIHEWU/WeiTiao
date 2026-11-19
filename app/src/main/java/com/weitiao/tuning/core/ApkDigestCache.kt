package com.weitiao.tuning.core

import android.content.SharedPreferences
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Metadata identity for caching an APK SHA-256 digest.
 *
 * Cache hit guarantees only that all seven metadata fields match the previously computed entry;
 * it does NOT re-verify the full APK file contents on disk, and NEVER caches hook authorization.
 */
internal data class ApkDigestIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkPath: String,
    val lastUpdateTime: Long,
    val fileLength: Long,
    val lastModified: Long,
) {
    val isValidMetadata: Boolean
        get() = packageName.isNotBlank() &&
            versionName.isNotBlank() &&
            versionCode > 0L &&
            apkPath.isNotBlank() &&
            lastUpdateTime > 0L &&
            fileLength > 0L &&
            lastModified > 0L

    fun serialize(): String = listOf(
        packageName,
        versionName,
        versionCode.toString(),
        apkPath,
        lastUpdateTime.toString(),
        fileLength.toString(),
        lastModified.toString(),
    ).joinToString("|") { "${it.length}:$it" }
}

internal interface ApkDigestStore {
    fun getIdentity(): String?
    fun getDigest(): String?
    fun put(identity: String, digest: String)
}

internal class SharedPrefsApkDigestStore(
    private val prefs: SharedPreferences,
) : ApkDigestStore {
    override fun getIdentity(): String? = prefs.getString(ApkDigestCache.KEY_DIGEST_IDENTITY, null)
    override fun getDigest(): String? = prefs.getString(ApkDigestCache.KEY_DIGEST_SHA256, null)
    override fun put(identity: String, digest: String) {
        prefs.edit()
            .putString(ApkDigestCache.KEY_DIGEST_IDENTITY, identity)
            .putString(ApkDigestCache.KEY_DIGEST_SHA256, digest)
            .apply()
    }
}

internal object ApkDigestCache {
    const val KEY_DIGEST_IDENTITY = "apk_digest_identity"
    const val KEY_DIGEST_SHA256 = "apk_digest_sha256"
    private val SHA256_REGEX = Regex("[0-9a-fA-F]{64}")

    fun getOrCompute(
        identity: ApkDigestIdentity,
        store: ApkDigestStore,
        compute: () -> String,
    ): String {
        val serialized = identity.serialize()
        if (identity.isValidMetadata) {
            val cached = runCatching {
                val storedId = store.getIdentity()
                val storedDigest = store.getDigest()
                if (storedId == serialized && storedDigest != null && SHA256_REGEX.matches(storedDigest)) {
                    storedDigest.lowercase(Locale.ROOT)
                } else {
                    null
                }
            }.getOrNull()
            if (cached != null) return cached
        }

        val computed = compute().lowercase(Locale.ROOT)
        if (identity.isValidMetadata && SHA256_REGEX.matches(computed)) {
            runCatching { store.put(serialized, computed) }
        }
        return computed
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

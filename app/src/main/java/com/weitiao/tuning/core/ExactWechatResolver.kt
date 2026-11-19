package com.weitiao.tuning.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Identity must come from the loaded WeChat package, not the module's BuildConfig. */
data class WechatVersion(
    val versionName: String,
    val versionCode: Long,
    val packageName: String = "com.tencent.mm",
)

data class ExactMethodDescriptor(
    val name: String,
    val isStatic: Boolean,
)

/**
 * Trusted, reviewed adaptation supplied by module code. Never construct this from user preferences
 * or promote a reflection/cache hit to it. The APK digest and semantic review are required because
 * two common method signatures cannot establish step semantics. No verified adaptations ship here.
 */
data class VerifiedWechatDescriptor(
    val adaptationId: String,
    val version: WechatVersion,
    val apkSha256: String,
    val className: String,
    val read: ExactMethodDescriptor,
    val upload: ExactMethodDescriptor,
    val semanticReviewEvidence: String,
)

enum class ExactWechatStatus { UNAVAILABLE, UNVERIFIED, VERIFIED }

class ExactWechatCandidate internal constructor(val read: Method, val upload: Method)

/** Only the resolver can authorize installation; a candidate alone is never an installation token. */
class ExactWechatResolution private constructor(
    val status: ExactWechatStatus,
    val reason: String,
    val candidate: ExactWechatCandidate?,
    val adaptationId: String?,
) {
    val installAllowed: Boolean get() = status == ExactWechatStatus.VERIFIED && candidate != null

    internal companion object {
        fun unavailable(reason: String) =
            ExactWechatResolution(ExactWechatStatus.UNAVAILABLE, reason, null, null)
        fun unverified(reason: String, candidate: ExactWechatCandidate? = null) =
            ExactWechatResolution(ExactWechatStatus.UNVERIFIED, reason, candidate, null)
        fun verified(candidate: ExactWechatCandidate, id: String) =
            ExactWechatResolution(ExactWechatStatus.VERIFIED, "Verified adaptation: $id", candidate, id)
    }
}

/** Future DexKit/cache implementations can supply hints, never verification or install permission. */
interface ExactWechatCandidateSource {
    fun lookup(version: WechatVersion): ExactWechatCandidateHint
}

data class ExactWechatCandidateHint(val className: String?, val reason: String)

object UnavailableWechatCandidateSource : ExactWechatCandidateSource {
    override fun lookup(version: WechatVersion) = ExactWechatCandidateHint(
        null, "Generic discovery unavailable: DexKit scanning and adaptation cache are not implemented",
    )
}

/** Pure JVM reflection probe. Does not initialize target classes, invoke methods, or install hooks. */
class ExactWechatResolver(
    private val candidateSource: ExactWechatCandidateSource = UnavailableWechatCandidateSource,
) {
    fun probe(
        classLoader: ClassLoader,
        version: WechatVersion,
        descriptor: VerifiedWechatDescriptor? = null,
        apkSha256: String? = null,
    ): ExactWechatResolution {
        if (version.packageName != "com.tencent.mm" || version.versionName.isBlank() || version.versionCode <= 0) {
            return ExactWechatResolution.unavailable("Invalid WeChat package/version identity")
        }
        if (descriptor != null) {
            val error = validateDescriptor(descriptor, version, apkSha256)
            if (error != null) return ExactWechatResolution.unavailable(error)
            return resolve(classLoader, descriptor.className, descriptor.read, descriptor.upload).let { result ->
                if (result.candidate != null) ExactWechatResolution.verified(result.candidate, descriptor.adaptationId)
                else result
            }
        }
        // This reported location is a hint only. Staticness and semantic meaning remain unverified.
        val hint = if (version.versionName == "8.0.79" && version.versionCode == 3183L) {
            ExactWechatCandidateHint("com.tencent.mm.plugin.sport.model.d", "Reported 8.0.79/3183 location")
        } else {
            try { candidateSource.lookup(version) } catch (e: Exception) {
                return ExactWechatResolution.unavailable("Candidate source failed: ${e.javaClass.simpleName}")
            }
        }
        val className = hint.className ?: return ExactWechatResolution.unavailable(hint.reason)
        val result = resolve(classLoader, className, null, null)
        return if (result.candidate != null) {
            ExactWechatResolution.unverified(
                "Candidate only: no reviewed adaptation bound to this APK; signatures do not verify step semantics",
                result.candidate,
            )
        } else result
    }

    private fun validateDescriptor(d: VerifiedWechatDescriptor, version: WechatVersion, apk: String?): String? = when {
        d.version != version -> "Adaptation package/version mismatch"
        d.adaptationId.isBlank() || d.semanticReviewEvidence.isBlank() -> "Missing adaptation identity or semantic review evidence"
        !SHA256.matches(d.apkSha256) || apk == null || !SHA256.matches(apk) -> "Missing or invalid APK SHA-256"
        !d.apkSha256.equals(apk, ignoreCase = true) -> "Adaptation APK SHA-256 mismatch"
        !d.className.startsWith("com.tencent.mm.") || !CLASS_NAME.matches(d.className) -> "Invalid adaptation class name"
        !METHOD_NAME.matches(d.read.name) || !METHOD_NAME.matches(d.upload.name) -> "Invalid adaptation method name"
        d.read.name == d.upload.name -> "Read/upload method names must be distinct and unambiguous"
        else -> null
    }

    private fun resolve(
        loader: ClassLoader,
        className: String,
        readSpec: ExactMethodDescriptor?,
        uploadSpec: ExactMethodDescriptor?,
    ): ExactWechatResolution {
        try {
            val type = Class.forName(className, false, loader)
            val declared = type.declaredMethods
            val readName = readSpec?.name ?: "a"
            val uploadName = uploadSpec?.name ?: "d"
            // Reject all same-name overloads, including bridge/synthetic variants, rather than guess.
            val reads = declared.filter { it.name == readName }
            val uploads = declared.filter { it.name == uploadName }
            if (reads.size != 1 || uploads.size != 1) {
                return ExactWechatResolution.unavailable("Missing or ambiguous read/upload method (overloads rejected)")
            }
            val read = reads.single()
            val upload = uploads.single()
            if (read.returnType != java.lang.Long.TYPE || read.parameterTypes.isNotEmpty()) {
                return ExactWechatResolution.unavailable("Read signature must be (): primitive long")
            }
            if (upload.returnType != java.lang.Boolean.TYPE ||
                !upload.parameterTypes.contentEquals(arrayOf(java.lang.Long.TYPE))) {
                return ExactWechatResolution.unavailable("Upload signature must be (primitive long): primitive boolean")
            }
            if (listOf(read, upload).any { Modifier.isAbstract(it.modifiers) || it.isSynthetic || it.isBridge }) {
                return ExactWechatResolution.unavailable("Abstract, synthetic or bridge methods are unsupported")
            }
            if ((readSpec != null && Modifier.isStatic(read.modifiers) != readSpec.isStatic) ||
                (uploadSpec != null && Modifier.isStatic(upload.modifiers) != uploadSpec.isStatic)) {
                return ExactWechatResolution.unavailable("Adaptation method staticness mismatch")
            }
            return ExactWechatResolution.unverified("Reflection candidate requires semantic verification", ExactWechatCandidate(read, upload))
        } catch (e: ClassNotFoundException) {
            return ExactWechatResolution.unavailable("Candidate class unavailable: $className")
        } catch (e: LinkageError) {
            return ExactWechatResolution.unavailable("Candidate linkage failed: ${e.javaClass.simpleName}")
        } catch (e: SecurityException) {
            return ExactWechatResolution.unavailable("Reflection unavailable: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val METHOD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    }
}

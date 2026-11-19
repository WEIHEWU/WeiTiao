package com.weitiao.tuning.core

/** Static bytecode-reviewed adaptation; runtime APK digest and reflection checks remain mandatory. */
internal object ReviewedWechatAdaptations {
    val play8080 = VerifiedWechatDescriptor(
        adaptationId = "play-8.0.80-3200-device-step",
        version = WechatVersion("8.0.80", 3200L),
        apkSha256 = "b6fc9913abf64f4a1be2ceea51abe490cb976063e64690aa6ffcd1a6e9a22172",
        className = "com.tencent.mm.plugin.sport.model.d",
        read = ExactMethodDescriptor("a", false),
        upload = ExactMethodDescriptor("d", false),
        semanticReviewEvidence = "classes16.dex: a() checks c0.g day against z.b().a and returns x.f; " +
            "d(J) validates positive count, constructs sport.model.j with count, enqueues network request, " +
            "writes KEY_LAST_UPLOAD_DEVICE_STEP_COUNT_LONG. sport.model.a.callback calls a then d. " +
            "Static bytecode reviewed; runtime hook and server acceptance not tested.",
    )

    fun forVersion(version: WechatVersion): VerifiedWechatDescriptor? =
        play8080.takeIf { it.version == version }
}

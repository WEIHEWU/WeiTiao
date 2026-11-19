package com.weitiao.tuning.core

/** Requested policy; SENSOR remains the default even when a verified adaptation is available. */
enum class StepBackend { SENSOR, AUTO, EXACT }

@ConsistentCopyVisibility
data class StepBackendSelection internal constructor(
    val requested: StepBackend,
    val backend: StepBackend,
    val reason: String,
    val exact: ExactWechatResolution? = null,
) {
    val installExact: Boolean get() = backend == StepBackend.EXACT && exact?.installAllowed == true
}

object StepBackendSelector {
    /** Call probe first for AUTO/EXACT; install only when the returned installExact is true. */
    fun select(
        requested: StepBackend = StepBackend.SENSOR,
        resolution: ExactWechatResolution? = null,
    ): StepBackendSelection {
        if (requested == StepBackend.SENSOR) {
            return StepBackendSelection(requested, StepBackend.SENSOR, "SENSOR selected (default)")
        }
        if (resolution?.installAllowed == true) {
            return StepBackendSelection(requested, StepBackend.EXACT, resolution.reason, resolution)
        }
        return StepBackendSelection(
            requested,
            StepBackend.SENSOR,
            "${requested.name} fell back to SENSOR: ${resolution?.reason ?: "no verified WeChat adaptation"}",
        )
    }
}

package dev.jongwoo.androidvm.vm

import org.json.JSONObject

/**
 * Phase E.5 / E.6 / E.7 graphics acceleration ladder. The host runtime can deliver guest frames
 * via four modes; the diagnostics gate confirms the per-mode capability flag aligns with the
 * host driver's actual support, and surfaces a graceful-degradation reason when a mode is
 * skipped.
 */
enum class GraphicsAccelerationMode(val wireName: String) {
    SOFTWARE_FRAMEBUFFER("software_framebuffer"),
    GLES_PASSTHROUGH("gles_passthrough"),
    VIRGL("virgl"),
    VENUS("venus"),
    ;

    companion object {
        fun fromWireName(value: String): GraphicsAccelerationMode? =
            entries.firstOrNull { it.wireName == value }
    }
}

/** Per-mode capability declaration. */
data class GraphicsModeCapability(
    val mode: GraphicsAccelerationMode,
    val supportedByHost: Boolean,
    val frameCount: Int,
    val fpsAvg: Int,
    val gpuName: String?,
    val degradationReason: String?,
) {
    val ready: Boolean
        get() = supportedByHost && frameCount >= MIN_FRAME_COUNT && fpsAvg >= MIN_FPS

    val gatePassed: Boolean
        get() = ready || !supportedByHost

    fun detail(): String {
        if (!supportedByHost) {
            val reason = degradationReason ?: "host_unsupported"
            return "skipped=true reason=$reason"
        }
        return "frame_count_ge=$frameCount fps_avg_ge=$fpsAvg gpu_name=${gpuName ?: "unknown"}"
    }

    fun line(label: String): String {
        if (!supportedByHost) {
            val reason = degradationReason ?: "host_unsupported"
            return "$label passed=false skipped=true reason=$reason"
        }
        return "$label passed=$ready frame_count_ge=$frameCount fps_avg_ge=$fpsAvg " +
            "gpu_name=${gpuName ?: "unknown"}"
    }

    companion object {
        const val MIN_FRAME_COUNT: Int = 300
        const val MIN_FPS: Int = 30

        fun unsupported(mode: GraphicsAccelerationMode, reason: String): GraphicsModeCapability =
            GraphicsModeCapability(
                mode = mode,
                supportedByHost = false,
                frameCount = 0,
                fpsAvg = 0,
                gpuName = null,
                degradationReason = reason,
            )

        fun ready(
            mode: GraphicsAccelerationMode,
            frameCount: Int,
            fpsAvg: Int,
            gpuName: String,
        ): GraphicsModeCapability = GraphicsModeCapability(
            mode = mode,
            supportedByHost = true,
            frameCount = frameCount,
            fpsAvg = fpsAvg,
            gpuName = gpuName,
            degradationReason = null,
        )
    }
}

/** Capability matrix the receiver fills in once the host driver has been probed. */
data class GraphicsAccelerationMatrix(
    val gles: GraphicsModeCapability,
    val virgl: GraphicsModeCapability,
    val venus: GraphicsModeCapability,
) {
    init {
        require(gles.mode == GraphicsAccelerationMode.GLES_PASSTHROUGH)
        require(virgl.mode == GraphicsAccelerationMode.VIRGL)
        require(venus.mode == GraphicsAccelerationMode.VENUS)
    }

    /** Phase E core gate accepts either ready or graceful degradation per mode. */
    fun gateOk(): Boolean = (gles.ready || !gles.supportedByHost) &&
        (virgl.ready || !virgl.supportedByHost) &&
        (venus.ready || !venus.supportedByHost)

    fun toJson(): JSONObject = JSONObject()
        .put("gles", JSONObject()
            .put("ready", gles.ready)
            .put("supportedByHost", gles.supportedByHost))
        .put("virgl", JSONObject()
            .put("ready", virgl.ready)
            .put("supportedByHost", virgl.supportedByHost))
        .put("venus", JSONObject()
            .put("ready", venus.ready)
            .put("supportedByHost", venus.supportedByHost))

    companion object {
        /** Off-device default: every mode reports "host driver not probed yet". */
        fun unprobed(): GraphicsAccelerationMatrix = GraphicsAccelerationMatrix(
            gles = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.GLES_PASSTHROUGH,
                "host_driver_not_probed",
            ),
            virgl = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.VIRGL,
                "host_driver_not_probed",
            ),
            venus = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.VENUS,
                "host_driver_not_probed",
            ),
        )
    }
}

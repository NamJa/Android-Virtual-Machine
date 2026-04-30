package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

/** A YUV_420_888 frame as captured by [CameraXFrameSource]. */
data class CameraFrame(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val ySize: Int,
    val uSize: Int,
    val vSize: Int,
) {
    init {
        require(width > 0 && height > 0) { "CameraFrame dimensions must be positive" }
        require(ySize > 0 && uSize > 0 && vSize > 0) { "plane sizes must be positive" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("format", FORMAT_YUV_420_888)
        .put("width", width)
        .put("height", height)
        .put("timestampNs", timestampNanos)
        .put("ySize", ySize)
        .put("uSize", uSize)
        .put("vSize", vSize)

    companion object {
        const val FORMAT_YUV_420_888 = "YUV_420_888"
    }
}

/**
 * Phase D.5 frame source. Production implementations wrap CameraX `ImageAnalysis`; tests pass a
 * [FixedCameraSource] that returns a single canned frame.
 */
interface CameraXFrameSource {
    /** Returns the next available frame, or null if the source has not produced one yet. */
    suspend fun nextFrame(): CameraFrame?

    /** Total number of frames pushed since the source was opened. */
    fun pushedFrames(): Long
}

class FixedCameraSource(
    private val frame: CameraFrame? = CameraFrame(
        width = 640,
        height = 480,
        timestampNanos = 0L,
        ySize = 640 * 480,
        uSize = 640 * 480 / 4,
        vSize = 640 * 480 / 4,
    ),
) : CameraXFrameSource {
    private var emitted: Long = 0L

    override suspend fun nextFrame(): CameraFrame? {
        if (frame != null) emitted += 1
        return frame
    }

    override fun pushedFrames(): Long = emitted
}

/**
 * Phase D.5 camera bridge. Bridge ENABLED + first frame request triggers `CAMERA` permission
 * request via [PermissionRequestGateway]. OFF / UNSUPPORTED policies short-circuit before the
 * gateway is touched, so off bridges never raise a permission prompt.
 */
class CameraBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val permissionGateway: PermissionRequestGateway,
    private val frameSource: CameraXFrameSource,
) : BridgeHandler {
    override val bridge: BridgeType = BridgeType.CAMERA

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        require(request.bridge == BridgeType.CAMERA) { "CameraBridge handles CAMERA only" }
        val policy = policyStore.load().getValue(BridgeType.CAMERA)
        val response = when {
            !policy.enabled || policy.mode == BridgeMode.OFF ->
                BridgeResponse(BridgeResult.UNAVAILABLE, "camera_disabled")
            policy.mode == BridgeMode.UNSUPPORTED ->
                BridgeResponse(BridgeResult.UNSUPPORTED, "camera_unsupported")
            policy.mode != BridgeMode.ENABLED ->
                BridgeResponse(BridgeResult.UNSUPPORTED, "camera_mode_unsupported")
            else -> {
                val granted = permissionGateway.request(
                    permission = REQUIRED_PERMISSION,
                    reason = request.reason.copy(permission = REQUIRED_PERMISSION),
                )
                if (!granted) {
                    BridgeResponse(BridgeResult.DENIED, "camera_permission_denied")
                } else {
                    val frame = frameSource.nextFrame()
                    if (frame == null) {
                        BridgeResponse(BridgeResult.UNAVAILABLE, "camera_no_frame")
                    } else {
                        BridgeResponse(BridgeResult.ALLOWED, "camera_frame_delivered", frame.toJson().toString())
                    }
                }
            }
        }
        auditLog.appendDecision(
            instanceId = request.instanceId,
            bridge = BridgeType.CAMERA,
            operation = request.operation,
            decision = BridgeDecision(
                allowed = response.result == BridgeResult.ALLOWED,
                result = response.result,
                reason = response.reason,
            ),
        )
        return response
    }

    fun pushedFrames(): Long = frameSource.pushedFrames()

    companion object {
        const val REQUIRED_PERMISSION = "android.permission.CAMERA"
    }
}

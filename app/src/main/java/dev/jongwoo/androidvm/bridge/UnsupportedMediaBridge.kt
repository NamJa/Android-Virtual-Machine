package dev.jongwoo.androidvm.bridge

/**
 * Stage 07 MVP boundary for camera and microphone bridges. The handler always returns
 * UNSUPPORTED — no host CameraX / AudioRecord call is ever made and no host runtime permission
 * is requested. The audit log records the request for visibility.
 */
class UnsupportedMediaBridge(
    override val bridge: BridgeType,
    private val auditLog: BridgeAuditLog,
) : BridgeHandler {
    init {
        require(bridge == BridgeType.CAMERA || bridge == BridgeType.MICROPHONE) {
            "UnsupportedMediaBridge only handles CAMERA / MICROPHONE"
        }
    }

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        require(request.bridge == bridge) {
            "UnsupportedMediaBridge bridge mismatch: handler=$bridge request=${request.bridge}"
        }
        val reason = "${bridge.wireName}_unsupported_stage7_mvp"
        val decision = BridgeDecision.unsupported(reason)
        auditLog.appendDecision(
            instanceId = request.instanceId,
            bridge = bridge,
            operation = request.operation,
            decision = decision,
        )
        return BridgeResponse(BridgeResult.UNSUPPORTED, reason)
    }
}

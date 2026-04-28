package dev.jongwoo.androidvm.bridge

class NetworkBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
) : BridgeHandler {
    override val bridge: BridgeType = BridgeType.NETWORK

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        require(request.bridge == BridgeType.NETWORK) {
            "NetworkBridge handles NETWORK only"
        }
        val policy = policyStore.load().getValue(BridgeType.NETWORK)
        val response = if (!policy.enabled || policy.mode == BridgeMode.OFF) {
            BridgeResponse(BridgeResult.UNAVAILABLE, "network_disabled")
        } else {
            BridgeResponse(BridgeResult.ALLOWED, "network_enabled")
        }
        auditLog.appendDecision(
            instanceId = request.instanceId,
            bridge = BridgeType.NETWORK,
            operation = request.operation,
            decision = BridgeDecision(response.result == BridgeResult.ALLOWED, response.result, response.reason),
        )
        return response
    }
}

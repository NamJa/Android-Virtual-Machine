package dev.jongwoo.androidvm.bridge

/**
 * Single point that decides whether a guest bridge request is allowed, denied, unavailable, or
 * unsupported and — for the small set of bridges that need a host runtime permission — drives the
 * permission prompt through the [PermissionRequestGateway].
 *
 * Off / unsupported paths must NEVER call [permissionGateway]; this is asserted by tests.
 */
class DefaultPermissionBroker(
    private val policyStoreFor: (instanceId: String) -> BridgePolicyStore,
    private val permissionGateway: PermissionRequestGateway,
) : PermissionBroker {

    override suspend fun decide(
        instanceId: String,
        bridge: BridgeType,
        operation: String,
        reason: PermissionReason,
    ): BridgeDecision {
        require(reason.bridge == bridge) {
            "PermissionReason.bridge must match the requested bridge"
        }
        require(reason.operation == operation) {
            "PermissionReason.operation must match the requested operation"
        }

        val policy = policyStoreFor(instanceId).load().getValue(bridge)

        if (policy.mode == BridgeMode.UNSUPPORTED) {
            return BridgeDecision.unsupported("bridge_unsupported")
        }
        if (!policy.enabled || policy.mode == BridgeMode.OFF) {
            return BridgeDecision.unavailable("bridge_disabled")
        }

        val permission = dangerousPermissionFor(bridge, policy.mode)
        if (permission != null) {
            val granted = permissionGateway.request(
                permission,
                reason.copy(permission = permission),
            )
            if (!granted) {
                return BridgeDecision.denied("permission_denied")
            }
        }

        return BridgeDecision.allowed("allowed")
    }

    override suspend fun ensurePermission(
        permission: String,
        reason: PermissionReason,
    ): Boolean = permissionGateway.request(permission, reason.copy(permission = permission))

    override fun isBridgeEnabled(instanceId: String, bridge: BridgeType): Boolean {
        val policy = policyStoreFor(instanceId).load().getValue(bridge)
        return policy.enabled && policy.mode != BridgeMode.OFF && policy.mode != BridgeMode.UNSUPPORTED
    }

    override fun setBridgePolicy(instanceId: String, bridge: BridgeType, mode: BridgeMode) {
        val store = policyStoreFor(instanceId)
        store.update(bridge) { current ->
            val unsupportedByScope = Stage7BridgeScope.support[bridge] == BridgeSupport.UNSUPPORTED_MVP
            val effectiveMode = if (unsupportedByScope && mode != BridgeMode.UNSUPPORTED) {
                BridgeMode.UNSUPPORTED
            } else {
                mode
            }
            current.copy(
                mode = effectiveMode,
                enabled = when (effectiveMode) {
                    BridgeMode.OFF, BridgeMode.UNSUPPORTED -> false
                    else -> true
                },
            )
        }
    }
}

package dev.jongwoo.androidvm.bridge

interface PermissionBroker {
    suspend fun decide(
        instanceId: String,
        bridge: BridgeType,
        operation: String,
        reason: PermissionReason,
    ): BridgeDecision

    suspend fun ensurePermission(permission: String, reason: PermissionReason): Boolean

    fun isBridgeEnabled(instanceId: String, bridge: BridgeType): Boolean

    fun setBridgePolicy(instanceId: String, bridge: BridgeType, mode: BridgeMode)
}

/**
 * Resolves the dangerous Android permission required for a given bridge / mode pair, or null when
 * no host runtime permission is involved (off, unsupported, fixed-location, output-only bridges).
 */
fun dangerousPermissionFor(bridge: BridgeType, mode: BridgeMode): String? = when (bridge) {
    BridgeType.LOCATION -> when (mode) {
        BridgeMode.LOCATION_HOST_REAL -> "android.permission.ACCESS_FINE_LOCATION"
        else -> null
    }
    BridgeType.CAMERA -> if (mode == BridgeMode.ENABLED) "android.permission.CAMERA" else null
    BridgeType.MICROPHONE -> if (mode == BridgeMode.ENABLED) "android.permission.RECORD_AUDIO" else null
    BridgeType.CLIPBOARD,
    BridgeType.AUDIO_OUTPUT,
    BridgeType.NETWORK,
    BridgeType.DEVICE_PROFILE,
    BridgeType.VIBRATION,
    -> null
}

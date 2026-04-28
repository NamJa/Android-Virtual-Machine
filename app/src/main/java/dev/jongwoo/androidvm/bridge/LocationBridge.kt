package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

data class GuestLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 50f,
    val timeMillis: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("accuracyMeters", accuracyMeters.toDouble())
        .put("timeMillis", timeMillis)
}

interface HostLocationProvider {
    suspend fun currentLocation(): GuestLocation?
}

class FixedHostLocationProvider(private val location: GuestLocation?) : HostLocationProvider {
    override suspend fun currentLocation(): GuestLocation? = location
}

class LocationBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val permissionGateway: PermissionRequestGateway,
    private val hostLocationProvider: HostLocationProvider,
) : BridgeHandler {
    override val bridge: BridgeType = BridgeType.LOCATION

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        require(request.bridge == BridgeType.LOCATION) {
            "LocationBridge handles LOCATION only"
        }
        val policy = policyStore.load().getValue(BridgeType.LOCATION)
        val response = when {
            !policy.enabled || policy.mode == BridgeMode.OFF ->
                BridgeResponse(BridgeResult.UNAVAILABLE, "location_disabled")
            policy.mode == BridgeMode.LOCATION_FIXED -> fixedLocation(policy)
            policy.mode == BridgeMode.LOCATION_HOST_REAL -> realLocation(request)
            else -> BridgeResponse(BridgeResult.UNSUPPORTED, "location_mode_unsupported")
        }
        auditLog.appendDecision(
            instanceId = request.instanceId,
            bridge = BridgeType.LOCATION,
            operation = request.operation,
            decision = BridgeDecision(
                allowed = response.result == BridgeResult.ALLOWED,
                result = response.result,
                reason = response.reason,
            ),
        )
        return response
    }

    private fun fixedLocation(policy: BridgePolicy): BridgeResponse {
        val latitude = policy.options[OPTION_LATITUDE]?.toDoubleOrNull()
            ?: return BridgeResponse(BridgeResult.UNAVAILABLE, "fixed_location_missing")
        val longitude = policy.options[OPTION_LONGITUDE]?.toDoubleOrNull()
            ?: return BridgeResponse(BridgeResult.UNAVAILABLE, "fixed_location_missing")
        val accuracy = policy.options[OPTION_ACCURACY]?.toFloatOrNull() ?: 50f
        val location = GuestLocation(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracy,
        )
        return BridgeResponse(BridgeResult.ALLOWED, "fixed_location", location.toJson().toString())
    }

    private suspend fun realLocation(request: BridgeRequest): BridgeResponse {
        val granted = permissionGateway.request(
            permission = "android.permission.ACCESS_FINE_LOCATION",
            reason = request.reason.copy(permission = "android.permission.ACCESS_FINE_LOCATION"),
        )
        if (!granted) {
            return BridgeResponse(BridgeResult.DENIED, "location_permission_denied")
        }
        val location = hostLocationProvider.currentLocation()
            ?: return BridgeResponse(BridgeResult.UNAVAILABLE, "location_provider_unavailable")
        return BridgeResponse(BridgeResult.ALLOWED, "host_location", location.toJson().toString())
    }

    companion object {
        const val OPTION_LATITUDE = "latitude"
        const val OPTION_LONGITUDE = "longitude"
        const val OPTION_ACCURACY = "accuracyMeters"
    }
}

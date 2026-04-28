package dev.jongwoo.androidvm.bridge

import java.io.File
import java.util.UUID
import org.json.JSONObject

/**
 * Synthetic device profile returned to the guest. The host's real Build / TelephonyManager /
 * advertising IDs are intentionally never reflected here. Stage 07 only exposes a per-instance
 * stable Android ID and a fixed manufacturer/model/brand triple.
 */
data class SyntheticDeviceProfile(
    val manufacturer: String = "CleanRoom",
    val model: String = "VirtualPhone",
    val brand: String = "CleanRoom",
    val androidId: String,
    val serial: String = "unknown",
    val phoneNumber: String = "",
    val imei: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("brand", brand)
        .put("androidId", androidId)
        .put("serial", serial)
        .put("phoneNumber", phoneNumber)
        .put("imei", imei)
}

class DeviceProfileBridge(
    private val instanceRoot: File,
    private val auditLog: BridgeAuditLog,
    private val androidIdProvider: () -> String = { UUID.randomUUID().toString().replace("-", "") },
) : BridgeHandler {
    override val bridge: BridgeType = BridgeType.DEVICE_PROFILE

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        require(request.bridge == BridgeType.DEVICE_PROFILE) {
            "DeviceProfileBridge handles DEVICE_PROFILE only"
        }
        val profile = SyntheticDeviceProfile(androidId = loadOrCreateAndroidId())
        val payload = sanitizedPayload(profile)
        auditLog.appendDecision(
            instanceId = request.instanceId,
            bridge = BridgeType.DEVICE_PROFILE,
            operation = request.operation,
            decision = BridgeDecision.allowed("synthetic_profile"),
        )
        return BridgeResponse(
            result = BridgeResult.ALLOWED,
            reason = "synthetic_profile",
            payloadJson = payload.toString(),
        )
    }

    private fun loadOrCreateAndroidId(): String {
        instanceRoot.mkdirs()
        val file = File(instanceRoot, ANDROID_ID_FILE_NAME)
        if (file.exists()) {
            val cached = file.readText().trim()
            if (cached.isNotEmpty()) return cached
        }
        val id = androidIdProvider()
        file.writeText(id)
        return id
    }

    private fun sanitizedPayload(profile: SyntheticDeviceProfile): JSONObject {
        val json = profile.toJson()
        Stage7BridgeScope.forbiddenHostIdentityFields.forEach { field ->
            if (json.has(field) && field !in keptFields) {
                json.remove(field)
            }
        }
        return json
    }

    companion object {
        const val ANDROID_ID_FILE_NAME = "synthetic-android-id"

        // imei and phoneNumber stay in the response intentionally as redacted empty strings so
        // the guest sees them as known-empty fields; advertisingId / hostInstalledPackages are
        // not present at all.
        private val keptFields = setOf("imei", "phoneNumber", "simSerialNumber", "meid")
    }
}

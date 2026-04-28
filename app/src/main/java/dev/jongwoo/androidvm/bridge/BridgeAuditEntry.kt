package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

/**
 * Stage 07 audit entry. Sensitive payload (clipboard text, raw coordinates, package data) is
 * never persisted. Only the bridge identity, the operation name, the policy decision, and a
 * short reason are recorded.
 */
data class BridgeAuditEntry(
    val timeMillis: Long,
    val instanceId: String,
    val bridge: BridgeType,
    val operation: String,
    val allowed: Boolean,
    val result: BridgeResult,
    val reason: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", timeMillis)
        .put("instanceId", instanceId)
        .put("bridge", bridge.wireName)
        .put("operation", operation)
        .put("allowed", allowed)
        .put("result", result.wireName)
        .put("reason", reason)

    companion object {
        fun fromJson(value: JSONObject): BridgeAuditEntry = BridgeAuditEntry(
            timeMillis = value.getLong("time"),
            instanceId = value.getString("instanceId"),
            bridge = BridgeType.fromWireName(value.getString("bridge"))
                ?: error("Unknown bridge: ${value.getString("bridge")}"),
            operation = value.getString("operation"),
            allowed = value.getBoolean("allowed"),
            result = BridgeResult.fromWireName(value.getString("result"))
                ?: error("Unknown result: ${value.getString("result")}"),
            reason = value.getString("reason"),
        )
    }
}

package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

data class BridgeRequest(
    val instanceId: String,
    val bridge: BridgeType,
    val operation: String,
    val reason: PermissionReason,
    val payloadJson: String = "{}",
) {
    fun parsedPayload(): JSONObject = if (payloadJson.isBlank()) JSONObject() else JSONObject(payloadJson)
}

data class BridgeResponse(
    val result: BridgeResult,
    val reason: String,
    val payloadJson: String = "{}",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("result", result.wireName)
        .put("reason", reason)
        .put("payload", if (payloadJson.isBlank()) JSONObject() else JSONObject(payloadJson))
}

interface BridgeHandler {
    val bridge: BridgeType
    suspend fun handle(request: BridgeRequest): BridgeResponse
}

package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

/**
 * Per-bridge policy record. One [BridgePolicy] is stored per [BridgeType] in the persistent
 * [BridgePolicyStore].
 *
 * - [enabled]: false means the bridge is off. Off bridges return UNAVAILABLE without ever calling
 *   the host API.
 * - [mode]: how the bridge behaves when [enabled]. UNSUPPORTED is reserved for Stage 07 MVP
 *   bridges that have no host implementation yet (camera, microphone).
 * - [options]: free-form per-bridge configuration (e.g. fixed-location coordinates).
 */
data class BridgePolicy(
    val bridge: BridgeType,
    val mode: BridgeMode,
    val enabled: Boolean,
    val options: Map<String, String> = emptyMap(),
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
            .put("bridge", bridge.wireName)
            .put("mode", mode.wireName)
            .put("enabled", enabled)
        val opts = JSONObject()
        options.forEach { (key, value) -> opts.put(key, value) }
        json.put("options", opts)
        return json
    }

    companion object {
        fun fromJson(value: JSONObject): BridgePolicy {
            val bridge = BridgeType.fromWireName(value.getString("bridge"))
                ?: error("Unknown bridge: ${value.getString("bridge")}")
            val mode = BridgeMode.fromWireName(value.getString("mode"))
                ?: error("Unknown bridge mode: ${value.getString("mode")}")
            val options = mutableMapOf<String, String>()
            value.optJSONObject("options")?.let { obj ->
                obj.keys().forEach { key -> options[key] = obj.getString(key) }
            }
            return BridgePolicy(
                bridge = bridge,
                mode = mode,
                enabled = value.getBoolean("enabled"),
                options = options,
            )
        }
    }
}

object DefaultBridgePolicies {
    val all: Map<BridgeType, BridgePolicy> = mapOf(
        BridgeType.CLIPBOARD to BridgePolicy(BridgeType.CLIPBOARD, BridgeMode.OFF, enabled = false),
        BridgeType.LOCATION to BridgePolicy(BridgeType.LOCATION, BridgeMode.OFF, enabled = false),
        BridgeType.CAMERA to BridgePolicy(BridgeType.CAMERA, BridgeMode.UNSUPPORTED, enabled = false),
        BridgeType.MICROPHONE to BridgePolicy(BridgeType.MICROPHONE, BridgeMode.UNSUPPORTED, enabled = false),
        BridgeType.AUDIO_OUTPUT to BridgePolicy(BridgeType.AUDIO_OUTPUT, BridgeMode.ENABLED, enabled = true),
        BridgeType.NETWORK to BridgePolicy(BridgeType.NETWORK, BridgeMode.ENABLED, enabled = true),
        BridgeType.DEVICE_PROFILE to BridgePolicy(BridgeType.DEVICE_PROFILE, BridgeMode.ENABLED, enabled = true),
        BridgeType.VIBRATION to BridgePolicy(BridgeType.VIBRATION, BridgeMode.ENABLED, enabled = true),
    )

    fun forBridge(bridge: BridgeType): BridgePolicy =
        all.getValue(bridge)
}

/**
 * Legacy bag-of-flags policy retained only so vm_config.json keeps the same shape while Stage 07
 * migrates the rest of the host runtime to per-bridge policy. Will be removed once the native
 * side reads policy through [BridgePolicyStore] / dispatcher.
 */
data class LegacyBridgePolicy(
    val audioOutput: Boolean = true,
    val vibration: Boolean = true,
    val clipboard: Boolean = false,
    val contacts: Boolean = false,
    val files: Boolean = false,
    val location: Boolean = false,
    val microphone: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("audioOutput", audioOutput)
        .put("vibration", vibration)
        .put("clipboard", clipboard)
        .put("contacts", contacts)
        .put("files", files)
        .put("location", location)
        .put("microphone", microphone)

    companion object {
        fun from(policies: Map<BridgeType, BridgePolicy>): LegacyBridgePolicy = LegacyBridgePolicy(
            audioOutput = policies[BridgeType.AUDIO_OUTPUT]?.enabled == true,
            vibration = policies[BridgeType.VIBRATION]?.enabled == true,
            clipboard = policies[BridgeType.CLIPBOARD]?.enabled == true,
            contacts = false,
            files = false,
            location = policies[BridgeType.LOCATION]?.enabled == true,
            microphone = policies[BridgeType.MICROPHONE]?.enabled == true,
        )
    }
}

package dev.jongwoo.androidvm.bridge

enum class BridgeType {
    CLIPBOARD,
    LOCATION,
    CAMERA,
    MICROPHONE,
    AUDIO_OUTPUT,
    NETWORK,
    DEVICE_PROFILE,
    VIBRATION,
    ;

    val wireName: String
        get() = name.lowercase()

    companion object {
        fun fromWireName(value: String): BridgeType? = entries.firstOrNull { it.wireName == value }
    }
}

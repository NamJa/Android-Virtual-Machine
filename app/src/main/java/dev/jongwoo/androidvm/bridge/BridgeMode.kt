package dev.jongwoo.androidvm.bridge

enum class BridgeMode {
    OFF,
    ENABLED,
    UNSUPPORTED,
    CLIPBOARD_HOST_TO_GUEST,
    CLIPBOARD_GUEST_TO_HOST,
    CLIPBOARD_BIDIRECTIONAL,
    LOCATION_FIXED,
    LOCATION_HOST_REAL,
    ;

    val wireName: String
        get() = name.lowercase()

    companion object {
        fun fromWireName(value: String): BridgeMode? = entries.firstOrNull { it.wireName == value }
    }
}

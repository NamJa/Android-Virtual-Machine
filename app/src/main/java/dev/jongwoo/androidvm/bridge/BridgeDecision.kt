package dev.jongwoo.androidvm.bridge

enum class BridgeResult {
    ALLOWED,
    DENIED,
    UNAVAILABLE,
    UNSUPPORTED,
    ;

    val wireName: String
        get() = name.lowercase()

    companion object {
        fun fromWireName(value: String): BridgeResult? = entries.firstOrNull { it.wireName == value }
    }
}

data class BridgeDecision(
    val allowed: Boolean,
    val result: BridgeResult,
    val reason: String,
) {
    companion object {
        fun unavailable(reason: String) = BridgeDecision(false, BridgeResult.UNAVAILABLE, reason)
        fun unsupported(reason: String) = BridgeDecision(false, BridgeResult.UNSUPPORTED, reason)
        fun denied(reason: String) = BridgeDecision(false, BridgeResult.DENIED, reason)
        fun allowed(reason: String) = BridgeDecision(true, BridgeResult.ALLOWED, reason)
    }
}

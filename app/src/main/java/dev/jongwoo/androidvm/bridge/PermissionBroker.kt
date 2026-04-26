package dev.jongwoo.androidvm.bridge

class PermissionBroker(private val policy: BridgePolicy) {
    fun allows(kind: BridgeKind): Boolean = when (kind) {
        BridgeKind.AUDIO_OUTPUT -> policy.audioOutput
        BridgeKind.CLIPBOARD -> policy.clipboard
        BridgeKind.CONTACTS -> policy.contacts
        BridgeKind.FILES -> policy.files
        BridgeKind.LOCATION -> policy.location
        BridgeKind.MICROPHONE -> policy.microphone
        BridgeKind.VIBRATION -> policy.vibration
    }

    fun require(kind: BridgeKind, detail: String): Boolean {
        val allowed = allows(kind)
        BridgeAuditLog.append(kind, if (allowed) "allowed $detail" else "blocked $detail")
        return allowed
    }
}

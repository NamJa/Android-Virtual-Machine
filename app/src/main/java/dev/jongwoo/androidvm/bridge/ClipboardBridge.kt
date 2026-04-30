package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

interface HostClipboard {
    fun getPlainText(): String?
    fun setPlainText(text: String)
}

class InMemoryHostClipboard : HostClipboard {
    private var contents: String? = null

    val lastWrittenText: String?
        get() = contents

    override fun getPlainText(): String? = contents
    override fun setPlainText(text: String) {
        contents = text
    }
}

/**
 * Clipboard bridge that enforces direction (host->guest, guest->host, bidirectional) and a hard
 * size cap. Plain text only — Stage 07 MVP refuses non-text MIME types.
 */
class ClipboardBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val hostClipboard: HostClipboard,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val onChangedThrottle: ChangeListenerThrottle =
        ChangeListenerThrottle(ChangeListenerThrottle.CLIPBOARD_DEFAULT_MS),
) {
    /**
     * Phase D.4: invoked when the host's [android.content.ClipboardManager.OnPrimaryClipChangedListener]
     * fires. Returns true if the listener should propagate the change to the guest, false if the
     * throttle decided to drop this signal. Off / non-bidirectional modes also drop without
     * triggering the throttle.
     */
    fun onHostClipboardChanged(): Boolean {
        val policy = policyStore.load().getValue(BridgeType.CLIPBOARD)
        if (!policy.enabled) return false
        if (policy.mode !in HOST_TO_GUEST_MODES) return false
        return onChangedThrottle.accept()
    }

    fun hostToGuest(instanceId: String): BridgeResponse {
        val policy = policyStore.load().getValue(BridgeType.CLIPBOARD)
        if (!policy.enabled || policy.mode !in HOST_TO_GUEST_MODES) {
            return audit(instanceId, "host_to_guest",
                BridgeResponse(BridgeResult.UNAVAILABLE, "clipboard_host_to_guest_disabled"))
        }
        val text = hostClipboard.getPlainText()
            ?: return audit(instanceId, "host_to_guest",
                BridgeResponse(BridgeResult.UNAVAILABLE, "clipboard_empty_or_non_text"))
        if (text.toByteArray(Charsets.UTF_8).size > maxBytes) {
            return audit(instanceId, "host_to_guest",
                BridgeResponse(BridgeResult.DENIED, "clipboard_too_large"))
        }
        val payload = JSONObject().put("text", text).toString()
        return audit(
            instanceId,
            "host_to_guest",
            BridgeResponse(BridgeResult.ALLOWED, "clipboard_delivered", payload),
        )
    }

    fun guestToHost(instanceId: String, text: String): BridgeDecision {
        val policy = policyStore.load().getValue(BridgeType.CLIPBOARD)
        val decision = when {
            !policy.enabled || policy.mode !in GUEST_TO_HOST_MODES ->
                BridgeDecision.unavailable("clipboard_guest_to_host_disabled")
            text.toByteArray(Charsets.UTF_8).size > maxBytes ->
                BridgeDecision.denied("clipboard_too_large")
            else -> {
                hostClipboard.setPlainText(text)
                BridgeDecision.allowed("clipboard_written")
            }
        }
        auditLog.appendDecision(instanceId, BridgeType.CLIPBOARD, "guest_to_host", decision)
        return decision
    }

    private fun audit(instanceId: String, operation: String, response: BridgeResponse): BridgeResponse {
        auditLog.appendDecision(
            instanceId,
            BridgeType.CLIPBOARD,
            operation,
            BridgeDecision(response.result == BridgeResult.ALLOWED, response.result, response.reason),
        )
        return response
    }

    companion object {
        const val DEFAULT_MAX_BYTES = 16 * 1024
        private val HOST_TO_GUEST_MODES = setOf(
            BridgeMode.CLIPBOARD_HOST_TO_GUEST,
            BridgeMode.CLIPBOARD_BIDIRECTIONAL,
        )
        private val GUEST_TO_HOST_MODES = setOf(
            BridgeMode.CLIPBOARD_GUEST_TO_HOST,
            BridgeMode.CLIPBOARD_BIDIRECTIONAL,
        )
    }
}

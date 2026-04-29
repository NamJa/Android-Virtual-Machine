package dev.jongwoo.androidvm.vm

import android.os.Bundle
import org.json.JSONObject

/**
 * Wire contract for messages exchanged between the default process ([VmManagerService]) and the
 * `:vm1` process ([VmInstanceService]). The contract is intentionally narrow: every payload is a
 * single JSON string carried in [VmIpc.KEY_PAYLOAD_JSON] alongside [VmIpc.KEY_INSTANCE_ID]. Adding
 * a new cross-process message means adding a new `MSG_*` constant here, a new payload codec in
 * [VmIpcCodec], and a unit test in `VmIpcContractTest` to lock down the encoding.
 *
 * No raw bridge payload (clipboard text, location coordinates, package contents) is ever sent over
 * this contract — only structural state. See `docs/planning/phase-a-host-shell.md` § A.3.
 */
object VmIpc {
    // ---- Inbound to :vm1 (sent by the default process) ----
    /** payload: empty bundle; sender's `replyTo` Messenger is captured for state pushes. */
    const val MSG_REGISTER_REPLY = 0x01
    /** payload: empty bundle; the matching Messenger is dropped from the broadcast set. */
    const val MSG_UNREGISTER_REPLY = 0x02

    // ---- Outbound from :vm1 (broadcast to every registered reply Messenger) ----
    const val MSG_STATE_UPDATE = 0x11
    const val MSG_BOOTSTRAP_STATUS = 0x12
    const val MSG_BRIDGE_STATUS = 0x20
    const val MSG_PACKAGE_STATUS = 0x30
    const val MSG_GENERIC_LOG = 0x40

    const val KEY_INSTANCE_ID = "instanceId"
    const val KEY_PAYLOAD_JSON = "payloadJson"

    fun bundle(instanceId: String, payloadJson: String): Bundle = Bundle().apply {
        putString(KEY_INSTANCE_ID, instanceId)
        putString(KEY_PAYLOAD_JSON, payloadJson)
    }
}

/**
 * Pure-JVM JSON codec for [VmIpc] payloads. Lives outside [VmIpc] so it can be exercised from
 * unit tests without an Android `Bundle`.
 */
object VmIpcCodec {
    fun encodeState(instanceId: String, state: VmState): String = JSONObject()
        .put("instanceId", instanceId)
        .put("state", state.name)
        .toString()

    fun decodeState(json: String): Pair<String, VmState>? {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val id = obj.optString("instanceId").ifBlank { return null }
        val name = obj.optString("state").ifBlank { return null }
        val state = runCatching { VmState.valueOf(name) }.getOrNull() ?: return null
        return id to state
    }
}

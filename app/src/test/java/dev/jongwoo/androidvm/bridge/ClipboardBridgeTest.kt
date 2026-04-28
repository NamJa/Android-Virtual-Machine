package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun offModeBlocksBothDirections() {
        val (bridge, _, hostClipboard, audit) = newBridge("clip-off")
        // default policy is OFF
        hostClipboard.setPlainText("host secret")

        val htg = bridge.hostToGuest("vm1")
        val gth = bridge.guestToHost("vm1", "guest secret")

        assertEquals(BridgeResult.UNAVAILABLE, htg.result)
        assertEquals(BridgeResult.UNAVAILABLE, gth.result)
        assertEquals("host secret", hostClipboard.lastWrittenText)
        assertEquals(2, audit.read().size)
    }

    @Test
    fun hostToGuestModeAllowsHostToGuestOnly() {
        val (bridge, store, hostClipboard, _) = newBridge("clip-h2g")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_HOST_TO_GUEST, enabled = true)
        }
        hostClipboard.setPlainText("from host")

        val htg = bridge.hostToGuest("vm1")
        val gth = bridge.guestToHost("vm1", "from guest")

        assertEquals(BridgeResult.ALLOWED, htg.result)
        assertEquals("from host", JSONObject(htg.payloadJson).getString("text"))
        assertEquals(BridgeResult.UNAVAILABLE, gth.result)
        // host clipboard remains unchanged
        assertEquals("from host", hostClipboard.lastWrittenText)
    }

    @Test
    fun guestToHostModeAllowsGuestToHostOnly() {
        val (bridge, store, hostClipboard, _) = newBridge("clip-g2h")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_GUEST_TO_HOST, enabled = true)
        }
        hostClipboard.setPlainText("from host")

        val htg = bridge.hostToGuest("vm1")
        val gth = bridge.guestToHost("vm1", "from guest")

        assertEquals(BridgeResult.UNAVAILABLE, htg.result)
        assertEquals(BridgeResult.ALLOWED, gth.result)
        assertEquals("from guest", hostClipboard.lastWrittenText)
    }

    @Test
    fun bidirectionalModeAllowsBothDirections() {
        val (bridge, store, hostClipboard, _) = newBridge("clip-bi")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }
        hostClipboard.setPlainText("from host")

        val htg = bridge.hostToGuest("vm1")
        val gth = bridge.guestToHost("vm1", "from guest")

        assertEquals(BridgeResult.ALLOWED, htg.result)
        assertEquals(BridgeResult.ALLOWED, gth.result)
        assertEquals("from guest", hostClipboard.lastWrittenText)
    }

    @Test
    fun emptyHostClipboardReturnsUnavailable() {
        val (bridge, store, _, _) = newBridge("clip-empty")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }

        val response = bridge.hostToGuest("vm1")

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("clipboard_empty_or_non_text", response.reason)
    }

    @Test
    fun oversizedPayloadIsDeniedInBothDirections() {
        val (bridge, store, hostClipboard, audit) = newBridge("clip-large")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }
        val big = "a".repeat(ClipboardBridge.DEFAULT_MAX_BYTES + 1)
        hostClipboard.setPlainText(big)

        val htg = bridge.hostToGuest("vm1")
        val gth = bridge.guestToHost("vm1", big)

        assertEquals(BridgeResult.DENIED, htg.result)
        assertEquals(BridgeResult.DENIED, gth.result)
        assertEquals("clipboard_too_large", htg.reason)
        // audit log must not contain the payload itself
        val raw = audit.logFile.readText()
        assertFalse(raw.contains(big))
    }

    @Test
    fun guestToHostModeDoesNotAllowHostToGuestRead() {
        val (bridge, store, _, _) = newBridge("clip-asymmetric")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_GUEST_TO_HOST, enabled = true)
        }

        val response = bridge.hostToGuest("vm1")

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("clipboard_host_to_guest_disabled", response.reason)
    }

    @Test
    fun hostToGuestModeDoesNotAllowGuestToHostWrite() {
        val (bridge, store, hostClipboard, _) = newBridge("clip-asymmetric-2")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_HOST_TO_GUEST, enabled = true)
        }

        val decision = bridge.guestToHost("vm1", "guest text")

        assertEquals(BridgeResult.UNAVAILABLE, decision.result)
        assertNull(hostClipboard.lastWrittenText)
    }

    @Test
    fun auditDoesNotPersistClipboardBody() {
        val (bridge, store, hostClipboard, audit) = newBridge("clip-redact")
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }
        hostClipboard.setPlainText("topsecret-host-text")

        bridge.hostToGuest("vm1")
        bridge.guestToHost("vm1", "guest-secret-payload")

        val raw = audit.logFile.readText()
        assertFalse(raw.contains("topsecret-host-text"))
        assertFalse(raw.contains("guest-secret-payload"))
        assertTrue(raw.contains("clipboard_delivered"))
        assertTrue(raw.contains("clipboard_written"))
    }

    private data class TestRig(
        val bridge: ClipboardBridge,
        val store: BridgePolicyStore,
        val hostClipboard: InMemoryHostClipboard,
        val audit: BridgeAuditLog,
    )

    private fun newBridge(prefix: String): TestRig {
        val root = tempDir(prefix)
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val hostClipboard = InMemoryHostClipboard()
        val bridge = ClipboardBridge(store, audit, hostClipboard)
        return TestRig(bridge, store, hostClipboard, audit)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

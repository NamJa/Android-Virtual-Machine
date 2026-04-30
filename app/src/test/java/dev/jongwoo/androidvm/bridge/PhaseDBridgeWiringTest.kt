package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhaseDBridgeWiringTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun changeListenerThrottle_dropsCloseSignals() {
        var now = 0L
        val throttle = ChangeListenerThrottle(intervalMillis = 100, clock = { now })
        assertTrue(throttle.accept())
        now = 50
        assertFalse(throttle.accept())
        now = 99
        assertFalse(throttle.accept())
        assertEquals(2, throttle.droppedSinceLastAcceptForTest())
        now = 200
        assertTrue(throttle.accept())
        assertEquals(0, throttle.droppedSinceLastAcceptForTest())
    }

    @Test
    fun audioOutputBridge_recordsXrunsFromSink() {
        val root = Files.createTempDirectory("xrun").toFile().also { tempDirs += it }
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val sink = FlakySink()
        val bridge = AudioOutputBridge(store, audit, sink)
        store.update(BridgeType.AUDIO_OUTPUT) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        bridge.writePcm("vm1", ShortArray(4)) // 1st: underrun
        bridge.writePcm("vm1", ShortArray(4)) // 2nd: clean
        bridge.writePcm("vm1", ShortArray(4)) // 3rd: underrun
        val snap = bridge.xrunSnapshot()
        assertEquals(2L, snap.total)
        assertTrue(snap.lastUnderrun)
    }

    @Test
    fun clipboardBridge_throttlesRapidHostChanges() {
        val root = Files.createTempDirectory("clip").toFile().also { tempDirs += it }
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val host = InMemoryHostClipboard()
        var now = 0L
        val bridge = ClipboardBridge(
            store, audit, host,
            onChangedThrottle = ChangeListenerThrottle(intervalMillis = 100, clock = { now }),
        )
        // Off → returns false without touching throttle.
        assertFalse(bridge.onHostClipboardChanged())
        store.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }
        host.setPlainText("hello")
        assertTrue(bridge.onHostClipboardChanged())
        now = 50
        assertFalse(bridge.onHostClipboardChanged())
        now = 200
        assertTrue(bridge.onHostClipboardChanged())
    }

    @Test
    fun networkSyscallGate_followsModeAndVpnAttachState() {
        assertEquals(
            NetworkSyscallGate.SyscallDecision.ENETUNREACH,
            NetworkSyscallGate.decide(NetworkEgressMode.DISABLED),
        )
        assertEquals(
            NetworkSyscallGate.SyscallDecision.ALLOW,
            NetworkSyscallGate.decide(NetworkEgressMode.HOST_NAT),
        )
        assertEquals(
            NetworkSyscallGate.SyscallDecision.EACCES,
            NetworkSyscallGate.decide(NetworkEgressMode.VPN_ISOLATED, vpnAttached = false),
        )
        assertEquals(
            NetworkSyscallGate.SyscallDecision.ALLOW,
            NetworkSyscallGate.decide(NetworkEgressMode.VPN_ISOLATED, vpnAttached = true),
        )
        assertEquals(
            NetworkSyscallGate.SyscallDecision.ALLOW,
            NetworkSyscallGate.decide(NetworkEgressMode.SOCKS5),
        )
    }

    @Test
    fun networkEgressModeWireRoundTrip() {
        NetworkEgressMode.entries.forEach { mode ->
            assertNotNull(NetworkEgressMode.fromWireName(mode.wireName))
        }
    }

    private class FlakySink : AudioSink {
        private var counter = 0
        private var lastUnderrun = false
        override fun write(pcm: ShortArray): Int {
            // Simulate underrun on every write whose index is even.
            counter++
            lastUnderrun = counter % 2 == 1 // 1st and 3rd writes underrun
            return pcm.size
        }
        override fun lastWriteUnderran(): Boolean = lastUnderrun
    }
}

package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputAndNetworkBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private class RecordingAudioSink : AudioSink {
        var totalFrames = 0
        var writeCount = 0
        override fun write(pcm: ShortArray): Int {
            writeCount++
            totalFrames += pcm.size
            return pcm.size
        }
    }

    @Test
    fun audioOutputDisabledReturnsUnavailableAndDoesNotCallSink() {
        val (store, audit) = newStores("audio-off")
        store.update(BridgeType.AUDIO_OUTPUT) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        val sink = RecordingAudioSink()
        val bridge = AudioOutputBridge(store, audit, sink)

        val decision = bridge.writePcm("vm1", ShortArray(128) { 1 })

        assertEquals(BridgeResult.UNAVAILABLE, decision.result)
        assertEquals("audio_output_disabled", decision.reason)
        assertEquals(0, sink.writeCount)
        assertEquals("audio_output_disabled", audit.read().single().reason)
    }

    @Test
    fun audioOutputMutedDoesNotCallSinkButReportsAllowed() {
        val (store, audit) = newStores("audio-muted")
        val sink = RecordingAudioSink()
        val bridge = AudioOutputBridge(store, audit, sink)

        val decision = bridge.writePcm("vm1", ShortArray(64) { 1 }, muted = true)

        assertEquals(BridgeResult.ALLOWED, decision.result)
        assertEquals("audio_output_muted", decision.reason)
        assertEquals(0, sink.writeCount)
    }

    @Test
    fun audioOutputEnabledWritesToSink() {
        val (store, audit) = newStores("audio-on")
        val sink = RecordingAudioSink()
        val bridge = AudioOutputBridge(store, audit, sink)

        val decision = bridge.writePcm("vm1", ShortArray(32) { 7 })

        assertEquals(BridgeResult.ALLOWED, decision.result)
        assertEquals(1, sink.writeCount)
        assertEquals(32, sink.totalFrames)
        assertEquals("audio_output_written", decision.reason)
    }

    @Test
    fun vibrationDisabledDoesNotCallVibrator() {
        val (store, audit) = newStores("vib-off")
        store.update(BridgeType.VIBRATION) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        val vibrator = NoopHostVibrator()
        val bridge = VibrationBridge(store, audit, vibrator)

        val decision = bridge.vibrate("vm1", 200L)

        assertEquals(BridgeResult.UNAVAILABLE, decision.result)
        assertNull(vibrator.lastDurationMs)
    }

    @Test
    fun vibrationDurationIsCappedAtMax() {
        val (store, audit) = newStores("vib-cap")
        val vibrator = NoopHostVibrator()
        val bridge = VibrationBridge(store, audit, vibrator, maxDurationMs = 250L)

        bridge.vibrate("vm1", 5000L)

        assertEquals(250L, vibrator.lastDurationMs)
    }

    @Test
    fun vibrationDurationIsRaisedToMin() {
        val (store, audit) = newStores("vib-min")
        val vibrator = NoopHostVibrator()
        val bridge = VibrationBridge(store, audit, vibrator, minDurationMs = 5L)

        bridge.vibrate("vm1", 1L)

        assertEquals(5L, vibrator.lastDurationMs)
    }

    @Test
    fun vibrationRejectsZeroAndNegativeDurations() {
        val (store, audit) = newStores("vib-zero")
        val vibrator = NoopHostVibrator()
        val bridge = VibrationBridge(store, audit, vibrator)

        val zero = bridge.vibrate("vm1", 0L)
        val negative = bridge.vibrate("vm1", -10L)

        assertEquals(BridgeResult.DENIED, zero.result)
        assertEquals(BridgeResult.DENIED, negative.result)
        assertEquals("vibration_invalid_duration", zero.reason)
        assertNull(vibrator.lastDurationMs)
    }

    @Test
    fun vibrationAuditLogsDecision() {
        val (store, audit) = newStores("vib-audit")
        val bridge = VibrationBridge(store, audit, NoopHostVibrator())

        bridge.vibrate("vm1", 100L)

        val entry = audit.read().single()
        assertEquals(BridgeType.VIBRATION, entry.bridge)
        assertEquals("vibration_started", entry.reason)
    }

    @Test
    fun networkDisabledReturnsUnavailable() = runTest {
        val (store, audit) = newStores("net-off")
        store.update(BridgeType.NETWORK) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        val handler = NetworkBridge(store, audit)

        val response = handler.handle(networkRequest())

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("network_disabled", response.reason)
        assertEquals("network_disabled", audit.read().single().reason)
    }

    @Test
    fun networkEnabledReturnsAllowed() = runTest {
        val (store, audit) = newStores("net-on")
        val handler = NetworkBridge(store, audit)

        val response = handler.handle(networkRequest())

        assertEquals(BridgeResult.ALLOWED, response.result)
        assertEquals("network_enabled", response.reason)
        assertTrue(audit.read().single().allowed)
    }

    @Test
    fun networkBridgeRejectsForeignBridgeRequests() = runTest {
        val (store, audit) = newStores("net-mismatch")
        val handler = NetworkBridge(store, audit)
        val mismatched = networkRequest().copy(bridge = BridgeType.AUDIO_OUTPUT)

        val thrown = runCatching { handler.handle(mismatched) }.exceptionOrNull()

        assertFalse(thrown == null)
        assertTrue(thrown is IllegalArgumentException)
    }

    private fun networkRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.NETWORK,
        operation = "connect",
        reason = PermissionReason(BridgeType.NETWORK, "connect", "", "Network"),
    )

    private fun newStores(prefix: String): Pair<BridgePolicyStore, BridgeAuditLog> {
        val root = tempDir(prefix)
        return BridgePolicyStore(root) to BridgeAuditLog(root)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

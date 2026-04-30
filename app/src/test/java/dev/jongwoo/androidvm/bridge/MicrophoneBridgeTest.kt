package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun sampleRateConverter_downsamples48kTo16kRoughlyOneThird() {
        val input = ShortArray(300) { (it * 100).toShort() }
        val out = SampleRateConverter.resample(input, 48_000, 16_000)
        assertEquals(100, out.size)
    }

    @Test
    fun sampleRateConverter_passthroughOnSameRate() {
        val input = shortArrayOf(1, 2, 3, 4)
        val out = SampleRateConverter.resample(input, 16_000, 16_000)
        assertTrue(input.contentEquals(out))
        // Must return a fresh copy, not the same reference.
        assertFalse(input === out)
    }

    @Test
    fun microphoneBridge_offReturnsUnavailableAndDoesNotPrompt() = runTest {
        val (bridge, gateway, _) = newBridge()
        val response = bridge.handle(micRequest())
        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun microphoneBridge_unsupportedModeReturnsUnsupported() = runTest {
        val (bridge, gateway, store) = newBridge()
        store.update(BridgeType.MICROPHONE) {
            it.copy(mode = BridgeMode.UNSUPPORTED, enabled = true)
        }
        val response = bridge.handle(micRequest())
        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun microphoneBridge_enabledRequestsRecordAudioOnUse() = runTest {
        val (bridge, gateway, store) = newBridge()
        store.update(BridgeType.MICROPHONE) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        gateway.nextResult = false

        val denied = bridge.handle(micRequest())
        assertEquals(BridgeResult.DENIED, denied.result)
        assertEquals(1, gateway.requests.size)
        assertEquals("android.permission.RECORD_AUDIO", gateway.requests.single().permission)
    }

    @Test
    fun microphoneBridge_deliversResampledPcmWhenPermissionGranted() = runTest {
        val (bridge, gateway, store) = newBridge(
            source = FixedPcmSource(ShortArray(2_400) { it.toShort() }, sampleRateHz = 48_000),
        )
        store.update(BridgeType.MICROPHONE) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        gateway.nextResult = true

        val response = bridge.handle(
            BridgeRequest(
                instanceId = "vm1",
                bridge = BridgeType.MICROPHONE,
                operation = "open",
                reason = PermissionReason(BridgeType.MICROPHONE, "open", "android.permission.RECORD_AUDIO", "STT"),
                payloadJson = JSONObject().put("frames", 1500).toString(),
            ),
        )
        assertEquals(BridgeResult.ALLOWED, response.result)
        val payload = JSONObject(response.payloadJson)
        assertEquals(1500, payload.getInt("hostFrames"))
        assertEquals(48_000, payload.getInt("sampleRateIn"))
        assertEquals(16_000, payload.getInt("sampleRateOut"))
        // 1500 frames @ 48 kHz → 500 frames @ 16 kHz.
        assertEquals(500, payload.getInt("guestFrames"))
        assertEquals(500, bridge.lastReadFrames)
    }

    @Test
    fun microphoneBridge_auditDoesNotLeakRawSamples() = runTest {
        val root = tempDir("mic-audit")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway().apply { nextResult = true }
        val bridge = MicrophoneBridge(
            store, audit, gateway,
            FixedPcmSource(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)),
        )
        store.update(BridgeType.MICROPHONE) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        bridge.handle(micRequest())
        val text = audit.logFile.readText()
        assertFalse(text.contains("hostFrames"))
        assertTrue(text.contains("microphone_frames_delivered"))
    }

    private fun micRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.MICROPHONE,
        operation = "open",
        reason = PermissionReason(BridgeType.MICROPHONE, "open", "android.permission.RECORD_AUDIO", "test"),
    )

    private fun newBridge(
        source: AudioInputSource = FixedPcmSource(ShortArray(MicrophoneBridge.DEFAULT_FRAME_COUNT)),
    ): Triple<MicrophoneBridge, RecordingPermissionGateway, BridgePolicyStore> {
        val root = tempDir("mic")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway()
        val bridge = MicrophoneBridge(store, audit, gateway, source)
        return Triple(bridge, gateway, store)
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

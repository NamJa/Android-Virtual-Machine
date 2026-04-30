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

class CameraBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun cameraBridge_offBridgeReturnsUnavailableAndDoesNotRequestPermission() = runTest {
        val (bridge, gateway, _, source) = newBridge()
        val response = bridge.handle(cameraRequest())
        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertTrue(gateway.requests.isEmpty())
        assertEquals(0L, source.pushedFrames())
    }

    @Test
    fun cameraBridge_unsupportedReturnsUnsupportedAndSkipsGateway() = runTest {
        val (bridge, gateway, store, _) = newBridge()
        // Default policy is UNSUPPORTED + disabled.
        val response = bridge.handle(cameraRequest())
        // The bridge first checks `enabled || OFF` — default is enabled=false so UNAVAILABLE wins.
        // To test the explicit UNSUPPORTED path we keep enabled=true but mode UNSUPPORTED.
        assertEquals(BridgeResult.UNAVAILABLE, response.result)

        store.update(BridgeType.CAMERA) {
            it.copy(mode = BridgeMode.UNSUPPORTED, enabled = true)
        }
        val r2 = bridge.handle(cameraRequest())
        assertEquals(BridgeResult.UNSUPPORTED, r2.result)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun cameraBridge_enabledRequestsCameraPermissionOnUse() = runTest {
        val (bridge, gateway, store, _) = newBridge()
        store.update(BridgeType.CAMERA) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        gateway.nextResult = false

        val denied = bridge.handle(cameraRequest())
        assertEquals(BridgeResult.DENIED, denied.result)
        assertEquals(1, gateway.requests.size)
        assertEquals("android.permission.CAMERA", gateway.requests.single().permission)
    }

    @Test
    fun cameraBridge_enabledDeliversYuvFrameWhenPermissionGranted() = runTest {
        val (bridge, gateway, store, source) = newBridge()
        store.update(BridgeType.CAMERA) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        gateway.nextResult = true

        val ok = bridge.handle(cameraRequest())
        assertEquals(BridgeResult.ALLOWED, ok.result)
        val payload = JSONObject(ok.payloadJson)
        assertEquals("YUV_420_888", payload.getString("format"))
        assertEquals(1L, source.pushedFrames())
    }

    @Test
    fun cameraBridge_enabledNoFrameReturnsUnavailable() = runTest {
        val root = tempDir("camera-noframe")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway().apply { nextResult = true }
        val bridge = CameraBridge(store, audit, gateway, FixedCameraSource(frame = null))
        store.update(BridgeType.CAMERA) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }

        val response = bridge.handle(cameraRequest())
        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("camera_no_frame", response.reason)
    }

    @Test
    fun cameraBridge_auditDoesNotLeakRawPayload() = runTest {
        val root = tempDir("camera-audit")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway().apply { nextResult = true }
        val bridge = CameraBridge(store, audit, gateway, FixedCameraSource())
        store.update(BridgeType.CAMERA) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
        bridge.handle(cameraRequest())
        val log = audit.logFile.readText()
        assertFalse("audit must not include raw bytes or 'ySize'", log.contains("ySize"))
        assertTrue(log.contains("camera_frame_delivered"))
    }

    private fun cameraRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.CAMERA,
        operation = "open",
        reason = PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", "preview"),
    )

    private fun newBridge(): BridgeFixtures {
        val root = tempDir("camera")
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway()
        val source = FixedCameraSource()
        val bridge = CameraBridge(store, audit, gateway, source)
        return BridgeFixtures(bridge, gateway, store, source)
    }

    private data class BridgeFixtures(
        val bridge: CameraBridge,
        val gateway: RecordingPermissionGateway,
        val store: BridgePolicyStore,
        val source: FixedCameraSource,
    )

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

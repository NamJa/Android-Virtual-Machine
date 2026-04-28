package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsupportedMediaBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun cameraHandlerReturnsUnsupportedAndLogsAudit() = runTest {
        val rig = newRig(BridgeType.CAMERA)

        val response = rig.handler.handle(cameraOpenRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertEquals("camera_unsupported_stage7_mvp", response.reason)
        val entry = rig.audit.read().single()
        assertEquals(BridgeType.CAMERA, entry.bridge)
        assertEquals(BridgeResult.UNSUPPORTED, entry.result)
    }

    @Test
    fun microphoneHandlerReturnsUnsupportedAndLogsAudit() = runTest {
        val rig = newRig(BridgeType.MICROPHONE)

        val response = rig.handler.handle(micOpenRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertEquals("microphone_unsupported_stage7_mvp", response.reason)
        assertEquals(1, rig.audit.read().size)
    }

    @Test
    fun cameraDispatchedThroughDispatcherDoesNotPromptForCameraPermission() = runTest {
        val rig = newRig(BridgeType.CAMERA)
        val gateway = RecordingPermissionGateway()
        val dispatcher = BridgeDispatcher(
            broker = DefaultPermissionBroker({ rig.store }, gateway),
            auditLogFor = { rig.audit },
            handlers = mapOf(BridgeType.CAMERA to rig.handler),
        )

        val response = dispatcher.dispatch(cameraOpenRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertTrue(gateway.requests.none { it.permission == "android.permission.CAMERA" })
    }

    @Test
    fun microphoneDispatchedThroughDispatcherDoesNotPromptForRecordAudio() = runTest {
        val rig = newRig(BridgeType.MICROPHONE)
        val gateway = RecordingPermissionGateway()
        val dispatcher = BridgeDispatcher(
            broker = DefaultPermissionBroker({ rig.store }, gateway),
            auditLogFor = { rig.audit },
            handlers = mapOf(BridgeType.MICROPHONE to rig.handler),
        )

        val response = dispatcher.dispatch(micOpenRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertTrue(gateway.requests.none { it.permission == "android.permission.RECORD_AUDIO" })
    }

    @Test
    fun handlerRejectsForeignBridgeType() {
        val audit = BridgeAuditLog(tempDir("media-mismatch"))
        val thrown = runCatching {
            UnsupportedMediaBridge(BridgeType.AUDIO_OUTPUT, audit)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun handlerRejectsRequestForOtherBridge() = runTest {
        val rig = newRig(BridgeType.CAMERA)
        val mismatched = micOpenRequest()
        val thrown = runCatching { rig.handler.handle(mismatched) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    private data class Rig(
        val handler: UnsupportedMediaBridge,
        val store: BridgePolicyStore,
        val audit: BridgeAuditLog,
    )

    private fun newRig(bridge: BridgeType): Rig {
        val root = tempDir("unsupported-${bridge.name}")
        val audit = BridgeAuditLog(root)
        val store = BridgePolicyStore(root)
        return Rig(UnsupportedMediaBridge(bridge, audit), store, audit)
    }

    private fun cameraOpenRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.CAMERA,
        operation = "open",
        reason = PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", "Camera"),
    )

    private fun micOpenRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.MICROPHONE,
        operation = "open",
        reason = PermissionReason(BridgeType.MICROPHONE, "open", "android.permission.RECORD_AUDIO", "Microphone"),
    )

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

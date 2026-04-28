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

class PermissionBrokerTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun unsupportedCameraReturnsUnsupportedAndNeverPromptsForPermission() = runTest {
        val (broker, gateway, _) = newBroker()

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.CAMERA,
            operation = "open",
            reason = cameraReason(),
        )

        assertEquals(BridgeResult.UNSUPPORTED, decision.result)
        assertFalse(decision.allowed)
        assertEquals("bridge_unsupported", decision.reason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun unsupportedMicrophoneReturnsUnsupportedAndDoesNotPromptRecordAudio() = runTest {
        val (broker, gateway, _) = newBroker()

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.MICROPHONE,
            operation = "open",
            reason = micReason(),
        )

        assertEquals(BridgeResult.UNSUPPORTED, decision.result)
        assertTrue(gateway.requests.none { it.permission == "android.permission.RECORD_AUDIO" })
    }

    @Test
    fun offClipboardReturnsUnavailableWithoutTouchingHostApi() = runTest {
        val (broker, gateway, _) = newBroker()

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.CLIPBOARD,
            operation = "host_to_guest",
            reason = clipboardReason(),
        )

        assertEquals(BridgeResult.UNAVAILABLE, decision.result)
        assertEquals("bridge_disabled", decision.reason)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun fixedLocationModeAllowedWithoutPermissionPrompt() = runTest {
        val (broker, gateway, store) = newBroker()
        store.update(BridgeType.LOCATION) {
            it.copy(
                mode = BridgeMode.LOCATION_FIXED,
                enabled = true,
                options = mapOf("latitude" to "37.0", "longitude" to "127.0"),
            )
        }

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.LOCATION,
            operation = "request_current_location",
            reason = locationReason("android.permission.ACCESS_FINE_LOCATION"),
        )

        assertEquals(BridgeResult.ALLOWED, decision.result)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun realLocationModePromptsForFineLocationAndDeniesWhenRefused() = runTest {
        val (broker, gateway, store) = newBroker()
        store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        gateway.nextResult = false

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.LOCATION,
            operation = "request_current_location",
            reason = locationReason("android.permission.ACCESS_FINE_LOCATION"),
        )

        assertEquals(BridgeResult.DENIED, decision.result)
        assertEquals("permission_denied", decision.reason)
        assertEquals(1, gateway.requests.size)
        assertEquals("android.permission.ACCESS_FINE_LOCATION", gateway.requests.single().permission)
    }

    @Test
    fun realLocationModeAllowedWhenPermissionGranted() = runTest {
        val (broker, gateway, store) = newBroker()
        store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        gateway.nextResult = true

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.LOCATION,
            operation = "request_current_location",
            reason = locationReason("android.permission.ACCESS_FINE_LOCATION"),
        )

        assertEquals(BridgeResult.ALLOWED, decision.result)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun audioOutputBridgeAllowedWithoutAnyPermissionPrompt() = runTest {
        val (broker, gateway, _) = newBroker()

        val decision = broker.decide(
            instanceId = INSTANCE,
            bridge = BridgeType.AUDIO_OUTPUT,
            operation = "write_pcm",
            reason = PermissionReason(BridgeType.AUDIO_OUTPUT, "write_pcm", "", "Audio output"),
        )

        assertEquals(BridgeResult.ALLOWED, decision.result)
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun setBridgePolicyForcesUnsupportedScopeBridgesToRemainUnsupported() {
        val (broker, _, store) = newBroker()

        broker.setBridgePolicy(INSTANCE, BridgeType.CAMERA, BridgeMode.ENABLED)
        val cameraPolicy = store.load().getValue(BridgeType.CAMERA)

        assertEquals(BridgeMode.UNSUPPORTED, cameraPolicy.mode)
        assertFalse(cameraPolicy.enabled)
    }

    @Test
    fun setBridgePolicyEnabledModeFlipsEnabledTrue() {
        val (broker, _, store) = newBroker()

        broker.setBridgePolicy(INSTANCE, BridgeType.LOCATION, BridgeMode.LOCATION_FIXED)

        val location = store.load().getValue(BridgeType.LOCATION)
        assertEquals(BridgeMode.LOCATION_FIXED, location.mode)
        assertTrue(location.enabled)
    }

    @Test
    fun setBridgePolicyOffModeDisablesBridge() {
        val (broker, _, store) = newBroker()
        broker.setBridgePolicy(INSTANCE, BridgeType.NETWORK, BridgeMode.OFF)

        val network = store.load().getValue(BridgeType.NETWORK)
        assertEquals(BridgeMode.OFF, network.mode)
        assertFalse(network.enabled)
    }

    @Test
    fun isBridgeEnabledMatchesEffectiveMode() {
        val (broker, _, _) = newBroker()
        assertTrue(broker.isBridgeEnabled(INSTANCE, BridgeType.AUDIO_OUTPUT))
        assertFalse(broker.isBridgeEnabled(INSTANCE, BridgeType.LOCATION))
        assertFalse(broker.isBridgeEnabled(INSTANCE, BridgeType.CAMERA))
    }

    @Test
    fun decideValidatesReasonBridgeMatchesRequestedBridge() = runTest {
        val (broker, _, _) = newBroker()

        val mismatched = PermissionReason(BridgeType.CAMERA, "open", "", "")
        val thrown = runCatching {
            broker.decide(INSTANCE, BridgeType.MICROPHONE, "open", mismatched)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun decideValidatesReasonOperationMatchesRequestedOperation() = runTest {
        val (broker, _, _) = newBroker()

        val mismatched = PermissionReason(BridgeType.CAMERA, "open", "", "")
        val thrown = runCatching {
            broker.decide(INSTANCE, BridgeType.CAMERA, "close", mismatched)
        }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun ensurePermissionDelegatesToGateway() = runTest {
        val (broker, gateway, _) = newBroker()
        gateway.nextResult = true

        val granted = broker.ensurePermission(
            "android.permission.ACCESS_FINE_LOCATION",
            locationReason("android.permission.ACCESS_FINE_LOCATION"),
        )

        assertTrue(granted)
        assertEquals(1, gateway.requests.size)
    }

    @Test
    fun dangerousPermissionForOnlyMapsModesThatNeedHostPrompt() {
        assertEquals(
            "android.permission.ACCESS_FINE_LOCATION",
            dangerousPermissionFor(BridgeType.LOCATION, BridgeMode.LOCATION_HOST_REAL),
        )
        assertNull(dangerousPermissionFor(BridgeType.LOCATION, BridgeMode.LOCATION_FIXED))
        assertNull(dangerousPermissionFor(BridgeType.LOCATION, BridgeMode.OFF))
        assertNull(dangerousPermissionFor(BridgeType.CLIPBOARD, BridgeMode.CLIPBOARD_BIDIRECTIONAL))
        assertNull(dangerousPermissionFor(BridgeType.AUDIO_OUTPUT, BridgeMode.ENABLED))
        assertNull(dangerousPermissionFor(BridgeType.VIBRATION, BridgeMode.ENABLED))
        assertNull(dangerousPermissionFor(BridgeType.NETWORK, BridgeMode.ENABLED))
        assertNull(dangerousPermissionFor(BridgeType.DEVICE_PROFILE, BridgeMode.ENABLED))
    }

    private fun newBroker(): Triple<DefaultPermissionBroker, RecordingPermissionGateway, BridgePolicyStore> {
        val store = BridgePolicyStore(tempDir("broker"))
        val gateway = RecordingPermissionGateway()
        val broker = DefaultPermissionBroker(
            policyStoreFor = { store },
            permissionGateway = gateway,
        )
        return Triple(broker, gateway, store)
    }

    private fun cameraReason() = PermissionReason(
        BridgeType.CAMERA,
        "open",
        "android.permission.CAMERA",
        "Camera bridge",
    )

    private fun micReason() = PermissionReason(
        BridgeType.MICROPHONE,
        "open",
        "android.permission.RECORD_AUDIO",
        "Microphone bridge",
    )

    private fun clipboardReason() = PermissionReason(
        BridgeType.CLIPBOARD,
        "host_to_guest",
        "",
        "Clipboard bridge",
    )

    private fun locationReason(permission: String) = PermissionReason(
        BridgeType.LOCATION,
        "request_current_location",
        permission,
        "Location bridge",
    )

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

    companion object {
        private const val INSTANCE = "vm1"
    }
}

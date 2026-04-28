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

class LocationBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun offModeReturnsUnavailableWithoutPermissionPrompt() = runTest {
        val rig = newBridge("loc-off")
        // default policy is OFF

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("location_disabled", response.reason)
        assertTrue(rig.gateway.requests.isEmpty())
    }

    @Test
    fun fixedModeReturnsConfiguredLocationAndDoesNotPrompt() = runTest {
        val rig = newBridge("loc-fixed")
        rig.store.update(BridgeType.LOCATION) {
            it.copy(
                mode = BridgeMode.LOCATION_FIXED,
                enabled = true,
                options = mapOf(
                    "latitude" to "37.5665",
                    "longitude" to "126.9780",
                    "accuracyMeters" to "25",
                ),
            )
        }

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.ALLOWED, response.result)
        val json = JSONObject(response.payloadJson)
        assertEquals(37.5665, json.getDouble("latitude"), 0.0001)
        assertEquals(126.9780, json.getDouble("longitude"), 0.0001)
        assertEquals(25.0, json.getDouble("accuracyMeters"), 0.0001)
        assertTrue(rig.gateway.requests.isEmpty())
    }

    @Test
    fun fixedModeMissingCoordinatesReturnsUnavailable() = runTest {
        val rig = newBridge("loc-missing")
        rig.store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_FIXED, enabled = true)
        }

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("fixed_location_missing", response.reason)
    }

    @Test
    fun realModeRequestsFineLocationAndDeniesIfRefused() = runTest {
        val rig = newBridge("loc-real-deny")
        rig.store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        rig.gateway.nextResult = false

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.DENIED, response.result)
        assertEquals("location_permission_denied", response.reason)
        assertEquals(1, rig.gateway.requests.size)
        assertEquals("android.permission.ACCESS_FINE_LOCATION", rig.gateway.requests.single().permission)
    }

    @Test
    fun realModeReturnsHostLocationWhenPermissionGranted() = runTest {
        val rig = newBridge(
            prefix = "loc-real-allow",
            hostLocation = GuestLocation(35.0, 129.0, 5f, 1700_000_000L),
        )
        rig.store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        rig.gateway.nextResult = true

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.ALLOWED, response.result)
        val json = JSONObject(response.payloadJson)
        assertEquals(35.0, json.getDouble("latitude"), 0.0001)
        assertEquals(129.0, json.getDouble("longitude"), 0.0001)
    }

    @Test
    fun realModeProviderUnavailableReturnsUnavailable() = runTest {
        val rig = newBridge(prefix = "loc-real-no-provider", hostLocation = null)
        rig.store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        rig.gateway.nextResult = true

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.UNAVAILABLE, response.result)
        assertEquals("location_provider_unavailable", response.reason)
    }

    @Test
    fun unrelatedModeReturnsUnsupported() = runTest {
        val rig = newBridge("loc-unsupported-mode")
        rig.store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.ENABLED, enabled = true)
        }

        val response = rig.bridge.handle(locationRequest())

        assertEquals(BridgeResult.UNSUPPORTED, response.result)
        assertEquals("location_mode_unsupported", response.reason)
    }

    @Test
    fun auditDoesNotPersistRawCoordinatesInReason() = runTest {
        val rig = newBridge(
            prefix = "loc-redact",
            hostLocation = GuestLocation(11.111111, 22.222222, 1f),
        )
        rig.store.update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true)
        }
        rig.gateway.nextResult = true

        rig.bridge.handle(locationRequest())

        val raw = rig.audit.logFile.readText()
        assertTrue(raw.contains("host_location"))
        assertFalse("raw coordinates leaked into audit log: $raw", raw.contains("11.111111"))
        assertFalse(raw.contains("22.222222"))
    }

    @Test
    fun handleRejectsForeignBridge() = runTest {
        val rig = newBridge("loc-mismatch")
        val request = locationRequest().copy(bridge = BridgeType.NETWORK)

        val thrown = runCatching { rig.bridge.handle(request) }.exceptionOrNull()

        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun fixedAccuracyDefaultsTo50WhenAbsent() = runTest {
        val rig = newBridge("loc-default-accuracy")
        rig.store.update(BridgeType.LOCATION) {
            it.copy(
                mode = BridgeMode.LOCATION_FIXED,
                enabled = true,
                options = mapOf("latitude" to "0", "longitude" to "0"),
            )
        }

        val response = rig.bridge.handle(locationRequest())

        val json = JSONObject(response.payloadJson)
        assertEquals(50.0, json.getDouble("accuracyMeters"), 0.0001)
    }

    private data class TestRig(
        val bridge: LocationBridge,
        val store: BridgePolicyStore,
        val audit: BridgeAuditLog,
        val gateway: RecordingPermissionGateway,
    )

    private fun newBridge(
        prefix: String,
        hostLocation: GuestLocation? = GuestLocation(0.0, 0.0, 0f),
    ): TestRig {
        val root = tempDir(prefix)
        val store = BridgePolicyStore(root)
        val audit = BridgeAuditLog(root)
        val gateway = RecordingPermissionGateway()
        val handler = LocationBridge(
            policyStore = store,
            auditLog = audit,
            permissionGateway = gateway,
            hostLocationProvider = FixedHostLocationProvider(hostLocation),
        )
        return TestRig(handler, store, audit, gateway)
    }

    private fun locationRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.LOCATION,
        operation = "request_current_location",
        reason = PermissionReason(
            BridgeType.LOCATION,
            "request_current_location",
            "android.permission.ACCESS_FINE_LOCATION",
            "Location",
        ),
    )

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

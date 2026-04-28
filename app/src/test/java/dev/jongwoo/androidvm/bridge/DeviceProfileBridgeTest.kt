package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfileBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun synthesizedProfileIsStableAcrossRestarts() = runTest {
        val instanceRoot = tempDir("device-stable")
        val audit = BridgeAuditLog(tempDir("device-stable-audit"))

        val first = bridge(instanceRoot, audit).handle(deviceProfileRequest()).asProfile()
        val second = bridge(instanceRoot, audit).handle(deviceProfileRequest()).asProfile()

        assertEquals(first.getString("androidId"), second.getString("androidId"))
    }

    @Test
    fun differentInstancesGetDifferentSyntheticAndroidIds() = runTest {
        val a = tempDir("device-a")
        val b = tempDir("device-b")
        val audit = BridgeAuditLog(tempDir("device-audit"))

        val idA = bridge(a, audit).handle(deviceProfileRequest()).asProfile().getString("androidId")
        val idB = bridge(b, audit).handle(deviceProfileRequest()).asProfile().getString("androidId")

        assertNotEquals(idA, idB)
    }

    @Test
    fun syntheticProfileDoesNotExposeHostIdentity() = runTest {
        val audit = BridgeAuditLog(tempDir("device-identity"))
        val response = bridge(tempDir("device-identity-root"), audit)
            .handle(deviceProfileRequest())

        val payload = response.asProfile()
        assertEquals("", payload.getString("phoneNumber"))
        assertEquals("", payload.getString("imei"))
        assertFalse(payload.has("advertisingId"))
        assertFalse(payload.has("hostInstalledPackages"))
        assertFalse(payload.has("meid"))
        assertFalse(payload.has("simSerialNumber"))
        assertEquals("CleanRoom", payload.getString("manufacturer"))
        assertEquals("VirtualPhone", payload.getString("model"))
        assertEquals("CleanRoom", payload.getString("brand"))
        assertEquals("unknown", payload.getString("serial"))
    }

    @Test
    fun handleAppendsAuditLogEntry() = runTest {
        val auditRoot = tempDir("device-audit-entry")
        val audit = BridgeAuditLog(auditRoot)
        val response = bridge(tempDir("device-audit-root"), audit)
            .handle(deviceProfileRequest())

        assertEquals(BridgeResult.ALLOWED, response.result)
        val entries = audit.read()
        assertEquals(1, entries.size)
        assertEquals(BridgeType.DEVICE_PROFILE, entries.single().bridge)
        assertEquals("synthetic_profile", entries.single().reason)
    }

    @Test
    fun androidIdFileIsCreatedInsideInstanceRoot() = runTest {
        val instanceRoot = tempDir("device-id-file")
        val audit = BridgeAuditLog(tempDir("device-id-file-audit"))

        bridge(instanceRoot, audit).handle(deviceProfileRequest())

        val idFile = File(instanceRoot, DeviceProfileBridge.ANDROID_ID_FILE_NAME)
        assertTrue(idFile.exists())
        assertTrue(idFile.readText().isNotBlank())
    }

    @Test
    fun customAndroidIdProviderIsHonoredOnFirstUseOnly() = runTest {
        val instanceRoot = tempDir("device-custom-id")
        val audit = BridgeAuditLog(tempDir("device-custom-id-audit"))
        val ids = ArrayDeque(listOf("first", "second"))
        val handler = DeviceProfileBridge(
            instanceRoot = instanceRoot,
            auditLog = audit,
            androidIdProvider = { ids.removeFirst() },
        )

        val firstId = handler.handle(deviceProfileRequest()).asProfile().getString("androidId")
        val secondId = handler.handle(deviceProfileRequest()).asProfile().getString("androidId")

        assertEquals("first", firstId)
        assertEquals("first", secondId)
        assertEquals(listOf("second"), ids.toList())
    }

    @Test
    fun handleRejectsRequestForOtherBridges() = runTest {
        val handler = bridge(tempDir("device-mismatch"), BridgeAuditLog(tempDir("device-mismatch-audit")))
        val mismatched = BridgeRequest(
            instanceId = "vm1",
            bridge = BridgeType.AUDIO_OUTPUT,
            operation = "describe",
            reason = PermissionReason(BridgeType.AUDIO_OUTPUT, "describe", "", ""),
        )

        val thrown = runCatching { handler.handle(mismatched) }.exceptionOrNull()

        assertNotNull(thrown)
        assertTrue(thrown is IllegalArgumentException)
    }

    private fun bridge(instanceRoot: File, audit: BridgeAuditLog): DeviceProfileBridge =
        DeviceProfileBridge(instanceRoot = instanceRoot, auditLog = audit)

    private fun deviceProfileRequest() = BridgeRequest(
        instanceId = "vm1",
        bridge = BridgeType.DEVICE_PROFILE,
        operation = "describe",
        reason = PermissionReason(BridgeType.DEVICE_PROFILE, "describe", "", "Device profile"),
    )

    private fun BridgeResponse.asProfile(): JSONObject = JSONObject(payloadJson)

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

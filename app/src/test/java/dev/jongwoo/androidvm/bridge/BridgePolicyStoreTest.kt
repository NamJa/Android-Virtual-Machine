package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgePolicyStoreTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun loadReturnsDefaultPoliciesWhenFileMissing() {
        val store = BridgePolicyStore(tempDir("policy-default"))

        val policies = store.load()

        assertEquals(DefaultBridgePolicies.all, policies)
        assertEquals(BridgeMode.OFF, policies.getValue(BridgeType.CLIPBOARD).mode)
        assertEquals(false, policies.getValue(BridgeType.LOCATION).enabled)
        assertEquals(BridgeMode.UNSUPPORTED, policies.getValue(BridgeType.CAMERA).mode)
        assertEquals(BridgeMode.UNSUPPORTED, policies.getValue(BridgeType.MICROPHONE).mode)
        assertEquals(true, policies.getValue(BridgeType.AUDIO_OUTPUT).enabled)
        assertEquals(true, policies.getValue(BridgeType.NETWORK).enabled)
        assertEquals(true, policies.getValue(BridgeType.DEVICE_PROFILE).enabled)
        assertEquals(true, policies.getValue(BridgeType.VIBRATION).enabled)
    }

    @Test
    fun savePersistsPolicyAcrossNewStoreInstance() {
        val root = tempDir("policy-persistence")
        val first = BridgePolicyStore(root)
        first.update(BridgeType.LOCATION) {
            it.copy(
                mode = BridgeMode.LOCATION_FIXED,
                enabled = true,
                options = mapOf("latitude" to "37.5665", "longitude" to "126.978"),
            )
        }

        val reloaded = BridgePolicyStore(root).load()

        val location = reloaded.getValue(BridgeType.LOCATION)
        assertEquals(BridgeMode.LOCATION_FIXED, location.mode)
        assertTrue(location.enabled)
        assertEquals("37.5665", location.options["latitude"])
        assertEquals("126.978", location.options["longitude"])
    }

    @Test
    fun perInstancePoliciesAreIsolated() {
        val a = BridgePolicyStore(tempDir("policy-a"))
        val b = BridgePolicyStore(tempDir("policy-b"))
        a.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL, enabled = true)
        }

        assertEquals(BridgeMode.CLIPBOARD_BIDIRECTIONAL, a.load().getValue(BridgeType.CLIPBOARD).mode)
        assertEquals(BridgeMode.OFF, b.load().getValue(BridgeType.CLIPBOARD).mode)
    }

    @Test
    fun resetRestoresDefaults() {
        val store = BridgePolicyStore(tempDir("policy-reset"))
        store.update(BridgeType.NETWORK) { it.copy(mode = BridgeMode.OFF, enabled = false) }
        assertEquals(false, store.load().getValue(BridgeType.NETWORK).enabled)

        val resetPolicies = store.reset()

        assertEquals(DefaultBridgePolicies.all, resetPolicies)
        assertEquals(true, store.load().getValue(BridgeType.NETWORK).enabled)
    }

    @Test
    fun corruptedFileFallsBackToDefaultsAndReportsReason() {
        val root = tempDir("policy-corrupt")
        File(root, BridgePolicyStore.POLICY_FILE_NAME).writeText("{ this is not json")
        val reasons = mutableListOf<String>()
        val audit = BridgeAuditLog(root)
        val store = BridgePolicyStore(root) { reason ->
            reasons += reason
            audit.appendPolicyRecovery("vm1", reason)
        }

        val result = store.loadDetailed()

        assertTrue(result is BridgePolicyLoadResult.RecoveredFromCorruption)
        val recovered = result as BridgePolicyLoadResult.RecoveredFromCorruption
        assertEquals(DefaultBridgePolicies.all, recovered.policies)
        assertNotNull(recovered.reason)
        assertEquals(1, reasons.size)
        assertTrue(File(root, BridgePolicyStore.POLICY_FILE_NAME).readText().contains("\"bridges\""))
        assertTrue(BridgePolicyStore(root).loadDetailed() is BridgePolicyLoadResult.Loaded)
        assertTrue(audit.read().any { it.operation == "policy_recovery" })
    }

    @Test
    fun saveProducesAtomicReplacement() {
        val root = tempDir("policy-atomic")
        val store = BridgePolicyStore(root)

        store.update(BridgeType.VIBRATION) { it.copy(enabled = false) }

        val tmpFile = File(root, "${BridgePolicyStore.POLICY_FILE_NAME}.tmp")
        assertTrue(!tmpFile.exists())
        assertTrue(File(root, BridgePolicyStore.POLICY_FILE_NAME).exists())
    }

    @Test
    fun updateAcceptsCopyWithMatchingBridge() {
        val store = BridgePolicyStore(tempDir("policy-update-match"))
        val updated = store.update(BridgeType.AUDIO_OUTPUT) {
            it.copy(enabled = false, mode = BridgeMode.OFF)
        }

        assertEquals(BridgeType.AUDIO_OUTPUT, updated.bridge)
        assertEquals(false, updated.enabled)
    }

    @Test
    fun savedPolicyJsonContainsAllBridgesAndStableShape() {
        val root = tempDir("policy-shape")
        val store = BridgePolicyStore(root)
        store.update(BridgeType.LOCATION) { it.copy(mode = BridgeMode.LOCATION_FIXED) }

        val json = File(root, BridgePolicyStore.POLICY_FILE_NAME).readText()

        BridgeType.entries.forEach { type ->
            assertTrue(
                "Policy JSON missing bridge ${type.wireName} in $json",
                json.contains("\"${type.wireName}\""),
            )
        }
    }

    @Test
    fun differentInstanceIdsProduceDistinctSerialisations() {
        val a = BridgePolicyStore(tempDir("policy-x"))
        val b = BridgePolicyStore(tempDir("policy-y"))

        a.update(BridgeType.LOCATION) { it.copy(mode = BridgeMode.LOCATION_FIXED, enabled = true) }
        b.update(BridgeType.LOCATION) { it.copy(mode = BridgeMode.LOCATION_HOST_REAL, enabled = true) }

        assertNotEquals(
            a.load().getValue(BridgeType.LOCATION).mode,
            b.load().getValue(BridgeType.LOCATION).mode,
        )
    }

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
    }
}

package dev.jongwoo.androidvm.storage

import dev.jongwoo.androidvm.bridge.BridgeAuditLog
import dev.jongwoo.androidvm.bridge.BridgeDecision
import dev.jongwoo.androidvm.bridge.BridgeMode
import dev.jongwoo.androidvm.bridge.BridgePolicyStore
import dev.jongwoo.androidvm.bridge.BridgeType
import dev.jongwoo.androidvm.bridge.DefaultBridgePolicies
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks down the multi-instance readiness contract for Phase A.2: every per-instance helper must
 * keep its state under `<avm>/instances/<id>/...` so that adding a second instance in Phase E.1
 * never silently bleeds across instance boundaries.
 */
class MultiInstanceReadinessTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun policiesIsolatedAcrossInstances() {
        val (layout, vm1, vm2) = freshLayoutWith("vm1", "vm-test")
        val storeA = BridgePolicyStore(vm1.root)
        val storeB = BridgePolicyStore(vm2.root)
        // Default state is identical, but mutating one must not affect the other.
        assertEquals(DefaultBridgePolicies.all, storeA.load())
        assertEquals(DefaultBridgePolicies.all, storeB.load())

        storeA.update(BridgeType.CLIPBOARD) {
            it.copy(mode = BridgeMode.CLIPBOARD_HOST_TO_GUEST, enabled = true)
        }

        assertEquals(
            BridgeMode.CLIPBOARD_HOST_TO_GUEST,
            BridgePolicyStore(vm1.root).load().getValue(BridgeType.CLIPBOARD).mode,
        )
        assertEquals(
            BridgeMode.OFF,
            BridgePolicyStore(vm2.root).load().getValue(BridgeType.CLIPBOARD).mode,
        )
        assertNotEquals(
            "Per-instance policy files must live under their own directory",
            File(vm1.root, BridgePolicyStore.POLICY_FILE_NAME).canonicalPath,
            File(vm2.root, BridgePolicyStore.POLICY_FILE_NAME).canonicalPath,
        )
        // Sanity: the policy file actually exists for vm1, but vm2 still has the implicit default.
        assertTrue(File(vm1.root, BridgePolicyStore.POLICY_FILE_NAME).exists())
        // vm2 hasn't written anything yet — its default-on-load shouldn't have created a file.
        assertFalse(File(vm2.root, BridgePolicyStore.POLICY_FILE_NAME).exists())
        // Bonus: layout enumerates both ids (alphabetical).
        assertEquals(listOf("vm-test", "vm1"), layout.listInstanceIds())
    }

    @Test
    fun auditLogsIsolatedAcrossInstances() {
        val (_, vm1, vm2) = freshLayoutWith("vm1", "vm-test")
        val auditA = BridgeAuditLog(vm1.root)
        val auditB = BridgeAuditLog(vm2.root)

        auditA.appendDecision("vm1", BridgeType.AUDIO_OUTPUT, "write_pcm", BridgeDecision.allowed("ok"))
        auditB.appendDecision("vm-test", BridgeType.NETWORK, "connect", BridgeDecision.denied("offline"))

        assertEquals(1, auditA.count())
        assertEquals(1, auditB.count())
        val aText = auditA.logFile.readText()
        val bText = auditB.logFile.readText()
        assertTrue(aText.contains("\"instanceId\":\"vm1\""))
        assertFalse(aText.contains("vm-test"))
        assertTrue(bText.contains("\"instanceId\":\"vm-test\""))
        assertFalse(bText.contains("\"instanceId\":\"vm1\""))
    }

    @Test
    fun deleteInstanceRemovesOnlyThatInstanceDirectory() {
        val (layout, vm1, vm2) = freshLayoutWith("vm1", "vm-test")
        // Drop a file inside each instance so we can verify what survives.
        File(vm1.root, "evidence.txt").writeText("vm1")
        File(vm2.root, "evidence.txt").writeText("vm-test")

        val deleted = layout.deleteInstance("vm1")

        assertTrue("deleteInstance must report success when the dir existed", deleted)
        assertFalse(vm1.root.exists())
        assertTrue("deleting one instance must not touch siblings", vm2.root.exists())
        assertEquals("vm-test", File(vm2.root, "evidence.txt").readText())
        assertEquals(listOf("vm-test"), layout.listInstanceIds())
    }

    @Test
    fun deleteInstanceRejectsTraversalIds() {
        val layout = freshLayout()
        listOf("..", "../escape", "vm1/..", "/abs", "name with space").forEach { id ->
            val threw = runCatching { layout.deleteInstance(id) }.isFailure
            assertTrue("deleteInstance must reject id=\"$id\"", threw)
        }
    }

    @Test
    fun ensureInstanceRejectsTraversalIds() {
        val layout = freshLayout()
        val threw = runCatching { layout.ensureInstance("..") }.isFailure
        assertTrue(threw)
    }

    @Test
    fun listInstanceIdsIsEmptyForFreshRoot() {
        val layout = freshLayout()
        assertEquals(emptyList<String>(), layout.listInstanceIds())
    }

    @Test
    fun listInstanceIdsIgnoresStrayFiles() {
        val layout = freshLayout()
        layout.ensureInstance("vm1")
        // A bare file inside `instances/` should be ignored.
        File(File(layout.ensureRoot(), "instances"), "rogue.txt").writeText("nope")
        assertEquals(listOf("vm1"), layout.listInstanceIds())
    }

    @Test
    fun runtimeStateFilePathLivesAtAvmRoot() {
        val layout = freshLayout()
        // `runtimeStateFile` is the contract VmManagerService relies on — it must sit at the avm
        // root so that wiping a single instance directory does not kill global runtime state.
        assertEquals(
            File(layout.ensureRoot(), "runtime-state.json").canonicalPath,
            layout.runtimeStateFile.canonicalPath,
        )
        assertNotNull(layout.runtimeStateFile.parentFile)
    }

    @Test
    fun ensureInstanceAndDeleteAreIdempotent() {
        val layout = freshLayout()
        layout.ensureInstance("vm1")
        layout.ensureInstance("vm1") // re-creating must not throw
        assertTrue(layout.deleteInstance("vm1"))
        assertFalse(layout.deleteInstance("vm1")) // already gone
        assertNull(layout.listInstanceIds().firstOrNull { it == "vm1" })
    }

    private fun freshLayout(): PathLayout {
        val dir = Files.createTempDirectory("avm-multi").toFile().also { tempDirs += it }
        return PathLayout.forRoot(File(dir, "avm"))
    }

    private fun freshLayoutWith(vararg ids: String): Triple<PathLayout, InstancePaths, InstancePaths> {
        require(ids.size == 2) { "freshLayoutWith expects two instance ids" }
        val layout = freshLayout()
        val first = layout.ensureInstance(ids[0])
        val second = layout.ensureInstance(ids[1])
        return Triple(layout, first, second)
    }
}

package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.bridge.BridgeAuditLog
import dev.jongwoo.androidvm.bridge.BridgeDecision
import dev.jongwoo.androidvm.bridge.BridgeMode
import dev.jongwoo.androidvm.bridge.BridgePolicyStore
import dev.jongwoo.androidvm.bridge.BridgeType
import dev.jongwoo.androidvm.storage.PathLayout
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiInstanceLifecycleTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun controllerAssignsDistinctSlotsUpToTheLimit() {
        val controller = MultiInstanceController(maxInstances = 4, maxConcurrentRunning = 2)
        val a = controller.assignSlot("vm-alpha")
        val b = controller.assignSlot("vm-beta")
        val c = controller.assignSlot("vm-gamma")
        val d = controller.assignSlot("vm-delta")
        assertTrue(a is MultiInstanceController.SlotAssignment.Allocated)
        assertTrue(b is MultiInstanceController.SlotAssignment.Allocated)
        assertTrue(c is MultiInstanceController.SlotAssignment.Allocated)
        assertTrue(d is MultiInstanceController.SlotAssignment.Allocated)
        val slots = listOf(a.slot, b.slot, c.slot, d.slot)
        assertEquals("slots must be distinct", slots.toSet().size, slots.size)
        assertEquals(setOf(":vm1", ":vm2", ":vm3", ":vm4"), slots.toSet())

        // Reassigning an existing instance returns the same slot.
        val again = controller.assignSlot("vm-alpha")
        assertTrue(again is MultiInstanceController.SlotAssignment.Existing)
        assertEquals(a.slot, again.slot)

        // Adding a 5th instance fails.
        val full = controller.assignSlot("vm-extra")
        assertTrue(full is MultiInstanceController.SlotAssignment.Full)
        assertNull(full.slot)
    }

    @Test
    fun startStopRespectsConcurrentLimit() {
        val controller = MultiInstanceController(maxInstances = 4, maxConcurrentRunning = 2)
        controller.assignSlot("a")
        controller.assignSlot("b")
        controller.assignSlot("c")
        assertTrue(controller.requestStart("a") is MultiInstanceController.StartDecision.Started)
        assertTrue(controller.requestStart("b") is MultiInstanceController.StartDecision.Started)
        val capped = controller.requestStart("c")
        assertTrue(capped is MultiInstanceController.StartDecision.OverCap)
        assertFalse(capped.ok)

        controller.requestStop("a")
        val ok = controller.requestStart("c")
        assertTrue(ok is MultiInstanceController.StartDecision.Started)
        assertEquals(setOf("b", "c"), controller.runningInstances())
    }

    @Test
    fun requestStartFailsForUnknownInstance() {
        val controller = MultiInstanceController()
        val verdict = controller.requestStart("never-assigned")
        assertTrue(verdict is MultiInstanceController.StartDecision.NoSlot)
        assertFalse(verdict.ok)
    }

    @Test
    fun releaseClearsSlotAndAllowsReassignment() {
        val controller = MultiInstanceController(maxInstances = 2, maxConcurrentRunning = 2)
        controller.assignSlot("a"); controller.assignSlot("b")
        assertEquals(2, controller.assignedInstances().size)
        controller.release("a")
        assertNull(controller.slotFor("a"))
        // After release we can assign a new instance into the freed slot.
        assertTrue(controller.assignSlot("c") is MultiInstanceController.SlotAssignment.Allocated)
    }

    @Test
    fun snapshotReportsLimitsAndRunningSet() {
        val controller = MultiInstanceController(maxInstances = 3, maxConcurrentRunning = 2)
        controller.assignSlot("alpha")
        controller.requestStart("alpha")
        val snap = controller.snapshot()
        assertEquals(3, snap.getInt("limit"))
        assertEquals(2, snap.getInt("concurrentLimit"))
        assertEquals(":vm1", snap.getJSONObject("slots").getString("alpha"))
        val running = snap.getJSONArray("running")
        assertEquals(1, running.length())
        assertEquals("alpha", running.getString(0))
    }

    @Test
    fun perInstancePolicyStoresAreIsolated() {
        val avmRoot = Files.createTempDirectory("multi").toFile().also { tempDirs += it }
        val layout = PathLayout.forRoot(avmRoot)
        val a = layout.ensureInstance("vm-a")
        val b = layout.ensureInstance("vm-b")
        BridgePolicyStore(a.root).update(BridgeType.LOCATION) {
            it.copy(mode = BridgeMode.LOCATION_FIXED, enabled = true)
        }
        // The other instance must not see vm-a's mode change.
        val isolated = BridgePolicyStore(b.root).load().getValue(BridgeType.LOCATION)
        assertEquals(BridgeMode.OFF, isolated.mode)
        assertFalse(isolated.enabled)
        BridgeAuditLog(a.root).appendDecision("vm-a", BridgeType.AUDIO_OUTPUT, "write_pcm",
            BridgeDecision.allowed("ok"))
        assertEquals(0, BridgeAuditLog(b.root).count())
    }

    @Test
    fun manifestDeclaresFourProcessSlots() {
        val manifestText = projectFile("app/src/main/AndroidManifest.xml")?.readText() ?: ""
        assertNotNull(manifestText)
        listOf(":vm1", ":vm2", ":vm3", ":vm4").forEach { slot ->
            assertTrue("manifest must declare process $slot", manifestText.contains(slot))
        }
        assertTrue(manifestText.contains("VmNativeActivity2"))
        assertTrue(manifestText.contains("VmNativeActivity3"))
        assertTrue(manifestText.contains("VmNativeActivity4"))
        assertTrue(manifestText.contains("VmInstanceService2"))
        assertTrue(manifestText.contains("VmInstanceService3"))
        assertTrue(manifestText.contains("VmInstanceService4"))
    }

    @Test
    fun helpersRouteVm2ToTheSecondStaticProcessSlot() {
        assertEquals(VmInstanceService2::class.java, VmInstanceService.serviceClassFor("vm2"))
        assertEquals(VmInstanceService3::class.java, VmInstanceService.serviceClassFor("vm3"))
        assertEquals(VmInstanceService4::class.java, VmInstanceService.serviceClassFor("vm4"))
        assertEquals(VmNativeActivity2::class.java, VmNativeActivity.activityClassFor("vm2"))
        assertEquals(VmNativeActivity3::class.java, VmNativeActivity.activityClassFor("vm3"))
        assertEquals(VmNativeActivity4::class.java, VmNativeActivity.activityClassFor("vm4"))
    }

    @Test
    fun helpersRouteArbitraryInstanceIdsThroughStaticSlots() {
        VmProcessSlots.release("vm-alpha")
        assertEquals(VmInstanceService2::class.java, VmInstanceService.serviceClassFor("vm-alpha"))
        assertEquals(VmNativeActivity2::class.java, VmNativeActivity.activityClassFor("vm-alpha"))
        VmProcessSlots.release("vm-alpha")
    }

    private fun projectFile(relativePath: String): File? {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        )
        return candidates.firstOrNull { it.exists() }
    }
}

package dev.jongwoo.androidvm.diag

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootHealthMonitorTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun launcherReachedWithinBudget_passesAndSkipsRepair() {
        val instance = createInstance("boot-ok")
        val monitor = BootHealthMonitor(instance, budgetMillis = 60_000L)
        var repairCalled = false
        val verdict = monitor.observe(
            startMillis = 1_000L,
            launcherReachedMillis = 5_000L,
            repairAction = { repairCalled = true; true },
        )
        assertTrue(verdict.passed)
        assertEquals(BootHealthState.LAUNCHER_REACHED, verdict.state)
        assertFalse(repairCalled)
        val marker = File(instance.runtimeDir, BootHealthMonitor.MARKER_FILE_NAME)
        assertTrue(marker.exists())
    }

    @Test
    fun launcherUnreachedTriggersRepairAttempt() {
        val instance = createInstance("boot-repair")
        val monitor = BootHealthMonitor(instance, budgetMillis = 30_000L)
        var repairCalled = false
        val verdict = monitor.observe(
            startMillis = 0L,
            launcherReachedMillis = null,
            repairAction = { repairCalled = true; true },
        )
        assertTrue(repairCalled)
        assertEquals(BootHealthState.REPAIR_TRIGGERED, verdict.state)
        assertTrue(verdict.passed)
    }

    @Test
    fun repairFailureMarksFailed() {
        val instance = createInstance("boot-fail")
        val monitor = BootHealthMonitor(instance)
        val verdict = monitor.observe(
            startMillis = 0L,
            launcherReachedMillis = null,
            repairAction = { false },
        )
        assertEquals(BootHealthState.FAILED, verdict.state)
        assertFalse(verdict.passed)
        assertTrue(verdict.message.contains("launcher_unreachable"))
    }

    @Test
    fun launcherReachedAfterBudgetTriggersRepair() {
        val instance = createInstance("boot-late")
        val monitor = BootHealthMonitor(instance, budgetMillis = 1_000L)
        val verdict = monitor.observe(
            startMillis = 0L,
            launcherReachedMillis = 60_000L,
            repairAction = { true },
        )
        assertEquals(BootHealthState.REPAIR_TRIGGERED, verdict.state)
    }

    private fun createInstance(prefix: String): InstancePaths {
        val root = Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
        val configDir = File(root, "config")
        val rootfsDir = File(root, "rootfs")
        val paths = InstancePaths(
            id = "vm1",
            root = root,
            configDir = configDir,
            rootfsDir = rootfsDir,
            dataDir = File(rootfsDir, "data"),
            cacheDir = File(rootfsDir, "cache"),
            logsDir = File(root, "logs"),
            runtimeDir = File(root, "runtime"),
            sharedDir = File(root, "shared"),
            stagingDir = File(root, "staging"),
            exportDir = File(root, "export"),
            configFile = File(configDir, "vm_config.json"),
            imageManifestFile = File(configDir, "image_manifest.json"),
        )
        paths.create()
        return paths
    }
}

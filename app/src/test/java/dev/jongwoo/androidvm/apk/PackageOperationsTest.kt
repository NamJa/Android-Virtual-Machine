package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageOperationsTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun uninstall_deletesAppDataAndIndexEntry() {
        val instance = createInstancePaths("op-uninstall")
        seedPackage(instance, "com.example.alpha", withDataMarker = true)
        val ops = PackageOperations(clock = { 1_700_000_000_000L })

        val result = ops.uninstall(instance, "com.example.alpha")

        assertEquals(PackageOperationOutcome.SUCCESS, result.outcome)
        assertFalse(File(instance.dataDir, "app/com.example.alpha").exists())
        assertFalse(File(instance.dataDir, "data/com.example.alpha").exists())
        val snapshot = PackageIndex(ApkInstaller.packageIndexFile(instance)).load(instance.id, "ignored")
        assertNull(snapshot.find("com.example.alpha"))
        assertTrue(ApkInstaller.installLogFile(instance).readText().contains("UNINSTALLED com.example.alpha"))
    }

    @Test
    fun uninstall_unknownPackageReturnsNotFound() {
        val instance = createInstancePaths("op-uninstall-missing")
        val ops = PackageOperations(clock = { 1_700_000_000_000L })

        val result = ops.uninstall(instance, "com.unknown")

        assertEquals(PackageOperationOutcome.NOT_FOUND, result.outcome)
        assertEquals(PackageOperationErrorCode.NOT_FOUND, result.errorCode)
    }

    @Test
    fun clearData_emptiesDataDirButKeepsPackageEntryAndApk() {
        val instance = createInstancePaths("op-clear")
        seedPackage(instance, "com.example.beta", withDataMarker = true)
        val marker = File(instance.dataDir, "data/com.example.beta/marker.txt")
        assertTrue(marker.exists())
        val ops = PackageOperations(clock = { 1_700_000_000_000L })

        val result = ops.clearData(instance, "com.example.beta")

        assertEquals(PackageOperationOutcome.SUCCESS, result.outcome)
        assertFalse(marker.exists())
        assertTrue(File(instance.dataDir, "data/com.example.beta").isDirectory)
        assertTrue(File(instance.dataDir, "app/com.example.beta/base.apk").exists())
        val snapshot = PackageIndex(ApkInstaller.packageIndexFile(instance)).load(instance.id, "ignored")
        assertNotNull(snapshot.find("com.example.beta"))
        assertTrue(ApkInstaller.installLogFile(instance).readText().contains("CLEARED_DATA com.example.beta"))
    }

    @Test
    fun setEnabled_togglesFlagAndPersists() {
        val instance = createInstancePaths("op-enable")
        seedPackage(instance, "com.example.gamma", withDataMarker = false)
        val ops = PackageOperations(clock = { 1_700_000_000_000L })

        val disabled = ops.setEnabled(instance, "com.example.gamma", enabled = false)
        val enabled = ops.setEnabled(instance, "com.example.gamma", enabled = true)

        assertEquals(PackageOperationOutcome.SUCCESS, disabled.outcome)
        assertEquals(PackageOperationOutcome.SUCCESS, enabled.outcome)
        val snapshot = PackageIndex(ApkInstaller.packageIndexFile(instance)).load(instance.id, "ignored")
        assertEquals(true, snapshot.find("com.example.gamma")?.enabled)
        val log = ApkInstaller.installLogFile(instance).readText()
        assertTrue(log.contains("DISABLED com.example.gamma"))
        assertTrue(log.contains("ENABLED com.example.gamma"))
    }

    private fun seedPackage(instance: InstancePaths, packageName: String, withDataMarker: Boolean) {
        val appDir = File(instance.dataDir, "app/$packageName").apply { mkdirs() }
        File(appDir, "base.apk").writeText("apk-bytes")
        val dataDir = File(instance.dataDir, "data/$packageName").apply { mkdirs() }
        if (withDataMarker) File(dataDir, "marker.txt").writeText("kept")
        val info = GuestPackageInfo(
            packageName = packageName,
            label = packageName,
            versionCode = 1L,
            versionName = "1.0",
            installedPath = File(appDir, "base.apk").absolutePath,
            dataPath = dataDir.absolutePath,
            sha256 = "deadbeef",
            sourceName = "$packageName.apk",
            installedAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
            enabled = true,
            launchable = true,
            launcherActivity = "$packageName.MainActivity",
            nativeAbis = emptyList(),
        )
        val index = PackageIndex(ApkInstaller.packageIndexFile(instance))
        val snapshot = PackageIndexSnapshot.empty(instance.id, "2024-01-01T00:00:00Z").upsert(info, "2024-01-01T00:00:00Z")
        index.save(snapshot)
    }

    private fun createInstancePaths(prefix: String): InstancePaths {
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

package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherBootstrapperTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun firstBoot_installsAndStartsMinimalLauncher() {
        val instance = createInstancePaths("launcher-first")
        val (coordinator, pms) = newCoordinator(instance, defaultPackage())
        val activityManager = PmsBackedActivityManager(pms)
        val bootstrapper = LauncherBootstrapper(coordinator, activityManager, clock = { 1_700_000_000_000L })

        val launcherBytes = zipBytes("AndroidManifest.xml" to "<x/>")
        val fixture = LauncherFixture.minimal { launcherBytes }
        val result = bootstrapper.bootstrap(instance, fixture)

        assertTrue("first boot must install", result.installed)
        assertFalse(result.alreadyInstalled)
        assertTrue(result.started)
        assertEquals(fixture.packageName, result.packageName)
        assertEquals(listOf(fixture.packageName to fixture.launcherActivity!!), activityManager.launches)

        val marker = LauncherBootstrapper.launcherBootMarker(instance)
        assertTrue(marker.exists())
        val markerJson = JSONObject(marker.readText())
        assertTrue(markerJson.getBoolean("success"))
        assertEquals(fixture.packageName, markerJson.getString("packageName"))
    }

    @Test
    fun secondBoot_skipsInstallButStillStartsLauncher() {
        val instance = createInstancePaths("launcher-second")
        val (coordinator, pms) = newCoordinator(instance, defaultPackage())
        val activityManager = PmsBackedActivityManager(pms)
        val bootstrapper = LauncherBootstrapper(coordinator, activityManager)

        val fixture = LauncherFixture.minimal { zipBytes("a" to "1") }
        val first = bootstrapper.bootstrap(instance, fixture)
        assertTrue(first.passed)

        val installCallsAfterFirst = pms.callCount
        val second = bootstrapper.bootstrap(instance, fixture)
        assertFalse("second boot must not reinstall", second.installed)
        assertTrue(second.alreadyInstalled)
        assertTrue(second.started)
        assertEquals("PMS install must not be called again", installCallsAfterFirst, pms.callCount)
        assertEquals(2, activityManager.launches.size)
    }

    @Test
    fun launcher_failsWhenPmsRejectsInstall() {
        val instance = createInstancePaths("launcher-pmsfail")
        val (coordinator, pms) = newCoordinator(instance, defaultPackage())
        pms.nextInstallStatus = PmsInstallStatus.FAILED_INVALID_APK
        pms.nextInstallMessage = "synthetic_failure"
        val activityManager = PmsBackedActivityManager(pms)
        val bootstrapper = LauncherBootstrapper(coordinator, activityManager)

        val fixture = LauncherFixture.minimal { zipBytes("a" to "1") }
        val result = bootstrapper.bootstrap(instance, fixture)

        assertFalse(result.passed)
        assertFalse(result.started)
        val marker = LauncherBootstrapper.launcherBootMarker(instance)
        assertTrue(marker.exists())
        assertFalse(JSONObject(marker.readText()).getBoolean("success"))
    }

    @Test
    fun activityManager_mapsAllPmsStatuses() {
        val pms = FakeGuestPmsClient()
        pms.seed(
            "vm1",
            PmsPackageEntry("alive.pkg", 1L, "alive.pkg.Main", true),
        )
        pms.seed(
            "vm1",
            PmsPackageEntry("dead.pkg", 1L, "dead.pkg.Main", false),
        )
        pms.seed(
            "vm1",
            PmsPackageEntry("nolaunch.pkg", 1L, null, true),
        )
        val am = PmsBackedActivityManager(pms)
        assertEquals(GuestActivityManager.LaunchStatus.STARTED,
            am.startActivity("vm1", "alive.pkg", null).status)
        assertEquals(GuestActivityManager.LaunchStatus.DISABLED,
            am.startActivity("vm1", "dead.pkg", null).status)
        assertEquals(GuestActivityManager.LaunchStatus.NOT_LAUNCHABLE,
            am.startActivity("vm1", "nolaunch.pkg", null).status)
        assertEquals(GuestActivityManager.LaunchStatus.NOT_FOUND,
            am.startActivity("vm1", "missing.pkg", null).status)
    }

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, contents) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun defaultPackage(): String = LauncherBootstrapper.DEFAULT_MINIMAL_PACKAGE

    private fun newCoordinator(instance: InstancePaths, packageName: String): Pair<PmsInstallCoordinator, FakeGuestPmsClient> {
        val installer = ApkInstaller(
            inspector = FakeAlwaysInspector(packageName),
            clock = { 1L },
        )
        val pms = FakeGuestPmsClient().apply {
            packageNameResolver = { packageName }
        }
        return PmsInstallCoordinator(installer, pms, clock = { 1L }) to pms
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

    private class FakeAlwaysInspector(private val packageName: String) : ApkInspector {
        override fun inspect(apkFile: File): ApkInspectionOutcome = ApkInspectionOutcome.Success(
            ApkInspectionResult(
                packageName = packageName,
                versionCode = 1L,
                versionName = "1.0",
                label = packageName,
                nativeAbis = emptySet(),
                launcherActivity = "$packageName.MinimalLauncherActivity",
            ),
        )
    }
}

package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PmsInstallCoordinatorTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun install_routesThroughPmsAndSyncsHostIndex() {
        val instance = createInstancePaths("pms-happy")
        val staged = stagedApk(instance, "import_0001.apk", zipBytes("AndroidManifest.xml" to "x"))
        val installer = ApkInstaller(
            inspector = FakeInspector(
                staged.absolutePath to inspection("com.example.alpha", versionCode = 7L, launcher = "com.example.alpha.MainActivity"),
            ),
            clock = { 1_700_000_000_000L },
        )
        val pms = FakeGuestPmsClient().apply {
            packageNameResolver = { "com.example.alpha" }
        }
        val coordinator = PmsInstallCoordinator(installer, pms, clock = { 1_700_000_000_000L })

        val outcome = coordinator.install(instance, staged, sourceName = "alpha.apk")

        assertTrue("expected combined success: $outcome", outcome.passed)
        assertEquals(ApkInstallOutcome.INSTALLED, outcome.installResult.outcome)
        assertEquals(PmsInstallStatus.SUCCESS, outcome.pmsResult.status)
        assertEquals(1, pms.callCount)
        val installCall = pms.installs.single()
        assertEquals("vm1", installCall.instanceId)
        assertTrue(installCall.stagedApkPath.endsWith("/com.example.alpha/base.apk"))
        assertEquals(
            PmsInstallResult.FLAG_REPLACE_EXISTING or PmsInstallResult.FLAG_SKIP_DEXOPT,
            installCall.flags,
        )

        // Host index reconciled with guest result.
        val mergedNames = outcome.mergedSnapshot.packages.map { it.packageName }
        assertEquals(listOf("com.example.alpha"), mergedNames)

        // Install log captured the PMS edge.
        val log = ApkInstaller.installLogFile(instance).readText()
        assertTrue(log.contains("PMS_INSTALL com.example.alpha status=success"))
    }

    @Test
    fun install_pmsFailureMarksOutcomeFailedButHostFsKept() {
        val instance = createInstancePaths("pms-fail")
        val staged = stagedApk(instance, "import_0001.apk", zipBytes("a" to "1"))
        val installer = ApkInstaller(
            inspector = FakeInspector(
                staged.absolutePath to inspection("com.example.beta", versionCode = 1L),
            ),
            clock = { 1L },
        )
        val pms = FakeGuestPmsClient().apply {
            nextInstallStatus = PmsInstallStatus.FAILED_DEXOPT
            nextInstallMessage = "dex2oat_disabled_in_phase_d"
        }
        val coordinator = PmsInstallCoordinator(installer, pms, clock = { 1L })

        val outcome = coordinator.install(instance, staged, sourceName = "beta.apk")

        assertEquals(ApkInstallOutcome.INSTALLED, outcome.installResult.outcome)
        assertEquals(PmsInstallStatus.FAILED_DEXOPT, outcome.pmsResult.status)
        assertFalse("outcome.passed should be false", outcome.passed)
        // Host fs still has the APK (we don't roll the host install back; sync removes the stale entry).
        assertTrue(File(instance.dataDir, "app/com.example.beta/base.apk").exists())
        // Sync drops the host entry because PMS does not list it.
        assertEquals(0, outcome.mergedSnapshot.packages.size)
    }

    @Test
    fun syncFromGuest_addsGuestOnlyEntriesAndDropsHostOnlyEntries() {
        val instance = createInstancePaths("pms-sync")
        val installer = ApkInstaller(
            inspector = FakeInspector(),
            clock = { 1_700_000_000_000L },
        )
        val pms = FakeGuestPmsClient()
        // Pre-populate host index with "stale.pkg" that PMS does not know about.
        val hostIndex = PackageIndex(ApkInstaller.packageIndexFile(instance))
        hostIndex.save(
            PackageIndexSnapshot.empty("vm1", "now").upsert(
                GuestPackageInfo(
                    packageName = "stale.pkg",
                    label = "stale",
                    versionCode = 1L,
                    versionName = "1.0",
                    installedPath = "/tmp/x",
                    dataPath = "/tmp/y",
                    sha256 = null,
                    sourceName = null,
                    installedAt = "now",
                    updatedAt = "now",
                    enabled = true,
                    launchable = false,
                    launcherActivity = null,
                    nativeAbis = emptyList(),
                ),
                "now",
            ),
        )
        // Seed the guest with "guest.only" + a duplicate of nothing.
        pms.seed(
            "vm1",
            PmsPackageEntry("guest.only", versionCode = 3L, launcherActivity = "guest.only.Main", enabled = true),
        )

        val coordinator = PmsInstallCoordinator(installer, pms, clock = { 1L })
        val merged = coordinator.syncFromGuest(instance)
        assertEquals(listOf("guest.only"), merged.packages.map { it.packageName })
        val entry = merged.packages.first()
        assertEquals(3L, entry.versionCode)
        assertEquals("guest.only.Main", entry.launcherActivity)
        assertTrue(entry.launchable)
    }

    @Test
    fun pmsInstallResult_jsonRoundTrip() {
        val r = PmsInstallResult(PmsInstallStatus.SUCCESS, "com.example", "ok").toJson()
        val parsed = PmsInstallResult.fromJson(r)
        assertEquals(PmsInstallStatus.SUCCESS, parsed.status)
        assertEquals("com.example", parsed.packageName)
        assertEquals("ok", parsed.message)
    }

    @Test
    fun pmsPackageEntries_listJsonRoundTrip() {
        val entries = listOf(
            PmsPackageEntry("a.b", 1L, "a.b.Main", true),
            PmsPackageEntry("c.d", 2L, null, false),
        )
        val text = PmsPackageEntry.listToJson(entries)
        val parsed = PmsPackageEntry.listFromJson(text)
        assertEquals(entries, parsed)
    }

    @Test
    fun fakeGuestPmsClient_launchActivityHonoursLauncherFallback() {
        val pms = FakeGuestPmsClient()
        pms.seed(
            "vm1",
            PmsPackageEntry("p.q", 1L, "p.q.MainActivity", true),
        )
        val launched = pms.launchActivity("vm1", "p.q", null)
        assertEquals(PmsLaunchStatus.LAUNCHED, launched.status)
        assertEquals("p.q.MainActivity", launched.activity)

        assertEquals(
            PmsLaunchStatus.NOT_FOUND,
            pms.launchActivity("vm1", "missing.pkg", null).status,
        )
        pms.seed("vm1", PmsPackageEntry("d.d", 1L, null, true))
        assertEquals(
            PmsLaunchStatus.NOT_LAUNCHABLE,
            pms.launchActivity("vm1", "d.d", null).status,
        )
        pms.seed("vm1", PmsPackageEntry("o.o", 1L, "o.o.Main", false))
        assertEquals(
            PmsLaunchStatus.DISABLED,
            pms.launchActivity("vm1", "o.o", null).status,
        )
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

    private fun stagedApk(instance: InstancePaths, name: String, bytes: ByteArray): File {
        val file = File(instance.stagingDir, name)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun inspection(
        packageName: String,
        versionCode: Long,
        launcher: String? = null,
    ) = ApkInspectionOutcome.Success(
        ApkInspectionResult(
            packageName = packageName,
            versionCode = versionCode,
            versionName = "1.0",
            label = packageName,
            nativeAbis = emptySet(),
            launcherActivity = launcher,
        ),
    )

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

    private class FakeInspector(
        private val responses: Map<String, ApkInspectionOutcome> = emptyMap(),
    ) : ApkInspector {
        constructor(vararg pairs: Pair<String, ApkInspectionOutcome>) : this(pairs.toMap())

        override fun inspect(apkFile: File): ApkInspectionOutcome =
            responses[apkFile.absolutePath]
                ?: ApkInspectionOutcome.Failed(
                    ApkInspectionErrorCode.IO_ERROR,
                    "no fake response for ${apkFile.absolutePath}",
                )
    }
}

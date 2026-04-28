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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkInstallerTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun install_placesBaseApkAndUpdatesIndex() {
        val instance = createInstancePaths("install-happy")
        val staged = stagedApk(instance, "import_0001.apk", zipBytes("AndroidManifest.xml" to "data"))
        val inspector = FakeInspector(
            mapOf(
                staged.absolutePath to ApkInspectionOutcome.Success(
                    ApkInspectionResult(
                        packageName = "com.example.alpha",
                        versionCode = 11L,
                        versionName = "1.1",
                        label = "Alpha",
                        nativeAbis = setOf("arm64-v8a"),
                        launcherActivity = null,
                    ),
                ),
            ),
        )
        val installer = ApkInstaller(inspector = inspector, clock = { 1_700_000_000_000L })

        val result = installer.install(
            instancePaths = instance,
            stagedApk = staged,
            sourceName = "alpha.apk",
        )

        assertEquals(ApkInstallOutcome.INSTALLED, result.outcome)
        assertNull(result.errorCode)
        assertNotNull("packageInfo", result.packageInfo)
        val info = result.packageInfo!!
        assertEquals("com.example.alpha", info.packageName)
        val installedApk = File(instance.dataDir, "app/com.example.alpha/base.apk")
        assertTrue(installedApk.exists())
        assertTrue(File(instance.dataDir, "data/com.example.alpha").isDirectory)

        val index = PackageIndex(ApkInstaller.packageIndexFile(instance))
        val snapshot = index.load(instance.id, "ignored")
        assertEquals(1, snapshot.packages.size)
        assertEquals("com.example.alpha", snapshot.packages.first().packageName)
        assertEquals(installedApk.absolutePath, snapshot.packages.first().installedPath)

        val log = ApkInstaller.installLogFile(instance).readText()
        assertTrue(log.contains("INSTALLED com.example.alpha"))

        val txnDirs = instance.stagingDir.listFiles().orEmpty().filter { it.name.startsWith("install-") }
        assertEquals(0, txnDirs.size)
    }

    @Test
    fun install_updatePolicyOverwritesAndPreservesData() {
        val instance = createInstancePaths("install-update")
        val staged1 = stagedApk(instance, "import_0001.apk", zipBytes("v1" to "1"))
        val staged2 = stagedApk(instance, "import_0002.apk", zipBytes("v2" to "2"))
        val inspector = FakeInspector(
            mapOf(
                staged1.absolutePath to ApkInspectionOutcome.Success(
                    ApkInspectionResult(
                        packageName = "com.example.beta",
                        versionCode = 1L,
                        versionName = "1.0",
                        label = "Beta",
                        nativeAbis = emptySet(),
                        launcherActivity = null,
                    ),
                ),
                staged2.absolutePath to ApkInspectionOutcome.Success(
                    ApkInspectionResult(
                        packageName = "com.example.beta",
                        versionCode = 2L,
                        versionName = "2.0",
                        label = "Beta v2",
                        nativeAbis = emptySet(),
                        launcherActivity = null,
                    ),
                ),
            ),
        )
        var now = 1_700_000_000_000L
        val installer = ApkInstaller(inspector = inspector, clock = { now })
        val first = installer.install(instance, staged1, sourceName = "beta-1.apk")
        assertEquals(ApkInstallOutcome.INSTALLED, first.outcome)
        // Drop a marker into the data dir to ensure it's preserved across update.
        val marker = File(instance.dataDir, "data/com.example.beta/marker.txt")
        marker.writeText("preserved")

        now = 1_700_000_001_000L
        val second = installer.install(instance, staged2, sourceName = "beta-2.apk")

        assertEquals(ApkInstallOutcome.UPDATED, second.outcome)
        assertEquals(2L, second.packageInfo?.versionCode)
        assertEquals(first.packageInfo?.installedAt, second.packageInfo?.installedAt)
        assertTrue(second.packageInfo?.updatedAt != first.packageInfo?.updatedAt)
        assertTrue(marker.exists())
        assertEquals("preserved", marker.readText())

        val index = PackageIndex(ApkInstaller.packageIndexFile(instance))
        val snapshot = index.load(instance.id, "ignored")
        assertEquals(1, snapshot.packages.size)
        assertEquals(2L, snapshot.find("com.example.beta")?.versionCode)
    }

    @Test
    fun install_rejectsDuplicateWhenPolicyReject() {
        val instance = createInstancePaths("install-reject")
        val staged1 = stagedApk(instance, "import_0001.apk", zipBytes("v1" to "1"))
        val staged2 = stagedApk(instance, "import_0002.apk", zipBytes("v2" to "2"))
        val inspector = FakeInspector(
            mapOf(
                staged1.absolutePath to success("com.example.gamma", 1L),
                staged2.absolutePath to success("com.example.gamma", 2L),
            ),
        )
        val installer = ApkInstaller(inspector = inspector, clock = { 1_700_000_000_000L })

        installer.install(instance, staged1, sourceName = "gamma.apk")
        val rejected = installer.install(
            instancePaths = instance,
            stagedApk = staged2,
            sourceName = "gamma2.apk",
            duplicatePolicy = DuplicateInstallPolicy.REJECT,
        )

        assertEquals(ApkInstallOutcome.DUPLICATE_REJECTED, rejected.outcome)
        assertEquals(ApkInstallErrorCode.DUPLICATE_REJECTED, rejected.errorCode)
        assertEquals(1L, PackageIndex(ApkInstaller.packageIndexFile(instance))
            .load(instance.id, "ignored")
            .find("com.example.gamma")
            ?.versionCode)
    }

    @Test
    fun install_inspectFailureLeavesNoArtifacts() {
        val instance = createInstancePaths("install-inspect-failed")
        val staged = stagedApk(instance, "import_0001.apk", zipBytes("x" to "1"))
        val inspector = FakeInspector(
            mapOf(
                staged.absolutePath to ApkInspectionOutcome.Failed(
                    ApkInspectionErrorCode.PARSE_FAILED,
                    "manifest broken",
                ),
            ),
        )
        val installer = ApkInstaller(inspector = inspector, clock = { 1_700_000_000_000L })

        val result = installer.install(instance, staged, sourceName = "broken.apk")

        assertEquals(ApkInstallOutcome.INSPECT_FAILED, result.outcome)
        assertEquals(ApkInstallErrorCode.INSPECT_FAILED, result.errorCode)
        assertFalse(File(instance.dataDir, "app").let { it.exists() && it.listFiles().orEmpty().isNotEmpty() })
        assertFalse(ApkInstaller.packageIndexFile(instance).exists())
        assertTrue(ApkInstaller.installLogFile(instance).readText().contains("INSPECT_FAILED"))
    }

    @Test
    fun install_missingApkReportsIoError() {
        val instance = createInstancePaths("install-missing")
        val missing = File(instance.stagingDir, "import_9999.apk")
        val installer = ApkInstaller(
            inspector = FakeInspector(emptyMap()),
            clock = { 1_700_000_000_000L },
        )

        val result = installer.install(instance, missing, sourceName = "ghost.apk")

        assertEquals(ApkInstallOutcome.IO_ERROR, result.outcome)
        assertEquals(ApkInstallErrorCode.IO_ERROR, result.errorCode)
    }

    @Test
    fun install_shaMismatchAbortsAndCleansTransaction() {
        val instance = createInstancePaths("install-sha-mismatch")
        val staged = stagedApk(instance, "import_0001.apk", zipBytes("y" to "1"))
        val inspector = FakeInspector(mapOf(staged.absolutePath to success("com.example.delta")))
        val installer = ApkInstaller(inspector = inspector, clock = { 1_700_000_000_000L })

        val result = installer.install(
            instancePaths = instance,
            stagedApk = staged,
            sourceName = "delta.apk",
            stagedSha256 = "0".repeat(64),
        )

        assertEquals(ApkInstallOutcome.IO_ERROR, result.outcome)
        assertEquals(0, instance.stagingDir.listFiles().orEmpty().count { it.name.startsWith("install-") })
        assertFalse(File(instance.dataDir, "app/com.example.delta/base.apk").exists())
    }

    private fun success(packageName: String, versionCode: Long = 1L): ApkInspectionOutcome.Success =
        ApkInspectionOutcome.Success(
            ApkInspectionResult(
                packageName = packageName,
                versionCode = versionCode,
                versionName = "1.0",
                label = packageName,
                nativeAbis = emptySet(),
                launcherActivity = null,
            ),
        )

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
        private val responses: Map<String, ApkInspectionOutcome>,
    ) : ApkInspector {
        override fun inspect(apkFile: File): ApkInspectionOutcome =
            responses[apkFile.absolutePath]
                ?: ApkInspectionOutcome.Failed(
                    ApkInspectionErrorCode.IO_ERROR,
                    "no fake response for ${apkFile.absolutePath}",
                )
    }
}

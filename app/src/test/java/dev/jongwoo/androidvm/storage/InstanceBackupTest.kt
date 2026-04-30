package dev.jongwoo.androidvm.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstanceBackupTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun export_includesConfigLogsAndPolicyButNotRootfs() {
        val instance = createInstance("backup-export")
        File(instance.configDir, "settings.json").writeText("{\"x\":1}")
        File(instance.logsDir, "events.log").writeText("hello")
        File(instance.rootfsDir, "data/secret.txt").apply { parentFile?.mkdirs(); writeText("rootfs-only") }
        File(instance.root, "bridge-policy.json").writeText("{}")
        File(instance.stagingDir, "ignored.tmp").writeText("temp")

        val sink = ByteArrayOutputStream()
        val report = InstanceBackup().export(instance, sink)
        assertTrue("expected at least one entry", report.entryCount >= 2)
        assertNotNull(report.sha256)

        val names = readZipNames(sink.toByteArray())
        assertTrue(names.any { it.endsWith("backup-manifest.json") })
        assertTrue(names.any { it.endsWith("settings.json") })
        assertTrue(names.any { it.endsWith("events.log") })
        assertTrue(names.any { it.endsWith("bridge-policy.json") })
        // rootfs and staging must not leak into the backup
        assertFalse(names.any { it.contains("rootfs/") })
        assertFalse(names.any { it.endsWith("ignored.tmp") })
    }

    @Test
    fun import_restoresIntoFreshInstanceRoot() {
        val source = createInstance("backup-source")
        File(source.configDir, "settings.json").writeText("alpha")
        File(source.root, "bridge-policy.json").writeText("policy")
        val sink = ByteArrayOutputStream()
        InstanceBackup().export(source, sink)

        val target = createInstance("backup-target")
        // wipe the target so import must restore everything.
        File(target.configDir, "settings.json").delete()
        File(target.root, "bridge-policy.json").delete()

        val result = InstanceBackup().import(sink.toByteArray(), target)
        assertTrue(result.restoredCount >= 2)
        assertEquals("alpha", File(target.configDir, "settings.json").readText())
        assertEquals("policy", File(target.root, "bridge-policy.json").readText())
    }

    @Test
    fun import_rejectsZipEntriesEscapingInstanceRoot() {
        val instance = createInstance("backup-escape")
        // Hand-craft a zip that tries to write to ../etc/evil.
        val malicious = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(malicious).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("${instance.id}/../../etc/evil"))
            zip.write("evil".toByteArray())
            zip.closeEntry()
        }

        val thrown = runCatching {
            InstanceBackup().import(malicious.toByteArray(), instance)
        }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    private fun readZipNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(bytes.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                names += entry.name
                zin.closeEntry()
            }
        }
        return names
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

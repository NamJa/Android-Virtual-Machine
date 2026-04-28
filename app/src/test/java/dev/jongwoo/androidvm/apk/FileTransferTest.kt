package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTransferTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun fileStager_stagesFileWithDistinctSlotFromApkPipeline() {
        val staging = tempDir("file-stager")
        val apkStager = ApkStager()
        val fileStager = FileStager()
        val apkBytes = zipApkBytes()
        val fileBytes = "report contents".toByteArray()

        val apkResult = apkStager.stage(
            stagingDir = staging,
            sourceName = "alpha.apk",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream(apkBytes) },
        )
        val fileResult = fileStager.stage(
            stagingDir = staging,
            sourceName = "report.txt",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream(fileBytes) },
        )

        assertTrue(apkResult.success)
        assertTrue(fileResult.success)
        val apkStaged = File(apkResult.stagedPath!!)
        val fileStaged = File(fileResult.stagedPath!!)
        assertEquals("import_0001.apk", apkStaged.name)
        assertTrue(fileStaged.name.startsWith("file_0001_"))
        assertNotNull(fileResult.metadataPath)
        val metaJson = JSONObject(File(fileResult.metadataPath!!).readText())
        assertEquals("file", metaJson.getString("kind"))
        assertEquals("report.txt", metaJson.getString("sourceName"))
        assertTrue(File(staging, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun fileStager_rejectsOversizedAndCleansPartial() {
        val staging = tempDir("file-stager-size")
        val stager = FileStager()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "big.bin",
            sizeLimitBytes = 8L,
            source = { ByteArrayInputStream(ByteArray(64) { it.toByte() }) },
        )

        assertFalse(result.success)
        assertEquals(FileImportErrorCode.SIZE_EXCEEDED, result.errorCode)
        assertEquals(0, staging.listFiles().orEmpty().count { it.name.startsWith("file_") })
        assertTrue(File(staging, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun fileExporter_copiesGuestPathIntoExportDir() {
        val instance = createInstancePaths("export-ok")
        val src = File(instance.dataDir, "media/Documents/note.txt")
        src.parentFile?.mkdirs()
        src.writeText("hello")
        val exporter = FileExporter()

        val result = exporter.exportFromRootfs(instance, "data/media/Documents/note.txt")

        assertEquals(FileExportOutcome.OK, result.outcome)
        assertNotNull(result.exportedPath)
        val exported = File(result.exportedPath!!)
        assertEquals("hello", exported.readText())
        assertEquals(instance.exportDir.canonicalPath, exported.parentFile?.canonicalPath)
    }

    @Test
    fun fileExporter_rejectsParentEscape() {
        val instance = createInstancePaths("export-escape")
        val outsideRoot = instance.root.parentFile!!
        val outside = File(outsideRoot, "outside.txt").apply { writeText("secret") }
        val exporter = FileExporter()

        val result = exporter.exportFromRootfs(instance, "../../${outside.name}")

        assertEquals(FileExportOutcome.ESCAPE_REJECTED, result.outcome)
        assertEquals(FileExportErrorCode.ESCAPE_REJECTED, result.errorCode)
        assertNull(result.exportedPath)
        assertEquals(0, instance.exportDir.listFiles().orEmpty().count { it.name == "outside.txt" })
        outside.delete()
    }

    @Test
    fun fileExporter_returnsNotFoundForMissing() {
        val instance = createInstancePaths("export-missing")
        val exporter = FileExporter()

        val result = exporter.exportFromRootfs(instance, "data/never/created.txt")

        assertEquals(FileExportOutcome.NOT_FOUND, result.outcome)
        assertEquals(FileExportErrorCode.NOT_FOUND, result.errorCode)
    }

    private fun zipApkBytes(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(output).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
            zip.write("payload".toByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }

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

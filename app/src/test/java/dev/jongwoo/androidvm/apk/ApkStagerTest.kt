package dev.jongwoo.androidvm.apk

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkStagerTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun stage_writesApkAndMetadataWithExpectedFields() {
        val staging = tempDir("stage-happy")
        val bytes = zipBytes("AndroidManifest.xml" to "<manifest/>")
        val stager = ApkStager()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "example.apk",
            sizeLimitBytes = 1_000_000L,
            totalBytes = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
            clock = { 0L },
        )

        assertTrue(result.message, result.success)
        assertNull(result.errorCode)
        assertEquals(File(staging, "import_0001.apk").absolutePath, result.stagedPath)
        assertTrue(File(staging, "import_0001.apk").exists())
        assertEquals(bytes.size.toLong(), result.sizeBytes)

        val metaFile = File(staging, "import_0001.json")
        assertTrue(metaFile.exists())
        val meta = JSONObject(metaFile.readText())
        assertEquals("example.apk", meta.getString("sourceName"))
        assertEquals(File(staging, "import_0001.apk").absolutePath, meta.getString("stagedPath"))
        assertEquals(bytes.size.toLong(), meta.getLong("size"))
        assertTrue(meta.getString("sha256").isNotEmpty())
        assertEquals("1970-01-01T00:00:00Z", meta.getString("createdAt"))

        assertTrue(File(staging, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stage_assignsSequentialSlots() {
        val staging = tempDir("stage-sequential")
        val stager = ApkStager()
        val first = stager.stage(
            stagingDir = staging,
            sourceName = "a.apk",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream(zipBytes("a" to "1")) },
        )
        val second = stager.stage(
            stagingDir = staging,
            sourceName = "b.apk",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream(zipBytes("b" to "2")) },
        )

        assertTrue(first.success)
        assertTrue(second.success)
        assertEquals(File(staging, "import_0001.apk").absolutePath, first.stagedPath)
        assertEquals(File(staging, "import_0002.apk").absolutePath, second.stagedPath)
    }

    @Test
    fun stage_rejectsInvalidExtension() {
        val staging = tempDir("stage-extension")
        val stager = ApkStager()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "rogue.zip",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream(zipBytes("a" to "1")) },
        )

        assertFalse(result.success)
        assertEquals(ApkImportErrorCode.INVALID_EXTENSION, result.errorCode)
        assertEquals(0, staging.listFiles().orEmpty().count { it.name.startsWith("import_") })
    }

    @Test
    fun stage_rejectsInvalidMagicAndCleansPartial() {
        val staging = tempDir("stage-magic")
        val stager = ApkStager()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "evil.apk",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream("GIF89a not an apk".toByteArray()) },
        )

        assertFalse(result.success)
        assertEquals(ApkImportErrorCode.INVALID_HEADER, result.errorCode)
        assertEquals(0, staging.listFiles().orEmpty().count { it.name.startsWith("import_") })
        assertTrue(File(staging, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stage_rejectsOversizedAndCleansPartial() {
        val staging = tempDir("stage-size")
        val stager = ApkStager()
        val payload = zipBytes("big" to "0123456789".repeat(100))

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "big.apk",
            sizeLimitBytes = 16L,
            source = { ByteArrayInputStream(payload) },
        )

        assertFalse(result.success)
        assertEquals(ApkImportErrorCode.SIZE_EXCEEDED, result.errorCode)
        assertEquals(0, staging.listFiles().orEmpty().count { it.name.startsWith("import_") })
        assertTrue(File(staging, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stage_rejectsEmptySource() {
        val staging = tempDir("stage-empty")
        val stager = ApkStager()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "empty.apk",
            sizeLimitBytes = 1_000L,
            source = { ByteArrayInputStream(ByteArray(0)) },
        )

        assertFalse(result.success)
        assertEquals(ApkImportErrorCode.EMPTY_FILE, result.errorCode)
    }

    @Test
    fun stage_failsWhenSourceCannotOpen() {
        val staging = tempDir("stage-open-fail")
        val stager = ApkStager()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "broken.apk",
            sizeLimitBytes = 1_000L,
            source = { throw java.io.IOException("permission denied") },
        )

        assertFalse(result.success)
        assertEquals(ApkImportErrorCode.OPEN_FAILED, result.errorCode)
        assertTrue(File(staging, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().isEmpty())
    }

    @Test
    fun stage_emitsProgressCallbacks() {
        val staging = tempDir("stage-progress")
        val stager = ApkStager()
        val phases = mutableListOf<ApkImportPhase>()

        val result = stager.stage(
            stagingDir = staging,
            sourceName = "c.apk",
            sizeLimitBytes = 1_000_000L,
            source = { ByteArrayInputStream(zipBytes("c" to "data")) },
            onProgress = { progress -> phases += progress.phase },
        )

        assertTrue(result.success)
        assertNotNull(result.stagedPath)
        assertTrue(phases.contains(ApkImportPhase.OPEN_SOURCE))
        assertTrue(phases.contains(ApkImportPhase.COPY_TO_STAGING))
        assertTrue(phases.contains(ApkImportPhase.HASH))
        assertTrue(phases.contains(ApkImportPhase.WRITE_METADATA))
        assertEquals(ApkImportPhase.DONE, phases.last())
    }

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
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
}

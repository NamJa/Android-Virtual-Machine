package dev.jongwoo.androidvm.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomArchiveReaderTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun extract_expandsZipAndNormalizesRootfsPrefix() {
        val archive = zipBytes(
            "rootfs/system/build.prop" to "ro.build.version.release=7.1.2\n",
            "rootfs/system/bin/app_process64" to "#!/system/bin/sh\n",
            "rootfs/system/bin/servicemanager" to "#!/system/bin/sh\n",
            "rootfs/system/bin/sh" to "#!/system/bin/sh\n",
            "rootfs/system/framework/.keep" to "",
            "rootfs/vendor/.keep" to "",
        )
        val destination = tempDir("extract-rootfs")
        val reader = RomArchiveReader { ByteArrayInputStream(archive) }

        val result = reader.extract(debugCandidate(), destination) {}

        assertTrue(result is RomArchiveExtractionResult.Extracted)
        assertEquals("ro.build.version.release=7.1.2\n", File(destination, "system/build.prop").readText())
        assertTrue(File(destination, "system/bin/app_process64").exists())
        assertTrue(File(destination, "system/framework").isDirectory)
        assertTrue(File(destination, "vendor").isDirectory)
        assertTrue(File(destination, "data").isDirectory)
        assertTrue(File(destination, "cache").isDirectory)
    }

    @Test
    fun extract_rejectsZipSlipEntry() {
        val archive = zipBytes("../escape.txt" to "escaped")
        val destination = tempDir("zip-slip-rootfs")
        val outside = File(destination.parentFile, "escape.txt")
        val reader = RomArchiveReader { ByteArrayInputStream(archive) }

        val result = reader.extract(debugCandidate(), destination) {}

        assertTrue(result is RomArchiveExtractionResult.Failed)
        assertFalse(outside.exists())
    }

    @Test
    fun extract_reportsUnsupportedTarZstForMvp() {
        val reader = RomArchiveReader { ByteArrayInputStream(ByteArray(0)) }

        val result = reader.extract(debugCandidate(format = "tar.zst"), tempDir("tar-zst-rootfs")) {}

        assertTrue(result is RomArchiveExtractionResult.Unsupported)
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

    private fun debugCandidate(format: String = "zip"): RomImageCandidate {
        val manifest = RomImageManifest(
            name = "androidfs_7.1.2_arm64_debug",
            guestVersion = "7.1.2",
            guestArch = "arm64",
            format = format,
            compressedSize = 1,
            uncompressedSize = 1,
            sha256 = "sha256",
            createdAt = "2024-01-01T00:00:00Z",
            minHostSdk = 26,
        )
        return RomImageCandidate(
            manifest = manifest,
            manifestAssetPath = "guest/${manifest.name}.manifest.json",
            archiveAssetPath = "guest/${manifest.archiveFileName}",
            checksumAssetPath = "guest/${manifest.checksumFileName}",
            archiveExists = true,
            checksumExists = true,
        )
    }
}

package dev.jongwoo.androidvm.storage

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RomArchiveReaderTarZstTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun manifest() = RomImageManifest(
        name = "guest",
        guestVersion = "7.1.2",
        guestArch = "arm64",
        format = "tar.zst",
        compressedSize = 0,
        uncompressedSize = 0,
        sha256 = "",
        createdAt = "",
        minHostSdk = 26,
    )

    private fun candidate() = RomImageCandidate(
        manifest = manifest(),
        manifestAssetPath = "guest/guest.manifest.json",
        archiveAssetPath = "guest/guest.tar.zst",
        checksumAssetPath = "guest/guest.sha256",
        archiveExists = true,
        checksumExists = true,
    )

    // JVM tests use zstd-jni explicitly; on-device the default is NDK libzstd.
    private fun reader(bytes: ByteArray) =
        RomArchiveReader(openAsset = { ByteArrayInputStream(bytes) }, zstd = ZstdJniDecompressor())

    private fun tarZst(build: (TarArchiveOutputStream) -> Unit): ByteArray {
        val baos = ByteArrayOutputStream()
        ZstdCompressorOutputStream(baos).use { zstd ->
            TarArchiveOutputStream(zstd).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                build(tar)
            }
        }
        return baos.toByteArray()
    }

    private fun file(tar: TarArchiveOutputStream, name: String, content: ByteArray, mode: Int) {
        val e = TarArchiveEntry(name)
        e.size = content.size.toLong()
        e.mode = mode
        tar.putArchiveEntry(e)
        tar.write(content)
        tar.closeArchiveEntry()
    }

    @Test
    fun extractsFilesDirsSymlinksWithExecBit() {
        val elf = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val data = tarZst { tar ->
            tar.putArchiveEntry(TarArchiveEntry("system/bin/").also { it.mode = 493 })
            tar.closeArchiveEntry()
            file(tar, "system/bin/linker64", elf, mode = 493) // 0755
            file(tar, "system/build.prop", "ro.zygote=zygote64\n".toByteArray(), mode = 420) // 0644
            val link = TarArchiveEntry("system/bin/app_process", TarConstants.LF_SYMLINK)
            link.linkName = "app_process64"
            tar.putArchiveEntry(link)
            tar.closeArchiveEntry()
        }

        val dest = tmp.newFolder("rootfs")
        val result = reader(data).extract(candidate(), dest) {}
        assertEquals(RomArchiveExtractionResult.Extracted, result)

        val linker = File(dest, "system/bin/linker64")
        assertTrue(linker.isFile)
        assertArrayEquals(elf, linker.readBytes())
        assertTrue("linker64 must be executable", linker.canExecute())

        val prop = File(dest, "system/build.prop")
        assertTrue(prop.isFile)
        assertFalse("build.prop should not be executable", prop.canExecute())

        val symlink = File(dest, "system/bin/app_process")
        assertTrue(Files.isSymbolicLink(symlink.toPath()))
        assertEquals("app_process64", Files.readSymbolicLink(symlink.toPath()).toString())

        // data/cache scaffolding created.
        assertTrue(File(dest, "data").isDirectory)
        assertTrue(File(dest, "cache").isDirectory)
    }

    @Test
    fun rejectsPathTraversal() {
        val data = tarZst { tar ->
            file(tar, "../escape.txt", "x".toByteArray(), mode = 420)
        }
        val dest = tmp.newFolder("rootfs")
        val result = reader(data).extract(candidate(), dest) {}
        assertTrue(result is RomArchiveExtractionResult.Failed)
        assertFalse(File(dest.parentFile, "escape.txt").exists())
    }

    @Test
    fun stripsRootfsPrefix() {
        val data = tarZst { tar ->
            file(tar, "rootfs/system/build.prop", "x".toByteArray(), mode = 420)
        }
        val dest = tmp.newFolder("rootfs")
        assertEquals(RomArchiveExtractionResult.Extracted, reader(data).extract(candidate(), dest) {})
        assertTrue(File(dest, "system/build.prop").isFile)
    }
}

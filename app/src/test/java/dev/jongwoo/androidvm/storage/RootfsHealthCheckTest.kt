package dev.jongwoo.androidvm.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootfsHealthCheckTest {
    private val tempDirs = mutableListOf<File>()
    private val healthCheck = RootfsHealthCheck()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun check_returnsHealthyForCompleteRootfsWithMarker() {
        val rootfs = tempDir("healthy-rootfs")
        createHealthyRootfs(rootfs)
        val marker = File(rootfs.parentFile, "image_manifest.json").apply {
            writeText("{}")
        }

        val result = healthCheck.check(rootfs, marker)

        assertTrue(result.ok)
        assertTrue(result.missingRequiredEntries.isEmpty())
        assertTrue(result.unwritableEntries.isEmpty())
        assertFalse(result.markerMissing)
    }

    @Test
    fun check_reportsMissingRequiredEntries() {
        val rootfs = tempDir("empty-rootfs")

        val result = healthCheck.check(rootfs, null)

        assertFalse(result.ok)
        assertTrue(result.missingRequiredEntries.contains("system/build.prop"))
        assertTrue(result.missingRequiredEntries.contains("system/bin/app_process64"))
        assertTrue(result.missingRequiredEntries.contains("vendor"))
    }

    @Test
    fun check_reportsMissingMarkerWhenExpected() {
        val rootfs = tempDir("marker-rootfs")
        createHealthyRootfs(rootfs)
        val marker = File(rootfs.parentFile, "missing-image_manifest.json")

        val result = healthCheck.check(rootfs, marker)

        assertFalse(result.ok)
        assertTrue(result.markerMissing)
    }

    @Test
    fun check_placeholderRootfsIsHealthyButNotBootReady() {
        val rootfs = tempDir("placeholder-rootfs")
        createHealthyRootfs(rootfs) // shell-script binaries, no real ELFs

        val result = healthCheck.check(rootfs, null)

        assertTrue(result.ok)
        assertFalse(result.bootReady)
        assertTrue(result.bootReadyMissing.contains("system/bin/linker64"))
        assertTrue(result.bootReadyMissing.contains("system/lib64/libc.so"))
        assertTrue(result.bootReadyMissing.contains("system/bin/app_process64"))
    }

    @Test
    fun check_realArm64ElfRootfsIsBootReady() {
        val rootfs = tempDir("bootready-rootfs")
        createHealthyRootfs(rootfs)
        writeArm64Elf(File(rootfs, "system/bin/linker64"))
        writeArm64Elf(File(rootfs, "system/lib64/libc.so"))
        writeArm64Elf(File(rootfs, "system/bin/app_process64")) // overwrite placeholder

        val result = healthCheck.check(rootfs, null)

        assertTrue(result.ok)
        assertTrue(result.bootReadySummary, result.bootReady)
        assertTrue(result.bootReadyMissing.isEmpty())
    }

    @Test
    fun bootReadyRejectsWrongMachine() {
        val rootfs = tempDir("wrongelf-rootfs")
        createHealthyRootfs(rootfs)
        writeArm64Elf(File(rootfs, "system/lib64/libc.so"))
        writeArm64Elf(File(rootfs, "system/bin/app_process64"))
        // Valid ELF64 magic but x86_64 (EM_X86_64 = 0x3E), not arm64.
        File(rootfs, "system/bin/linker64").apply {
            val b = ByteArray(64)
            b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
            b[4] = 2; b[18] = 0x3E
            writeBytes(b)
        }

        val result = healthCheck.check(rootfs, null)
        assertFalse(result.bootReady)
        assertTrue(result.bootReadyMissing.contains("system/bin/linker64"))
    }

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
    }

    private fun writeArm64Elf(file: File) {
        file.parentFile?.mkdirs()
        val b = ByteArray(64)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2 // ELFCLASS64
        b[5] = 1 // little-endian
        b[16] = 3 // ET_DYN
        b[18] = 0xB7.toByte() // EM_AARCH64
        file.writeBytes(b)
    }

    private fun createHealthyRootfs(rootfs: File) {
        File(rootfs, "system/bin").mkdirs()
        File(rootfs, "system/framework").mkdirs()
        File(rootfs, "vendor").mkdirs()
        File(rootfs, "data").mkdirs()
        File(rootfs, "cache").mkdirs()
        File(rootfs, "system/build.prop").writeText("ro.build.version.release=7.1.2\n")
        File(rootfs, "system/bin/app_process64").writeText("#!/system/bin/sh\n")
        File(rootfs, "system/bin/servicemanager").writeText("#!/system/bin/sh\n")
        File(rootfs, "system/bin/sh").writeText("#!/system/bin/sh\n")
    }
}

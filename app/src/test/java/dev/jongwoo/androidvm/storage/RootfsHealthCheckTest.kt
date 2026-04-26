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

    private fun tempDir(prefix: String): File {
        return Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
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

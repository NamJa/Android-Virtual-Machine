package dev.jongwoo.androidvm.diag

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportStoreTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun record_writesJsonUnderCrashesDir() {
        val instance = createInstance("crash-record")
        var t = 1_700_000_000_000L
        val store = CrashReportStore(instance, clock = { t })
        store.recordNow(
            CrashKind.NATIVE_SIGSEGV,
            process = ":vm1",
            threadName = "ART-thread-1",
            summary = "segfault @0xdeadbeef",
            stackPreviewLines = listOf("0  libart.so", "1  libfoo.so"),
        )
        val crashesDir = File(instance.logsDir, "crashes")
        val files = crashesDir.listFiles().orEmpty()
        assertEquals(1, files.size)
        val report = store.list().single()
        assertEquals(CrashKind.NATIVE_SIGSEGV, report.kind)
        assertEquals(":vm1", report.process)
        assertEquals(2, report.stackPreviewLines.size)
    }

    @Test
    fun rotates_keepsOnlyMostRecentReports() {
        val instance = createInstance("crash-rotate")
        var t = 1_700_000_000_000L
        val store = CrashReportStore(instance, clock = { t }, maxRetained = 3)
        repeat(5) {
            t += 1_500
            store.recordNow(
                CrashKind.JAVA_EXCEPTION,
                ":vm1",
                "main",
                "summary $it",
                listOf("frame_$it"),
            )
        }
        val reports = store.list()
        assertEquals(3, reports.size)
        // Latest summary should be the last one we recorded (#4).
        assertTrue(reports.last().summary.endsWith("4"))
    }

    @Test
    fun listEmptyWhenNoCrashes() {
        val instance = createInstance("crash-empty")
        val store = CrashReportStore(instance)
        assertEquals(0, store.count())
        assertTrue(store.list().isEmpty())
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

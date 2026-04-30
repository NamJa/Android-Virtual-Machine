package dev.jongwoo.androidvm.bridge

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileBridgeTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun import_succeedsAndStagesUnderFilesSubdir() {
        val instance = createInstance("file-import")
        val audit = BridgeAuditLog(instance.root)
        val bridge = FileBridge(audit)
        val payload = "hello world".toByteArray()

        val response = bridge.import(instance, "doc.txt", payload.size.toLong()) {
            ByteArrayInputStream(payload)
        }
        assertTrue(response is FileBridge.FileBridgeResponse.Imported)
        val staged = (response as FileBridge.FileBridgeResponse.Imported).result.stagedPath!!
        assertTrue(staged.contains("/staging/files/"))
        assertTrue(File(staged).exists())
        assertTrue(audit.read().any { it.operation == "file_bridge_file_import" })
    }

    @Test
    fun import_rejectsOversizeAndAuditsDenial() {
        val instance = createInstance("file-oversize")
        val audit = BridgeAuditLog(instance.root)
        val bridge = FileBridge(audit, sizeLimitBytes = 8)
        val payload = "definitely longer than eight bytes".toByteArray()

        val response = bridge.import(instance, "big.bin") { ByteArrayInputStream(payload) }
        assertTrue(response is FileBridge.FileBridgeResponse.Denied)
        assertEquals("file_too_large", (response as FileBridge.FileBridgeResponse.Denied).reason)
        assertTrue(audit.read().any { it.reason.contains("size_exceeded") })
    }

    @Test
    fun import_returnsDeniedWhenBridgeDisabled() {
        val instance = createInstance("file-disabled")
        val audit = BridgeAuditLog(instance.root)
        val bridge = FileBridge(audit).apply { setEnabled(false) }

        val response = bridge.import(instance, "x.txt") { ByteArrayInputStream("x".toByteArray()) }
        assertTrue(response is FileBridge.FileBridgeResponse.Denied)
        assertFalse(response.allowed)
    }

    @Test
    fun export_succeedsForLegitimateRootfsPath() {
        val instance = createInstance("file-export")
        val source = File(instance.rootfsDir, "logs/system.txt").apply {
            parentFile?.mkdirs()
            writeText("hello-world")
        }
        val audit = BridgeAuditLog(instance.root)
        val bridge = FileBridge(audit)

        val response = bridge.export(instance, "logs/system.txt", "system.txt")
        assertTrue(response is FileBridge.FileBridgeResponse.Exported)
        val result = (response as FileBridge.FileBridgeResponse.Exported).result
        assertEquals(11L, result.sizeBytes)
        assertNotNull(result.exportedPath)
        assertTrue(File(result.exportedPath!!).exists())
        assertTrue(source.exists()) // export must not delete the source
    }

    @Test
    fun export_rejectsPathTraversal() {
        val instance = createInstance("file-escape")
        File(instance.rootfsDir, "ok.txt").writeText("ok")
        val audit = BridgeAuditLog(instance.root)
        val bridge = FileBridge(audit)

        val response = bridge.export(instance, "../../etc/passwd")
        assertTrue(response is FileBridge.FileBridgeResponse.Denied)
        assertEquals("escape_rejected", (response as FileBridge.FileBridgeResponse.Denied).reason)
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

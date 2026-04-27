package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class VmConfigPathContractTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun defaultConfigExportsNativeRuntimePathContract() {
        val paths = instancePaths()

        val configJson = JSONObject(VmConfig.default(paths).toJson())
        val runtime = configJson.getJSONObject("runtime")
        val exportedPaths = configJson.getJSONObject("paths")

        assertEquals("7.1.2", runtime.getString("guestAndroidVersion"))
        assertEquals("arm64-v8a", runtime.getString("guestAbi"))
        assertEquals(paths.rootfsDir.absolutePath, exportedPaths.getString("rootfsPath"))
        assertEquals(paths.dataDir.absolutePath, exportedPaths.getString("dataDir"))
        assertEquals(paths.cacheDir.absolutePath, exportedPaths.getString("cacheDir"))
        assertEquals(paths.logsDir.absolutePath, exportedPaths.getString("logsDir"))
        assertEquals(paths.stagingDir.absolutePath, exportedPaths.getString("stagingDir"))
        assertEquals(paths.configFile.absolutePath, exportedPaths.getString("configFile"))
        assertEquals(paths.imageManifestFile.absolutePath, exportedPaths.getString("imageManifestFile"))
    }

    @Test
    fun defaultConfigDisplayMatchesStage5MvpPortraitTarget() {
        val paths = instancePaths()

        val display = JSONObject(VmConfig.default(paths).toJson()).getJSONObject("display")

        assertEquals(720, display.getInt("width"))
        assertEquals(1280, display.getInt("height"))
        assertEquals(320, display.getInt("densityDpi"))
    }

    private fun instancePaths(): InstancePaths {
        val root = Files.createTempDirectory("vm-config-paths").toFile().also { tempDirs += it }
        val configDir = File(root, "config")
        val rootfsDir = File(root, "rootfs")
        return InstancePaths(
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
    }
}

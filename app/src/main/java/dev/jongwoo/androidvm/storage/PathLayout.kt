package dev.jongwoo.androidvm.storage

import android.content.Context
import java.io.File

class PathLayout(context: Context) {
    private val root = File(context.filesDir, "avm")
    val auditLogFile: File = File(root, "logs/bridge-audit.log")

    fun ensureRoot(): File = root.apply {
        mkdirs()
        File(this, "roms").mkdirs()
        File(this, "instances").mkdirs()
        File(this, "logs").mkdirs()
    }

    fun ensureInstance(instanceId: String): InstancePaths {
        val instanceRoot = File(ensureRoot(), "instances/$instanceId")
        val configDir = File(instanceRoot, "config")
        val rootfsDir = File(instanceRoot, "rootfs")
        val paths = InstancePaths(
            id = instanceId,
            root = instanceRoot,
            configDir = configDir,
            rootfsDir = rootfsDir,
            dataDir = File(rootfsDir, "data"),
            cacheDir = File(rootfsDir, "cache"),
            logsDir = File(instanceRoot, "logs"),
            runtimeDir = File(instanceRoot, "runtime"),
            sharedDir = File(instanceRoot, "shared"),
            stagingDir = File(instanceRoot, "staging"),
            exportDir = File(instanceRoot, "export"),
            configFile = File(configDir, "vm_config.json"),
            imageManifestFile = File(configDir, "image_manifest.json"),
        )
        paths.create()
        return paths
    }
}

data class InstancePaths(
    val id: String,
    val root: File,
    val configDir: File,
    val rootfsDir: File,
    val dataDir: File,
    val cacheDir: File,
    val logsDir: File,
    val runtimeDir: File,
    val sharedDir: File,
    val stagingDir: File,
    val exportDir: File,
    val configFile: File,
    val imageManifestFile: File,
) {
    fun create() {
        root.mkdirs()
        configDir.mkdirs()
        rootfsDir.mkdirs()
        dataDir.mkdirs()
        cacheDir.mkdirs()
        logsDir.mkdirs()
        runtimeDir.mkdirs()
        sharedDir.mkdirs()
        stagingDir.mkdirs()
        exportDir.mkdirs()
    }
}

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
        val paths = InstancePaths(
            id = instanceId,
            root = instanceRoot,
            dataDir = File(instanceRoot, "data"),
            logsDir = File(instanceRoot, "logs"),
            runtimeDir = File(instanceRoot, "runtime"),
            sharedDir = File(instanceRoot, "shared"),
            configFile = File(instanceRoot, "config.json"),
        )
        paths.create()
        return paths
    }
}

data class InstancePaths(
    val id: String,
    val root: File,
    val dataDir: File,
    val logsDir: File,
    val runtimeDir: File,
    val sharedDir: File,
    val configFile: File,
) {
    fun create() {
        root.mkdirs()
        dataDir.mkdirs()
        logsDir.mkdirs()
        runtimeDir.mkdirs()
        sharedDir.mkdirs()
    }
}

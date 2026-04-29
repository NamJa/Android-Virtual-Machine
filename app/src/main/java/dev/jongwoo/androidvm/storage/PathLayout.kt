package dev.jongwoo.androidvm.storage

import android.content.Context
import java.io.File

class PathLayout private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "avm"))

    val auditLogFile: File = File(root, "logs/bridge-audit.log")
    val runtimeStateFile: File = File(root, "runtime-state.json")

    fun ensureRoot(): File = root.apply {
        mkdirs()
        File(this, "roms").mkdirs()
        File(this, "instances").mkdirs()
        File(this, "logs").mkdirs()
    }

    fun listInstanceIds(): List<String> {
        val instancesDir = File(ensureRoot(), "instances")
        if (!instancesDir.isDirectory) return emptyList()
        return instancesDir.listFiles()
            ?.filter { it.isDirectory && it.name.matches(VALID_ID) }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()
    }

    fun deleteInstance(instanceId: String): Boolean {
        require(instanceId.matches(VALID_ID)) { "Invalid instanceId: $instanceId" }
        val instancesRoot = File(ensureRoot(), "instances").canonicalFile
        val target = File(instancesRoot, instanceId).canonicalFile
        require(target.parentFile?.canonicalPath == instancesRoot.canonicalPath) {
            "Instance path escaped instances root: $target"
        }
        if (!target.exists()) return false
        return target.deleteRecursively()
    }

    fun ensureInstance(instanceId: String): InstancePaths {
        require(instanceId.matches(VALID_ID)) { "Invalid instanceId: $instanceId" }
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

    companion object {
        private val VALID_ID = Regex("[A-Za-z0-9_\\-]{1,64}")

        /** Test-only entry point that lets the layout point at any directory. */
        fun forRoot(avmRoot: File): PathLayout = PathLayout(avmRoot)
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

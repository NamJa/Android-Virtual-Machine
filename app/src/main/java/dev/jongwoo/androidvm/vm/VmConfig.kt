package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.bridge.BridgePolicy
import dev.jongwoo.androidvm.storage.InstancePaths
import org.json.JSONObject

data class VmConfig(
    val instanceId: String,
    val runtime: RuntimeConfig,
    val display: DisplayConfig,
    val paths: GuestPathConfig,
    val bridgePolicy: BridgePolicy = BridgePolicy(),
) {
    fun toJson(): String = JSONObject()
        .put("instanceId", instanceId)
        .put(
            "runtime",
            JSONObject()
                .put("guestAndroidVersion", runtime.guestAndroidVersion)
                .put("guestAbi", runtime.guestAbi)
                .put("cpuMode", runtime.cpuMode)
                .put("networkMode", runtime.networkMode),
        )
        .put(
            "display",
            JSONObject()
                .put("width", display.width)
                .put("height", display.height)
                .put("densityDpi", display.densityDpi),
        )
        .put(
            "paths",
            JSONObject()
                .put("rootfsPath", paths.rootfsPath)
                .put("dataDir", paths.dataDir)
                .put("cacheDir", paths.cacheDir)
                .put("runtimeDir", paths.runtimeDir)
                .put("sharedDir", paths.sharedDir)
                .put("logsDir", paths.logsDir)
                .put("stagingDir", paths.stagingDir)
                .put("configFile", paths.configFile)
                .put("imageManifestFile", paths.imageManifestFile),
        )
        .put("bridgePolicy", bridgePolicy.toJson())
        .toString(2)

    companion object {
        const val DEFAULT_INSTANCE_ID = "vm1"

        fun default(paths: InstancePaths): VmConfig = VmConfig(
            instanceId = paths.id,
            runtime = RuntimeConfig(
                guestAndroidVersion = "7.1.2",
                guestAbi = "arm64-v8a",
                cpuMode = "user-mode",
                networkMode = "host",
            ),
            display = DisplayConfig(
                width = 1280,
                height = 720,
                densityDpi = 320,
            ),
            paths = GuestPathConfig(
                rootfsPath = paths.rootfsDir.absolutePath,
                dataDir = paths.dataDir.absolutePath,
                cacheDir = paths.cacheDir.absolutePath,
                runtimeDir = paths.runtimeDir.absolutePath,
                sharedDir = paths.sharedDir.absolutePath,
                logsDir = paths.logsDir.absolutePath,
                stagingDir = paths.stagingDir.absolutePath,
                configFile = paths.configFile.absolutePath,
                imageManifestFile = paths.imageManifestFile.absolutePath,
            ),
        )
    }
}

data class RuntimeConfig(
    val guestAndroidVersion: String,
    val guestAbi: String,
    val cpuMode: String,
    val networkMode: String,
)

data class DisplayConfig(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

data class GuestPathConfig(
    val rootfsPath: String,
    val dataDir: String,
    val cacheDir: String,
    val runtimeDir: String,
    val sharedDir: String,
    val logsDir: String,
    val stagingDir: String,
    val configFile: String,
    val imageManifestFile: String,
)

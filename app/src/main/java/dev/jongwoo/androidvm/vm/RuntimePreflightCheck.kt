package dev.jongwoo.androidvm.vm

import android.content.Context
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.storage.RomPipelineSnapshot
import java.io.File

object RuntimePreflightCheck {
    fun run(
        context: Context,
        instanceId: String = VmConfig.DEFAULT_INSTANCE_ID,
    ): RuntimePreflightResult {
        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        val snapshot = RomInstaller(context).snapshot(instanceId)
        if (!snapshot.isInstalled) {
            return RuntimePreflightResult.Blocked(
                config = config,
                snapshot = snapshot,
                message = "Guest image is ${snapshot.imageState}. Install or repair the ROM image first.",
            )
        }

        val pathChecks = listOf(
            File(config.paths.rootfsPath).isDirectory to "Rootfs directory is missing: ${config.paths.rootfsPath}",
            File(config.paths.configFile).isFile to "VM config file is missing: ${config.paths.configFile}",
            File(config.paths.imageManifestFile).isFile to
                "Image manifest file is missing: ${config.paths.imageManifestFile}",
            (File(config.paths.dataDir).isDirectory && File(config.paths.dataDir).canWrite()) to
                "Guest data directory is not writable: ${config.paths.dataDir}",
            (File(config.paths.cacheDir).isDirectory && File(config.paths.cacheDir).canWrite()) to
                "Guest cache directory is not writable: ${config.paths.cacheDir}",
        )
        val failedCheck = pathChecks.firstOrNull { !it.first }
        if (failedCheck != null) {
            return RuntimePreflightResult.Blocked(
                config = config,
                snapshot = snapshot,
                message = failedCheck.second,
            )
        }

        return RuntimePreflightResult.Ready(config, snapshot)
    }
}

sealed interface RuntimePreflightResult {
    val config: VmConfig
    val snapshot: RomPipelineSnapshot

    data class Ready(
        override val config: VmConfig,
        override val snapshot: RomPipelineSnapshot,
    ) : RuntimePreflightResult

    data class Blocked(
        override val config: VmConfig,
        override val snapshot: RomPipelineSnapshot,
        val message: String,
    ) : RuntimePreflightResult
}

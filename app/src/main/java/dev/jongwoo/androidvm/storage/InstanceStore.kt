package dev.jongwoo.androidvm.storage

import android.content.Context
import dev.jongwoo.androidvm.vm.VmConfig

class InstanceStore(context: Context) {
    private val paths = PathLayout(context)

    fun ensureDefaultConfig(): VmConfig {
        val instancePaths = paths.ensureInstance(VmConfig.DEFAULT_INSTANCE_ID)
        val config = VmConfig.default(instancePaths)
        saveConfig(config, instancePaths)
        return config
    }

    fun pathsFor(instanceId: String): InstancePaths = paths.ensureInstance(instanceId)

    fun saveConfig(config: VmConfig, instancePaths: InstancePaths = paths.ensureInstance(config.instanceId)) {
        instancePaths.configFile.parentFile?.mkdirs()
        instancePaths.configFile.writeText(config.toJson())
    }
}

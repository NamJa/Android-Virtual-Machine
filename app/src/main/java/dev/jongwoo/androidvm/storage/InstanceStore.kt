package dev.jongwoo.androidvm.storage

import android.content.Context
import dev.jongwoo.androidvm.vm.VmConfig

class InstanceStore(
    private val paths: PathLayout,
) {
    constructor(context: Context) : this(PathLayout(context))

    fun ensureDefaultConfig(): VmConfig = ensureConfig(VmConfig.DEFAULT_INSTANCE_ID)

    fun ensureConfig(instanceId: String): VmConfig {
        val instancePaths = paths.ensureInstance(instanceId)
        val config = VmConfig.default(instancePaths)
        saveConfig(config, instancePaths)
        return config
    }

    fun list(): List<String> = paths.listInstanceIds()

    fun delete(instanceId: String): Boolean = paths.deleteInstance(instanceId)

    fun pathsFor(instanceId: String): InstancePaths = paths.ensureInstance(instanceId)

    fun saveConfig(config: VmConfig, instancePaths: InstancePaths = paths.ensureInstance(config.instanceId)) {
        instancePaths.configFile.parentFile?.mkdirs()
        instancePaths.configFile.writeText(config.toJson())
    }
}

package dev.jongwoo.androidvm.storage

import android.content.Context
import dev.jongwoo.androidvm.vm.VmConfig

class InstanceStore(context: Context) {
    private val paths = PathLayout(context)

    fun ensureDefaultConfig(): VmConfig {
        val instancePaths = paths.ensureInstance(VmConfig.DEFAULT_INSTANCE_ID)
        val config = VmConfig.default(instancePaths)
        instancePaths.configFile.writeText(config.toJson())
        return config
    }

    fun pathsFor(instanceId: String): InstancePaths = paths.ensureInstance(instanceId)
}

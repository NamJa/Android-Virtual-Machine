package dev.jongwoo.androidvm.vm

internal object VmProcessSlots {
    private val lock = Any()
    private val dynamicSlots = linkedMapOf<String, String>()

    fun processSlotFor(instanceId: String): String = when (instanceId) {
        VmConfig.DEFAULT_INSTANCE_ID -> ":vm1"
        "vm2" -> ":vm2"
        "vm3" -> ":vm3"
        "vm4" -> ":vm4"
        else -> synchronized(lock) {
            dynamicSlots.getOrPut(instanceId) {
                val used = dynamicSlots.values.toSet()
                listOf(":vm2", ":vm3", ":vm4").firstOrNull { it !in used } ?: ":vm4"
            }
        }
    }

    fun release(instanceId: String) {
        synchronized(lock) {
            dynamicSlots.remove(instanceId)
        }
    }
}

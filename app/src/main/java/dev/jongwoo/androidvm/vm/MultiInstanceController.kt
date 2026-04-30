package dev.jongwoo.androidvm.vm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase E.1 multi-instance controller. The host APK ships a fixed number of `:vmN` process slots
 * declared in the manifest; this controller maps user-visible instance IDs onto those slots,
 * enforces the concurrent-RUNNING ceiling, and exposes an isolation predicate the diagnostics
 * harness can grep.
 *
 * This is a pure-JVM type so unit tests can drive it without an Android framework.
 */
class MultiInstanceController(
    private val maxInstances: Int = DEFAULT_MAX_INSTANCES,
    private val maxConcurrentRunning: Int = DEFAULT_MAX_CONCURRENT,
) {
    init {
        require(maxInstances in 1..PROCESS_SLOTS.size) {
            "maxInstances must be in 1..${PROCESS_SLOTS.size} (was $maxInstances)"
        }
        require(maxConcurrentRunning in 1..maxInstances) {
            "maxConcurrentRunning must be in 1..$maxInstances (was $maxConcurrentRunning)"
        }
    }

    private val slotByInstance = LinkedHashMap<String, String>()
    private val runningInstances = LinkedHashSet<String>()

    val limit: Int get() = maxInstances
    val concurrentLimit: Int get() = maxConcurrentRunning

    @Synchronized
    fun assignSlot(instanceId: String): SlotAssignment {
        val existing = slotByInstance[instanceId]
        if (existing != null) return SlotAssignment.Existing(existing)
        if (slotByInstance.size >= maxInstances) {
            return SlotAssignment.Full("max_instances_${maxInstances}_reached")
        }
        val used = slotByInstance.values.toSet()
        val nextSlot = PROCESS_SLOTS.first { it !in used }
        slotByInstance[instanceId] = nextSlot
        return SlotAssignment.Allocated(nextSlot)
    }

    @Synchronized
    fun release(instanceId: String) {
        slotByInstance.remove(instanceId)
        runningInstances.remove(instanceId)
    }

    @Synchronized
    fun slotFor(instanceId: String): String? = slotByInstance[instanceId]

    @Synchronized
    fun assignedInstances(): List<String> = slotByInstance.keys.toList()

    @Synchronized
    fun runningInstances(): Set<String> = runningInstances.toSet()

    /**
     * Mark the given instance as starting. Fails fast with [StartDecision.OverCap] when the user
     * has already booked the concurrent ceiling.
     */
    @Synchronized
    fun requestStart(instanceId: String): StartDecision {
        if (slotByInstance[instanceId] == null) {
            return StartDecision.NoSlot("instance_not_assigned")
        }
        if (instanceId in runningInstances) {
            return StartDecision.AlreadyRunning
        }
        if (runningInstances.size >= maxConcurrentRunning) {
            return StartDecision.OverCap(
                "max_concurrent_${maxConcurrentRunning}_reached",
            )
        }
        runningInstances += instanceId
        return StartDecision.Started(slotByInstance.getValue(instanceId))
    }

    @Synchronized
    fun requestStop(instanceId: String): Boolean {
        return runningInstances.remove(instanceId)
    }

    @Synchronized
    fun snapshot(): JSONObject = JSONObject()
        .put("limit", maxInstances)
        .put("concurrentLimit", maxConcurrentRunning)
        .put("slots", JSONObject().apply {
            slotByInstance.forEach { (id, slot) -> put(id, slot) }
        })
        .put("running", JSONArray().apply {
            runningInstances.forEach { put(it) }
        })

    sealed class SlotAssignment {
        abstract val slot: String?
        abstract val ok: Boolean

        data class Allocated(override val slot: String) : SlotAssignment() {
            override val ok: Boolean = true
        }

        data class Existing(override val slot: String) : SlotAssignment() {
            override val ok: Boolean = true
        }

        data class Full(val reason: String) : SlotAssignment() {
            override val slot: String? = null
            override val ok: Boolean = false
        }
    }

    sealed class StartDecision {
        abstract val ok: Boolean

        data class Started(val slot: String) : StartDecision() {
            override val ok: Boolean = true
        }

        object AlreadyRunning : StartDecision() {
            override val ok: Boolean = true
        }

        data class OverCap(val reason: String) : StartDecision() {
            override val ok: Boolean = false
        }

        data class NoSlot(val reason: String) : StartDecision() {
            override val ok: Boolean = false
        }
    }

    companion object {
        const val DEFAULT_MAX_INSTANCES: Int = 4
        const val DEFAULT_MAX_CONCURRENT: Int = 2
        val PROCESS_SLOTS: List<String> = listOf(":vm1", ":vm2", ":vm3", ":vm4")
    }
}

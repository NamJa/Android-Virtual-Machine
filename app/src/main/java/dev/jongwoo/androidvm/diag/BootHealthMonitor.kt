package dev.jongwoo.androidvm.diag

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import org.json.JSONObject

/** State the launcher boot has reached. */
enum class BootHealthState {
    UNKNOWN,
    BOOTING,
    LAUNCHER_REACHED,
    REPAIR_TRIGGERED,
    FAILED,
    ;

    val wireName: String get() = name.lowercase()
}

data class BootHealthVerdict(
    val state: BootHealthState,
    val launcherReachedMillis: Long?,
    val repairAttempted: Boolean,
    val repairSucceeded: Boolean,
    val message: String,
) {
    val passed: Boolean
        get() = state == BootHealthState.LAUNCHER_REACHED ||
            (state == BootHealthState.REPAIR_TRIGGERED && repairSucceeded)
}

/**
 * Phase D.9 boot health monitor. Defines launcher activity startup as the boot success marker;
 * if the host process does not see that marker within [budgetMillis], invokes [repairAction] one
 * time and re-evaluates. Multi-snapshot rollback is reserved for Phase E.2.
 */
class BootHealthMonitor(
    private val instancePaths: InstancePaths,
    private val budgetMillis: Long = DEFAULT_BUDGET_MILLIS,
) {
    fun observe(
        startMillis: Long,
        launcherReachedMillis: Long?,
        repairAction: () -> Boolean,
    ): BootHealthVerdict {
        val markerFile = File(instancePaths.runtimeDir, MARKER_FILE_NAME)
        markerFile.parentFile?.mkdirs()
        if (launcherReachedMillis != null && launcherReachedMillis - startMillis <= budgetMillis) {
            writeMarker(markerFile, BootHealthState.LAUNCHER_REACHED, launcherReachedMillis, false, "boot_ok")
            return BootHealthVerdict(
                state = BootHealthState.LAUNCHER_REACHED,
                launcherReachedMillis = launcherReachedMillis,
                repairAttempted = false,
                repairSucceeded = false,
                message = "boot_ok",
            )
        }
        val repaired = runCatching { repairAction() }.getOrDefault(false)
        val state = if (repaired) BootHealthState.REPAIR_TRIGGERED else BootHealthState.FAILED
        val message = if (repaired) "repair_triggered" else "launcher_unreachable"
        writeMarker(markerFile, state, null, true, message)
        return BootHealthVerdict(
            state = state,
            launcherReachedMillis = null,
            repairAttempted = true,
            repairSucceeded = repaired,
            message = message,
        )
    }

    private fun writeMarker(
        markerFile: File,
        state: BootHealthState,
        launcherReachedMillis: Long?,
        repairAttempted: Boolean,
        message: String,
    ) {
        val json = JSONObject()
            .put("state", state.wireName)
            .put("launcherReachedMillis", launcherReachedMillis ?: JSONObject.NULL)
            .put("repairAttempted", repairAttempted)
            .put("message", message)
        runCatching { markerFile.writeText(json.toString(2)) }
    }

    companion object {
        const val MARKER_FILE_NAME: String = "boot-health.json"
        const val DEFAULT_BUDGET_MILLIS: Long = 60_000L
    }
}

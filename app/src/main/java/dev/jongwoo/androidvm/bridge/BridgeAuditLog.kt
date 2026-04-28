package dev.jongwoo.androidvm.bridge

import java.io.File
import org.json.JSONObject

/**
 * Append-only audit log of every bridge decision and policy change for one VM instance.
 *
 * Stored as JSONL at `<instanceRoot>/bridge-audit.jsonl`. Sensitive payload (clipboard text,
 * raw coordinates, package contents) is never appended — only the bridge / operation /
 * decision / short reason. The file rotates once it grows past [maxEntries] lines.
 */
class BridgeAuditLog(
    private val instanceRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive (was $maxEntries)" }
    }

    val logFile: File = run {
        val resolved = File(instanceRoot, LOG_FILE_NAME).canonicalFile
        require(resolved.parentFile?.canonicalPath == instanceRoot.canonicalPath) {
            "Audit log path escaped instance root: $resolved"
        }
        resolved
    }

    @Synchronized
    fun append(entry: BridgeAuditEntry) {
        logFile.parentFile?.mkdirs()
        logFile.appendText(entry.toJson().toString() + "\n")
        rotateIfNeeded()
    }

    @Synchronized
    fun appendDecision(
        instanceId: String,
        bridge: BridgeType,
        operation: String,
        decision: BridgeDecision,
    ) {
        append(
            BridgeAuditEntry(
                timeMillis = clock(),
                instanceId = instanceId,
                bridge = bridge,
                operation = operation,
                allowed = decision.allowed,
                result = decision.result,
                reason = decision.reason,
            ),
        )
    }

    @Synchronized
    fun appendPolicyChange(
        instanceId: String,
        bridge: BridgeType,
        mode: BridgeMode,
        enabled: Boolean,
    ) {
        append(
            BridgeAuditEntry(
                timeMillis = clock(),
                instanceId = instanceId,
                bridge = bridge,
                operation = "policy_change",
                allowed = true,
                result = BridgeResult.ALLOWED,
                reason = "mode=${mode.wireName} enabled=$enabled",
            ),
        )
    }

    @Synchronized
    fun appendPolicyRecovery(
        instanceId: String,
        reason: String,
        policies: Map<BridgeType, BridgePolicy> = DefaultBridgePolicies.all,
    ) {
        policies.values.forEach { policy ->
            append(
                BridgeAuditEntry(
                    timeMillis = clock(),
                    instanceId = instanceId,
                    bridge = policy.bridge,
                    operation = "policy_recovery",
                    allowed = true,
                    result = BridgeResult.ALLOWED,
                    reason = "recovered:$reason mode=${policy.mode.wireName} enabled=${policy.enabled}",
                ),
            )
        }
    }

    @Synchronized
    fun read(limit: Int = Int.MAX_VALUE): List<BridgeAuditEntry> {
        if (!logFile.exists()) return emptyList()
        require(limit > 0) { "limit must be positive (was $limit)" }
        val lines = logFile.readLines().filter { it.isNotBlank() }
        val tail = if (lines.size > limit) lines.subList(lines.size - limit, lines.size) else lines
        return tail.mapNotNull { line ->
            runCatching { BridgeAuditEntry.fromJson(JSONObject(line)) }.getOrNull()
        }
    }

    @Synchronized
    fun clear() {
        if (logFile.exists()) logFile.writeText("")
    }

    @Synchronized
    fun count(): Int {
        if (!logFile.exists()) return 0
        return logFile.readLines().count { it.isNotBlank() }
    }

    private fun rotateIfNeeded() {
        val lines = logFile.readLines()
        if (lines.size <= maxEntries) return
        val keep = lines.takeLast(maxEntries)
        val tmp = File(logFile.parentFile, "${logFile.name}.tmp")
        tmp.writeText(keep.joinToString(separator = "\n", postfix = "\n"))
        if (!tmp.renameTo(logFile)) {
            tmp.copyTo(logFile, overwrite = true)
            tmp.delete()
        }
    }

    companion object {
        const val LOG_FILE_NAME = "bridge-audit.jsonl"
        const val DEFAULT_MAX_ENTRIES = 500
    }
}

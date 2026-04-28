package dev.jongwoo.androidvm.bridge

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

sealed interface BridgePolicyLoadResult {
    data class Loaded(val policies: Map<BridgeType, BridgePolicy>) : BridgePolicyLoadResult
    data class RecoveredFromCorruption(
        val policies: Map<BridgeType, BridgePolicy>,
        val reason: String,
    ) : BridgePolicyLoadResult
    data class Default(val policies: Map<BridgeType, BridgePolicy>) : BridgePolicyLoadResult
}

class BridgePolicyStore(
    private val instanceRoot: File,
    private val onCorruption: ((String) -> Unit)? = null,
) {
    private val policyFile: File = run {
        val file = File(instanceRoot, POLICY_FILE_NAME).canonicalFile
        require(file.path.startsWith(instanceRoot.canonicalPath + File.separator) ||
            file.parentFile?.canonicalPath == instanceRoot.canonicalPath
        ) {
            "Bridge policy path escaped instance root: $file"
        }
        file
    }

    fun fileForTest(): File = policyFile

    @Synchronized
    fun load(): Map<BridgeType, BridgePolicy> = when (val result = loadDetailed()) {
        is BridgePolicyLoadResult.Loaded -> result.policies
        is BridgePolicyLoadResult.RecoveredFromCorruption -> result.policies
        is BridgePolicyLoadResult.Default -> result.policies
    }

    @Synchronized
    fun loadDetailed(): BridgePolicyLoadResult {
        if (!policyFile.exists()) {
            return BridgePolicyLoadResult.Default(DefaultBridgePolicies.all)
        }
        return try {
            BridgePolicyLoadResult.Loaded(decode(policyFile.readText()))
        } catch (cause: JSONException) {
            recover("invalid_json:${cause.message}")
        } catch (cause: IllegalStateException) {
            recover("invalid_policy:${cause.message}")
        } catch (cause: IOException) {
            recover("io_error:${cause.message}")
        }
    }

    private fun recover(reason: String): BridgePolicyLoadResult {
        val policies = DefaultBridgePolicies.all
        val recoveryReason = runCatching {
            save(policies)
            reason
        }.getOrElse { cause ->
            "$reason;persist_failed:${cause.javaClass.simpleName}:${cause.message}"
        }
        onCorruption?.invoke(recoveryReason)
        return BridgePolicyLoadResult.RecoveredFromCorruption(policies, recoveryReason)
    }

    @Synchronized
    fun save(policies: Map<BridgeType, BridgePolicy>) {
        require(policies.keys == DefaultBridgePolicies.all.keys) {
            "BridgePolicyStore.save expects every BridgeType to be present"
        }
        policyFile.parentFile?.mkdirs()
        val tmp = File(policyFile.parentFile, "${policyFile.name}.tmp")
        tmp.writeText(encode(policies))
        if (!tmp.renameTo(policyFile)) {
            tmp.copyTo(policyFile, overwrite = true)
            tmp.delete()
        }
    }

    @Synchronized
    fun update(bridge: BridgeType, transform: (BridgePolicy) -> BridgePolicy): BridgePolicy {
        val current = load().toMutableMap()
        val updated = transform(current.getValue(bridge))
        require(updated.bridge == bridge) {
            "Updated policy bridge mismatch: expected=$bridge actual=${updated.bridge}"
        }
        current[bridge] = updated
        save(current)
        return updated
    }

    @Synchronized
    fun reset(): Map<BridgeType, BridgePolicy> {
        save(DefaultBridgePolicies.all)
        return DefaultBridgePolicies.all
    }

    private fun encode(policies: Map<BridgeType, BridgePolicy>): String {
        val root = JSONObject().put("version", FORMAT_VERSION)
        val items = JSONObject()
        BridgeType.entries.forEach { type ->
            items.put(type.wireName, policies.getValue(type).toJson())
        }
        root.put("bridges", items)
        return root.toString(2)
    }

    private fun decode(text: String): Map<BridgeType, BridgePolicy> {
        val root = JSONObject(text)
        val bridges = root.optJSONObject("bridges")
            ?: error("missing bridges block")
        val result = mutableMapOf<BridgeType, BridgePolicy>()
        BridgeType.entries.forEach { type ->
            val node = bridges.optJSONObject(type.wireName)
            result[type] = if (node != null) {
                BridgePolicy.fromJson(node)
            } else {
                DefaultBridgePolicies.forBridge(type)
            }
        }
        return result
    }

    companion object {
        const val POLICY_FILE_NAME = "bridge-policy.json"
        const val FORMAT_VERSION = 1
    }
}

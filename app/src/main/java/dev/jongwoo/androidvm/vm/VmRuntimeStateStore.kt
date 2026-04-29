package dev.jongwoo.androidvm.vm

import java.io.File
import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure-JVM helper that persists per-instance [VmState] to a single JSON file. Extracted from
 * [VmManagerService] so it can be exercised from unit tests without Robolectric.
 *
 * Schema (version 1):
 * ```
 * { "version": 1,
 *   "instances": { "vm1": { "state": "RUNNING", "lastChangedMillis": 1703980800000 } } }
 * ```
 *
 * On corrupt JSON the store backs the file up to `runtime-state.bak.<timestamp>.json` and
 * returns an empty map; subsequent saves overwrite the file with valid contents.
 */
class VmRuntimeStateStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Synchronized
    fun load(): Map<String, VmState> {
        if (!file.exists()) return emptyMap()
        return try {
            decode(file.readText())
        } catch (cause: JSONException) {
            backupCorrupt("invalid_json:${cause.message ?: "unknown"}")
            emptyMap()
        } catch (cause: IllegalStateException) {
            backupCorrupt("invalid_schema:${cause.message ?: "unknown"}")
            emptyMap()
        } catch (cause: IOException) {
            emptyMap()
        }
    }

    @Synchronized
    fun save(state: Map<String, VmState>) {
        file.parentFile?.mkdirs()
        val now = clock()
        val instances = JSONObject()
        state.forEach { (id, vmState) ->
            instances.put(
                id,
                JSONObject()
                    .put("state", vmState.name)
                    .put("lastChangedMillis", now),
            )
        }
        val payload = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("instances", instances)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload.toString(2))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun decode(text: String): Map<String, VmState> {
        val root = JSONObject(text)
        val version = root.optInt("version", -1)
        if (version != FORMAT_VERSION) {
            error("unsupported version $version")
        }
        val instances = root.optJSONObject("instances") ?: return emptyMap()
        val result = mutableMapOf<String, VmState>()
        for (key in instances.keys()) {
            val node = instances.optJSONObject(key) ?: continue
            val name = node.optString("state", "")
            if (name.isBlank()) continue
            val state = runCatching { VmState.valueOf(name) }.getOrNull() ?: continue
            result[key] = state
        }
        return result
    }

    private fun backupCorrupt(reason: String) {
        runCatching {
            val parent = file.parentFile ?: return@runCatching
            val backup = File(parent, "$BACKUP_PREFIX${clock()}.json")
            file.copyTo(backup, overwrite = true)
            file.delete()
            backup.appendText("\n# corruption=$reason\n")
        }
    }

    companion object {
        const val FILE_NAME = "runtime-state.json"
        const val FORMAT_VERSION = 1
        private const val BACKUP_PREFIX = "runtime-state.bak."
    }
}

package dev.jongwoo.androidvm.apk

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class GuestPackageInfo(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String?,
    val installedPath: String,
    val dataPath: String,
    val sha256: String?,
    val sourceName: String?,
    val installedAt: String,
    val updatedAt: String,
    val enabled: Boolean,
    val launchable: Boolean,
    val launcherActivity: String?,
    val nativeAbis: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("label", label)
        .put("versionCode", versionCode)
        .put("versionName", versionName ?: JSONObject.NULL)
        .put("installedPath", installedPath)
        .put("dataPath", dataPath)
        .put("sha256", sha256 ?: JSONObject.NULL)
        .put("sourceName", sourceName ?: JSONObject.NULL)
        .put("installedAt", installedAt)
        .put("updatedAt", updatedAt)
        .put("enabled", enabled)
        .put("launchable", launchable)
        .put("launcherActivity", launcherActivity ?: JSONObject.NULL)
        .put("nativeAbis", JSONArray().apply { nativeAbis.forEach { put(it) } })

    companion object {
        fun fromJson(json: JSONObject): GuestPackageInfo {
            val abis = json.optJSONArray("nativeAbis")
                ?.let { array -> List(array.length()) { i -> array.optString(i) } }
                ?: emptyList()
            return GuestPackageInfo(
                packageName = json.getString("packageName"),
                label = json.optString("label", json.getString("packageName")),
                versionCode = json.optLong("versionCode", 0L),
                versionName = json.optString("versionName").takeIf { it.isNotBlank() && !json.isNull("versionName") },
                installedPath = json.getString("installedPath"),
                dataPath = json.getString("dataPath"),
                sha256 = json.optString("sha256").takeIf { it.isNotBlank() && !json.isNull("sha256") },
                sourceName = json.optString("sourceName").takeIf { it.isNotBlank() && !json.isNull("sourceName") },
                installedAt = json.optString("installedAt", ""),
                updatedAt = json.optString("updatedAt", json.optString("installedAt", "")),
                enabled = json.optBoolean("enabled", true),
                launchable = json.optBoolean("launchable", false),
                launcherActivity = json.optString("launcherActivity").takeIf {
                    it.isNotBlank() && !json.isNull("launcherActivity")
                },
                nativeAbis = abis,
            )
        }
    }
}

data class PackageIndexSnapshot(
    val instanceId: String,
    val version: Int,
    val packages: List<GuestPackageInfo>,
    val updatedAt: String,
) {
    fun find(packageName: String): GuestPackageInfo? = packages.firstOrNull { it.packageName == packageName }

    fun upsert(entry: GuestPackageInfo, updatedAt: String): PackageIndexSnapshot {
        val replaced = packages.any { it.packageName == entry.packageName }
        val nextPackages = if (replaced) {
            packages.map { if (it.packageName == entry.packageName) entry else it }
        } else {
            packages + entry
        }
        return copy(packages = nextPackages, updatedAt = updatedAt)
    }

    fun remove(packageName: String, updatedAt: String): PackageIndexSnapshot {
        val nextPackages = packages.filterNot { it.packageName == packageName }
        return copy(packages = nextPackages, updatedAt = updatedAt)
    }

    fun toJson(): String = JSONObject()
        .put("version", version)
        .put("instanceId", instanceId)
        .put("updatedAt", updatedAt)
        .put(
            "packages",
            JSONArray().apply { packages.forEach { put(it.toJson()) } },
        )
        .toString(2)

    companion object {
        const val SCHEMA_VERSION = 1

        fun empty(instanceId: String, updatedAt: String): PackageIndexSnapshot = PackageIndexSnapshot(
            instanceId = instanceId,
            version = SCHEMA_VERSION,
            packages = emptyList(),
            updatedAt = updatedAt,
        )

        fun fromJson(text: String): PackageIndexSnapshot {
            val json = JSONObject(text)
            val instanceId = json.optString("instanceId", "")
            val version = json.optInt("version", SCHEMA_VERSION)
            val updatedAt = json.optString("updatedAt", "")
            val array = json.optJSONArray("packages")
            val packages = if (array == null) {
                emptyList()
            } else {
                List(array.length()) { i -> GuestPackageInfo.fromJson(array.getJSONObject(i)) }
            }
            return PackageIndexSnapshot(
                instanceId = instanceId,
                version = version,
                packages = packages,
                updatedAt = updatedAt,
            )
        }
    }
}

class PackageIndex(private val indexFile: File) {

    fun load(instanceId: String, fallbackUpdatedAt: String): PackageIndexSnapshot {
        if (!indexFile.exists()) {
            return PackageIndexSnapshot.empty(instanceId, fallbackUpdatedAt)
        }
        return runCatching {
            val text = indexFile.readText()
            if (text.isBlank()) {
                PackageIndexSnapshot.empty(instanceId, fallbackUpdatedAt)
            } else {
                PackageIndexSnapshot.fromJson(text).copy(instanceId = instanceId)
            }
        }.getOrDefault(PackageIndexSnapshot.empty(instanceId, fallbackUpdatedAt))
    }

    fun save(snapshot: PackageIndexSnapshot) {
        indexFile.parentFile?.mkdirs()
        val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
        tmp.writeText(snapshot.toJson())
        if (indexFile.exists() && !indexFile.delete()) {
            tmp.delete()
            throw IllegalStateException("Cannot delete existing index ${indexFile.absolutePath}")
        }
        if (!tmp.renameTo(indexFile)) {
            throw IllegalStateException("Cannot rename ${tmp.absolutePath} to ${indexFile.absolutePath}")
        }
    }
}

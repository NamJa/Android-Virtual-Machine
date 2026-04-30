package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.vm.VmNativeBridge
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase D guest-side PMS surface. The host APK calls into this contract through the JNI bridge so
 * the install pipeline talks to the guest PackageManagerService instead of stopping at host
 * metadata.
 *
 * The default implementation in production routes through [VmNativeBridge.installApkViaPms] /
 * [VmNativeBridge.listGuestPackages]. Tests substitute a [FakeGuestPmsClient].
 */
interface PhaseDPmsClient {
    fun installPackage(instanceId: String, stagedApkPath: String, flags: Int): PmsInstallResult
    fun listPackages(instanceId: String): List<PmsPackageEntry>
    fun launchActivity(instanceId: String, packageName: String, activity: String?): PmsLaunchResult
}

/**
 * Device implementation backed by the guest package / activity services exposed through JNI.
 *
 * The native side keeps PackageManagerService-shaped package state under the instance runtime
 * directory and only returns success after the package service has committed the entry. Launches
 * route through the ActivityManager-shaped runtime path used by Stage 6 foreground smoke tests.
 */
class NativePhaseDPmsClient : PhaseDPmsClient {
    override fun installPackage(
        instanceId: String,
        stagedApkPath: String,
        flags: Int,
    ): PmsInstallResult = PmsInstallResult.fromJson(
        VmNativeBridge.installApkViaPms(instanceId, stagedApkPath, flags),
    )

    override fun listPackages(instanceId: String): List<PmsPackageEntry> =
        PmsPackageEntry.listFromJson(VmNativeBridge.listGuestPackages(instanceId))

    override fun launchActivity(
        instanceId: String,
        packageName: String,
        activity: String?,
    ): PmsLaunchResult {
        val rc = VmNativeBridge.launchGuestActivity(instanceId, packageName, activity ?: "")
        val status = runCatching { JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId)) }
            .getOrDefault(JSONObject())
        if (rc != 0) {
            return PmsLaunchResult(
                status = PmsLaunchStatus.UNAVAILABLE,
                activity = null,
                message = status.optString("lastMessage", "launch_failed:$rc"),
            )
        }
        val launched = status.optString("foregroundPackage") == packageName &&
            status.optBoolean("foregroundAppProcessRunning", false) &&
            status.optBoolean("foregroundWindowAttached", false)
        return if (launched) {
            PmsLaunchResult(
                status = PmsLaunchStatus.LAUNCHED,
                activity = status.optString("foregroundActivity").takeIf { it.isNotBlank() },
                message = status.optString("lastMessage", "started"),
            )
        } else {
            PmsLaunchResult(
                status = PmsLaunchStatus.NOT_LAUNCHABLE,
                activity = null,
                message = status.optString("lastMessage", "activity_not_foreground"),
            )
        }
    }
}

enum class PmsInstallStatus {
    SUCCESS,
    FAILED_INVALID_APK,
    FAILED_INSUFFICIENT_STORAGE,
    FAILED_DEXOPT,
    FAILED_OTHER,
    UNAVAILABLE,
    ;

    val wireName: String
        get() = name.lowercase()

    companion object {
        fun fromWireName(value: String): PmsInstallStatus =
            entries.firstOrNull { it.wireName == value } ?: FAILED_OTHER
    }
}

data class PmsInstallResult(
    val status: PmsInstallStatus,
    val packageName: String?,
    val message: String,
) {
    val ok: Boolean get() = status == PmsInstallStatus.SUCCESS

    fun toJson(): String = JSONObject()
        .put("status", status.wireName)
        .put("packageName", packageName ?: JSONObject.NULL)
        .put("message", message)
        .toString()

    companion object {
        const val FLAG_REPLACE_EXISTING = 0x00000002
        const val FLAG_SKIP_DEXOPT = 0x00000080

        fun fromJson(text: String): PmsInstallResult {
            val o = runCatching { JSONObject(text) }.getOrNull()
                ?: return PmsInstallResult(PmsInstallStatus.FAILED_OTHER, null, "json_parse_failed")
            return PmsInstallResult(
                status = PmsInstallStatus.fromWireName(o.optString("status", "failed_other")),
                packageName = o.optString("packageName").takeIf {
                    it.isNotBlank() && !o.isNull("packageName")
                },
                message = o.optString("message", ""),
            )
        }
    }
}

data class PmsPackageEntry(
    val packageName: String,
    val versionCode: Long,
    val launcherActivity: String?,
    val enabled: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("packageName", packageName)
        .put("versionCode", versionCode)
        .put("launcherActivity", launcherActivity ?: JSONObject.NULL)
        .put("enabled", enabled)

    companion object {
        fun fromJson(json: JSONObject): PmsPackageEntry = PmsPackageEntry(
            packageName = json.getString("packageName"),
            versionCode = json.optLong("versionCode", 0L),
            launcherActivity = json.optString("launcherActivity").takeIf {
                it.isNotBlank() && !json.isNull("launcherActivity")
            },
            enabled = json.optBoolean("enabled", true),
        )

        fun listFromJson(text: String): List<PmsPackageEntry> {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
            val array = root.optJSONArray("packages") ?: return emptyList()
            return List(array.length()) { i -> fromJson(array.getJSONObject(i)) }
        }

        fun listToJson(entries: List<PmsPackageEntry>): String {
            val array = JSONArray()
            entries.forEach { array.put(it.toJson()) }
            return JSONObject().put("packages", array).toString()
        }
    }
}

enum class PmsLaunchStatus {
    LAUNCHED,
    NOT_FOUND,
    NOT_LAUNCHABLE,
    DISABLED,
    UNAVAILABLE,
    ;

    val wireName: String
        get() = name.lowercase()
}

data class PmsLaunchResult(
    val status: PmsLaunchStatus,
    val activity: String?,
    val message: String,
) {
    val ok: Boolean get() = status == PmsLaunchStatus.LAUNCHED
}

/**
 * Pure-JVM implementation that emulates the guest PMS for unit tests and the off-device Phase D
 * harness. Records every call so tests can assert routing.
 */
class FakeGuestPmsClient : PhaseDPmsClient {
    data class InstallCall(
        val instanceId: String,
        val stagedApkPath: String,
        val flags: Int,
    )

    private val packagesByInstance = mutableMapOf<String, MutableMap<String, PmsPackageEntry>>()
    private val installCalls = mutableListOf<InstallCall>()

    @Volatile
    var nextInstallStatus: PmsInstallStatus = PmsInstallStatus.SUCCESS

    @Volatile
    var nextInstallMessage: String = "ok"

    val callCount: Int
        get() = synchronized(installCalls) { installCalls.size }

    val installs: List<InstallCall>
        get() = synchronized(installCalls) { installCalls.toList() }

    fun seed(instanceId: String, entry: PmsPackageEntry) {
        packagesByInstance.getOrPut(instanceId) { mutableMapOf() }[entry.packageName] = entry
    }

    /** Test hook: register what packageName the next staged APK should resolve to. */
    var packageNameResolver: (String) -> String = { stagedPath ->
        // Pull "<package>" from a staging path of the form ".../staging/install-..-<package>/base.apk"
        // Otherwise fall back to a deterministic synthetic package name.
        val parent = stagedPath.substringBeforeLast('/').substringAfterLast('/')
        if (parent.startsWith("install-")) {
            parent.substringAfter('-').substringAfter('-')
        } else {
            "fake.pkg.${stagedPath.hashCode().toUInt()}"
        }
    }

    override fun installPackage(
        instanceId: String,
        stagedApkPath: String,
        flags: Int,
    ): PmsInstallResult {
        synchronized(installCalls) { installCalls += InstallCall(instanceId, stagedApkPath, flags) }
        if (nextInstallStatus != PmsInstallStatus.SUCCESS) {
            return PmsInstallResult(nextInstallStatus, null, nextInstallMessage)
        }
        val packageName = packageNameResolver(stagedApkPath)
        val table = packagesByInstance.getOrPut(instanceId) { mutableMapOf() }
        val entry = PmsPackageEntry(
            packageName = packageName,
            versionCode = 1L,
            launcherActivity = "$packageName.MainActivity",
            enabled = true,
        )
        table[packageName] = entry
        return PmsInstallResult(PmsInstallStatus.SUCCESS, packageName, "installed")
    }

    override fun listPackages(instanceId: String): List<PmsPackageEntry> =
        packagesByInstance[instanceId]?.values?.sortedBy { it.packageName } ?: emptyList()

    override fun launchActivity(
        instanceId: String,
        packageName: String,
        activity: String?,
    ): PmsLaunchResult {
        val entry = packagesByInstance[instanceId]?.get(packageName)
            ?: return PmsLaunchResult(PmsLaunchStatus.NOT_FOUND, null, "package_not_installed")
        if (!entry.enabled) {
            return PmsLaunchResult(PmsLaunchStatus.DISABLED, null, "package_disabled")
        }
        val target = activity ?: entry.launcherActivity
            ?: return PmsLaunchResult(PmsLaunchStatus.NOT_LAUNCHABLE, null, "no_launcher_activity")
        return PmsLaunchResult(PmsLaunchStatus.LAUNCHED, target, "started")
    }
}

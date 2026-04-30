package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Phase D.1 wrapper around [ApkInstaller] that routes the staged APK through the guest PMS via
 * [PhaseDPmsClient] and treats the guest as the single source of truth for package state.
 *
 * The host-side [PackageIndex] is still kept as a cache so the UI can list packages without paying
 * the JNI hop, but it is reconciled to the guest PMS result on every install and on `syncFromGuest`.
 */
class PmsInstallCoordinator(
    private val installer: ApkInstaller,
    private val pmsClient: PhaseDPmsClient,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Outcome(
        val installResult: ApkInstallResult,
        val pmsResult: PmsInstallResult,
        val mergedSnapshot: PackageIndexSnapshot,
        val pmsListed: Boolean,
    ) {
        val passed: Boolean
            get() = installResult.outcome in installSuccessOutcomes && pmsResult.ok && pmsListed

        companion object {
            private val installSuccessOutcomes = setOf(
                ApkInstallOutcome.INSTALLED,
                ApkInstallOutcome.UPDATED,
            )
        }
    }

    fun install(
        instancePaths: InstancePaths,
        stagedApk: File,
        sourceName: String?,
        installFlags: Int = PmsInstallResult.FLAG_REPLACE_EXISTING or PmsInstallResult.FLAG_SKIP_DEXOPT,
    ): Outcome {
        val installResult = installer.install(
            instancePaths = instancePaths,
            stagedApk = stagedApk,
            sourceName = sourceName,
        )
        if (installResult.outcome != ApkInstallOutcome.INSTALLED &&
            installResult.outcome != ApkInstallOutcome.UPDATED
        ) {
            return Outcome(
                installResult = installResult,
                pmsResult = PmsInstallResult(
                    PmsInstallStatus.UNAVAILABLE,
                    null,
                    "host_install_failed:${installResult.outcome.name}",
                ),
                mergedSnapshot = loadSnapshot(instancePaths),
                pmsListed = false,
            )
        }
        val installedApk = installResult.packageInfo!!.installedPath
        val pmsResult = runCatching {
            pmsClient.installPackage(instancePaths.id, installedApk, installFlags)
        }.getOrElse { cause ->
            PmsInstallResult(
                status = PmsInstallStatus.UNAVAILABLE,
                packageName = installResult.packageInfo.packageName,
                message = "pms_unavailable:${cause.javaClass.simpleName}",
            )
        }
        appendLog(
            instancePaths,
            "PMS_INSTALL ${installResult.packageInfo.packageName} status=${pmsResult.status.wireName} message=${pmsResult.message}",
        )
        val merged = syncFromGuest(instancePaths)
        val pmsListed = merged.find(installResult.packageInfo.packageName) != null
        return Outcome(installResult, pmsResult, merged, pmsListed && pmsResult.ok)
    }

    /**
     * Merges the guest PMS view back into the host package index. Entries that exist on the host
     * but not on the guest are dropped (the guest is authoritative); entries that exist on the
     * guest but not on the host are kept as-is on the host because we do not have the full APK
     * metadata for them.
     */
    fun syncFromGuest(instancePaths: InstancePaths): PackageIndexSnapshot {
        val nowIso = formatTimestampUtc(clock())
        val index = PackageIndex(ApkInstaller.packageIndexFile(instancePaths))
        val current = index.load(instancePaths.id, nowIso)
        val guestEntries = runCatching { pmsClient.listPackages(instancePaths.id) }.getOrDefault(emptyList())
        val guestNames = guestEntries.map { it.packageName }.toSet()
        val pruned = current.packages.filter { it.packageName in guestNames }
        val byName = pruned.associateBy { it.packageName }
        val merged = guestEntries.map { entry ->
            val existing = byName[entry.packageName]
            existing?.copy(
                enabled = entry.enabled,
                launcherActivity = entry.launcherActivity ?: existing.launcherActivity,
                versionCode = if (entry.versionCode > 0) entry.versionCode else existing.versionCode,
                updatedAt = nowIso,
            ) ?: GuestPackageInfo(
                packageName = entry.packageName,
                label = entry.packageName,
                versionCode = entry.versionCode,
                versionName = null,
                installedPath = "",
                dataPath = File(instancePaths.dataDir, "data/${entry.packageName}").absolutePath,
                sha256 = null,
                sourceName = "guest_pms",
                installedAt = nowIso,
                updatedAt = nowIso,
                enabled = entry.enabled,
                launchable = entry.launcherActivity != null,
                launcherActivity = entry.launcherActivity,
                nativeAbis = emptyList(),
            )
        }
        val snapshot = current.copy(packages = merged, updatedAt = nowIso)
        runCatching { index.save(snapshot) }
        return snapshot
    }

    private fun loadSnapshot(instancePaths: InstancePaths): PackageIndexSnapshot {
        val nowIso = formatTimestampUtc(clock())
        return PackageIndex(ApkInstaller.packageIndexFile(instancePaths)).load(instancePaths.id, nowIso)
    }

    private fun appendLog(instancePaths: InstancePaths, line: String) {
        val logFile = ApkInstaller.installLogFile(instancePaths)
        runCatching {
            logFile.parentFile?.mkdirs()
            val timestamp = formatTimestampUtc(clock())
            logFile.appendText("$timestamp $line\n")
        }
    }

    private fun formatTimestampUtc(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }
}

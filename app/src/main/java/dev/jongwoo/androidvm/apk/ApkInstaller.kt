package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class ApkInstallPhase {
    INSPECT,
    PREPARE_TRANSACTION,
    COPY_BASE_APK,
    PREPARE_DATA_DIR,
    COMMIT_PACKAGE,
    UPDATE_INDEX,
    APPEND_LOG,
    DONE,
}

data class ApkInstallProgress(
    val phase: ApkInstallPhase,
    val packageName: String?,
    val message: String,
)

enum class ApkInstallOutcome {
    INSTALLED,
    UPDATED,
    INSPECT_FAILED,
    IO_ERROR,
    INDEX_ERROR,
    DUPLICATE_REJECTED,
}

data class ApkInstallResult(
    val outcome: ApkInstallOutcome,
    val packageInfo: GuestPackageInfo?,
    val previousPackageInfo: GuestPackageInfo?,
    val errorCode: String?,
    val message: String,
)

object ApkInstallErrorCode {
    const val INSPECT_FAILED = "INSTALL_INSPECT_FAILED"
    const val IO_ERROR = "INSTALL_IO_ERROR"
    const val INDEX_ERROR = "INSTALL_INDEX_ERROR"
    const val DUPLICATE_REJECTED = "INSTALL_DUPLICATE_REJECTED"
    const val LAUNCH_FAILED = "INSTALL_LAUNCH_FAILED"
}

enum class DuplicateInstallPolicy { UPDATE, REJECT }

class ApkInstaller(
    private val inspector: ApkInspector,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun install(
        instancePaths: InstancePaths,
        stagedApk: File,
        sourceName: String?,
        stagedSha256: String? = null,
        duplicatePolicy: DuplicateInstallPolicy = DuplicateInstallPolicy.UPDATE,
        onProgress: (ApkInstallProgress) -> Unit = {},
    ): ApkInstallResult {
        if (!stagedApk.exists()) {
            return failure(
                outcome = ApkInstallOutcome.IO_ERROR,
                errorCode = ApkInstallErrorCode.IO_ERROR,
                message = "Staged APK not found: ${stagedApk.absolutePath}",
            )
        }

        onProgress(ApkInstallProgress(ApkInstallPhase.INSPECT, null, "Inspecting ${stagedApk.name}"))
        val inspection = when (val outcome = inspector.inspect(stagedApk)) {
            is ApkInspectionOutcome.Failed -> {
                appendLog(
                    instancePaths = instancePaths,
                    line = "INSPECT_FAILED ${stagedApk.name} ${outcome.errorCode} ${outcome.message}",
                )
                return failure(
                    outcome = ApkInstallOutcome.INSPECT_FAILED,
                    errorCode = ApkInstallErrorCode.INSPECT_FAILED,
                    message = outcome.message,
                )
            }
            is ApkInspectionOutcome.Success -> outcome.info
        }

        val packageName = inspection.packageName
        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.PREPARE_TRANSACTION,
                packageName = packageName,
                message = "Preparing install transaction",
            ),
        )

        val packageIndex = PackageIndex(packageIndexFile(instancePaths))
        val nowIso = formatTimestampUtc(clock())
        val snapshot = packageIndex.load(instancePaths.id, nowIso)
        val previous = snapshot.find(packageName)
        if (previous != null && duplicatePolicy == DuplicateInstallPolicy.REJECT) {
            return ApkInstallResult(
                outcome = ApkInstallOutcome.DUPLICATE_REJECTED,
                packageInfo = previous,
                previousPackageInfo = previous,
                errorCode = ApkInstallErrorCode.DUPLICATE_REJECTED,
                message = "Package $packageName already installed; reject policy",
            )
        }

        val txnDir = File(instancePaths.stagingDir, "install-${clock()}-${sanitize(packageName)}")
        txnDir.deleteRecursively()
        if (!txnDir.mkdirs()) {
            return failure(
                outcome = ApkInstallOutcome.IO_ERROR,
                errorCode = ApkInstallErrorCode.IO_ERROR,
                message = "Cannot create transaction dir ${txnDir.absolutePath}",
            )
        }

        val txnApk = File(txnDir, "base.apk")
        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.COPY_BASE_APK,
                packageName = packageName,
                message = "Copying base.apk to transaction",
            ),
        )

        val sha256 = try {
            copyAndDigest(stagedApk, txnApk)
        } catch (error: IOException) {
            txnDir.deleteRecursively()
            return failure(
                outcome = ApkInstallOutcome.IO_ERROR,
                errorCode = ApkInstallErrorCode.IO_ERROR,
                message = error.message ?: "Failed to copy staged APK into transaction",
            )
        }
        if (stagedSha256 != null && !sha256.equals(stagedSha256, ignoreCase = true)) {
            txnDir.deleteRecursively()
            return failure(
                outcome = ApkInstallOutcome.IO_ERROR,
                errorCode = ApkInstallErrorCode.IO_ERROR,
                message = "Staged APK sha256 mismatch (expected $stagedSha256, got $sha256)",
            )
        }

        val appDir = File(instancePaths.dataDir, "app/$packageName")
        val installedApk = File(appDir, "base.apk")
        val dataDir = File(instancePaths.dataDir, "data/$packageName")

        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.PREPARE_DATA_DIR,
                packageName = packageName,
                message = "Preparing data directory",
            ),
        )
        if (!ensureDirs(File(instancePaths.dataDir, "app"), appDir, File(instancePaths.dataDir, "data"), dataDir)) {
            txnDir.deleteRecursively()
            return failure(
                outcome = ApkInstallOutcome.IO_ERROR,
                errorCode = ApkInstallErrorCode.IO_ERROR,
                message = "Cannot create guest data directories",
            )
        }

        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.COMMIT_PACKAGE,
                packageName = packageName,
                message = "Committing $packageName",
            ),
        )
        if (installedApk.exists() && !installedApk.delete()) {
            txnDir.deleteRecursively()
            return failure(
                outcome = ApkInstallOutcome.IO_ERROR,
                errorCode = ApkInstallErrorCode.IO_ERROR,
                message = "Cannot remove previous base.apk for $packageName",
            )
        }
        if (!txnApk.renameTo(installedApk)) {
            try {
                txnApk.copyTo(installedApk, overwrite = true)
                txnApk.delete()
            } catch (error: Throwable) {
                txnDir.deleteRecursively()
                return failure(
                    outcome = ApkInstallOutcome.IO_ERROR,
                    errorCode = ApkInstallErrorCode.IO_ERROR,
                    message = error.message ?: "Failed to commit base.apk",
                )
            }
        }
        txnDir.deleteRecursively()

        val installedAt = previous?.installedAt ?: nowIso
        val packageInfo = GuestPackageInfo(
            packageName = packageName,
            label = inspection.label ?: packageName,
            versionCode = inspection.versionCode,
            versionName = inspection.versionName,
            installedPath = installedApk.absolutePath,
            dataPath = dataDir.absolutePath,
            sha256 = sha256,
            sourceName = sourceName,
            installedAt = installedAt,
            updatedAt = nowIso,
            enabled = true,
            launchable = inspection.launcherActivity != null,
            launcherActivity = inspection.launcherActivity,
            nativeAbis = inspection.nativeAbis.toList().sorted(),
        )

        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.UPDATE_INDEX,
                packageName = packageName,
                message = "Updating package index",
            ),
        )
        try {
            packageIndex.save(snapshot.upsert(packageInfo, nowIso))
        } catch (error: Throwable) {
            // Roll back the file move so the state stays coherent.
            installedApk.delete()
            return failure(
                outcome = ApkInstallOutcome.INDEX_ERROR,
                errorCode = ApkInstallErrorCode.INDEX_ERROR,
                message = error.message ?: "Failed to persist package index",
            )
        }

        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.APPEND_LOG,
                packageName = packageName,
                message = "Appending install log",
            ),
        )
        val outcome = if (previous == null) ApkInstallOutcome.INSTALLED else ApkInstallOutcome.UPDATED
        appendLog(
            instancePaths = instancePaths,
            line = "${outcome.name} $packageName versionCode=${inspection.versionCode} sha256=$sha256",
        )

        onProgress(
            ApkInstallProgress(
                phase = ApkInstallPhase.DONE,
                packageName = packageName,
                message = "Done",
            ),
        )

        return ApkInstallResult(
            outcome = outcome,
            packageInfo = packageInfo,
            previousPackageInfo = previous,
            errorCode = null,
            message = if (previous == null) "Installed $packageName" else "Updated $packageName",
        )
    }

    private fun copyAndDigest(source: File, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun appendLog(instancePaths: InstancePaths, line: String) {
        val logFile = installLogFile(instancePaths)
        runCatching {
            logFile.parentFile?.mkdirs()
            val timestamp = formatTimestampUtc(clock())
            logFile.appendText("$timestamp $line\n")
        }
    }

    private fun ensureDirs(vararg dirs: File): Boolean = dirs.all { it.exists() || it.mkdirs() }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun failure(
        outcome: ApkInstallOutcome,
        errorCode: String,
        message: String,
    ): ApkInstallResult = ApkInstallResult(
        outcome = outcome,
        packageInfo = null,
        previousPackageInfo = null,
        errorCode = errorCode,
        message = message,
    )

    private fun formatTimestampUtc(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            builder.append(HEX[v ushr 4])
            builder.append(HEX[v and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()

        fun packageIndexFile(instancePaths: InstancePaths): File =
            File(instancePaths.runtimeDir, "package-index.json")

        fun installLogFile(instancePaths: InstancePaths): File =
            File(instancePaths.logsDir, "package_install.log")
    }
}

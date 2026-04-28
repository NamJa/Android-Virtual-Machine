package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class PackageOperationType { UNINSTALL, CLEAR_DATA, SET_ENABLED }

enum class PackageOperationOutcome { SUCCESS, NOT_FOUND, IO_ERROR, INDEX_ERROR }

data class PackageOperationResult(
    val type: PackageOperationType,
    val packageName: String,
    val outcome: PackageOperationOutcome,
    val message: String,
    val errorCode: String? = null,
    val packageInfoBefore: GuestPackageInfo? = null,
    val packageInfoAfter: GuestPackageInfo? = null,
)

object PackageOperationErrorCode {
    const val NOT_FOUND = "PACKAGE_NOT_FOUND"
    const val IO_ERROR = "PACKAGE_IO_ERROR"
    const val INDEX_ERROR = "PACKAGE_INDEX_ERROR"
}

class PackageOperations(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun uninstall(instancePaths: InstancePaths, packageName: String): PackageOperationResult {
        val nowIso = formatTimestampUtc(clock())
        val index = PackageIndex(ApkInstaller.packageIndexFile(instancePaths))
        val snapshot = index.load(instancePaths.id, nowIso)
        val previous = snapshot.find(packageName) ?: return PackageOperationResult(
            type = PackageOperationType.UNINSTALL,
            packageName = packageName,
            outcome = PackageOperationOutcome.NOT_FOUND,
            errorCode = PackageOperationErrorCode.NOT_FOUND,
            message = "Package $packageName is not installed",
        )

        val appDir = File(instancePaths.dataDir, "app/$packageName")
        val dataDir = File(instancePaths.dataDir, "data/$packageName")
        val ioFailed = mutableListOf<String>()
        if (appDir.exists() && !appDir.deleteRecursively()) ioFailed += appDir.absolutePath
        if (dataDir.exists() && !dataDir.deleteRecursively()) ioFailed += dataDir.absolutePath
        if (ioFailed.isNotEmpty()) {
            appendLog(instancePaths, "UNINSTALL_FAILED $packageName ${ioFailed.joinToString(",")}")
            return PackageOperationResult(
                type = PackageOperationType.UNINSTALL,
                packageName = packageName,
                outcome = PackageOperationOutcome.IO_ERROR,
                errorCode = PackageOperationErrorCode.IO_ERROR,
                message = "Cannot delete: ${ioFailed.joinToString(", ")}",
                packageInfoBefore = previous,
            )
        }
        try {
            index.save(snapshot.remove(packageName, nowIso))
        } catch (error: Throwable) {
            appendLog(instancePaths, "UNINSTALL_INDEX_FAILED $packageName ${error.message}")
            return PackageOperationResult(
                type = PackageOperationType.UNINSTALL,
                packageName = packageName,
                outcome = PackageOperationOutcome.INDEX_ERROR,
                errorCode = PackageOperationErrorCode.INDEX_ERROR,
                message = error.message ?: "Failed to persist package index",
                packageInfoBefore = previous,
            )
        }
        appendLog(instancePaths, "UNINSTALLED $packageName")
        return PackageOperationResult(
            type = PackageOperationType.UNINSTALL,
            packageName = packageName,
            outcome = PackageOperationOutcome.SUCCESS,
            message = "Uninstalled $packageName",
            packageInfoBefore = previous,
        )
    }

    fun clearData(instancePaths: InstancePaths, packageName: String): PackageOperationResult {
        val nowIso = formatTimestampUtc(clock())
        val index = PackageIndex(ApkInstaller.packageIndexFile(instancePaths))
        val snapshot = index.load(instancePaths.id, nowIso)
        val previous = snapshot.find(packageName) ?: return PackageOperationResult(
            type = PackageOperationType.CLEAR_DATA,
            packageName = packageName,
            outcome = PackageOperationOutcome.NOT_FOUND,
            errorCode = PackageOperationErrorCode.NOT_FOUND,
            message = "Package $packageName is not installed",
        )

        val dataDir = File(instancePaths.dataDir, "data/$packageName")
        val children = dataDir.listFiles().orEmpty()
        val ioFailed = mutableListOf<String>()
        for (child in children) {
            if (!child.deleteRecursively()) ioFailed += child.absolutePath
        }
        if (!dataDir.exists() && !dataDir.mkdirs()) ioFailed += dataDir.absolutePath
        if (ioFailed.isNotEmpty()) {
            appendLog(instancePaths, "CLEAR_DATA_FAILED $packageName ${ioFailed.joinToString(",")}")
            return PackageOperationResult(
                type = PackageOperationType.CLEAR_DATA,
                packageName = packageName,
                outcome = PackageOperationOutcome.IO_ERROR,
                errorCode = PackageOperationErrorCode.IO_ERROR,
                message = "Cannot clear: ${ioFailed.joinToString(", ")}",
                packageInfoBefore = previous,
            )
        }
        val updated = previous.copy(updatedAt = nowIso)
        try {
            index.save(snapshot.upsert(updated, nowIso))
        } catch (error: Throwable) {
            appendLog(instancePaths, "CLEAR_DATA_INDEX_FAILED $packageName ${error.message}")
            return PackageOperationResult(
                type = PackageOperationType.CLEAR_DATA,
                packageName = packageName,
                outcome = PackageOperationOutcome.INDEX_ERROR,
                errorCode = PackageOperationErrorCode.INDEX_ERROR,
                message = error.message ?: "Failed to persist package index",
                packageInfoBefore = previous,
            )
        }
        appendLog(instancePaths, "CLEARED_DATA $packageName")
        return PackageOperationResult(
            type = PackageOperationType.CLEAR_DATA,
            packageName = packageName,
            outcome = PackageOperationOutcome.SUCCESS,
            message = "Cleared data for $packageName",
            packageInfoBefore = previous,
            packageInfoAfter = updated,
        )
    }

    fun setEnabled(
        instancePaths: InstancePaths,
        packageName: String,
        enabled: Boolean,
    ): PackageOperationResult {
        val nowIso = formatTimestampUtc(clock())
        val index = PackageIndex(ApkInstaller.packageIndexFile(instancePaths))
        val snapshot = index.load(instancePaths.id, nowIso)
        val previous = snapshot.find(packageName) ?: return PackageOperationResult(
            type = PackageOperationType.SET_ENABLED,
            packageName = packageName,
            outcome = PackageOperationOutcome.NOT_FOUND,
            errorCode = PackageOperationErrorCode.NOT_FOUND,
            message = "Package $packageName is not installed",
        )
        val updated = previous.copy(enabled = enabled, updatedAt = nowIso)
        try {
            index.save(snapshot.upsert(updated, nowIso))
        } catch (error: Throwable) {
            appendLog(instancePaths, "SET_ENABLED_FAILED $packageName enabled=$enabled ${error.message}")
            return PackageOperationResult(
                type = PackageOperationType.SET_ENABLED,
                packageName = packageName,
                outcome = PackageOperationOutcome.INDEX_ERROR,
                errorCode = PackageOperationErrorCode.INDEX_ERROR,
                message = error.message ?: "Failed to persist package index",
                packageInfoBefore = previous,
            )
        }
        appendLog(
            instancePaths,
            if (enabled) "ENABLED $packageName" else "DISABLED $packageName",
        )
        return PackageOperationResult(
            type = PackageOperationType.SET_ENABLED,
            packageName = packageName,
            outcome = PackageOperationOutcome.SUCCESS,
            message = if (enabled) "Enabled $packageName" else "Disabled $packageName",
            packageInfoBefore = previous,
            packageInfoAfter = updated,
        )
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

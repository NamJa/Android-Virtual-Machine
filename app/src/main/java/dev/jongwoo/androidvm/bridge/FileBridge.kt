package dev.jongwoo.androidvm.bridge

import dev.jongwoo.androidvm.apk.FileExportOutcome
import dev.jongwoo.androidvm.apk.FileExportResult
import dev.jongwoo.androidvm.apk.FileExporter
import dev.jongwoo.androidvm.apk.FileImportErrorCode
import dev.jongwoo.androidvm.apk.FileImportResult
import dev.jongwoo.androidvm.apk.FileStager
import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.io.InputStream

/**
 * Phase D.8 file import/export bridge for non-APK content. Policy here is binary (enabled vs.
 * disabled) and lives outside [BridgePolicyStore] because the host UI flow is SAF-driven (the
 * user picks a source/destination URI per operation).
 *
 * Size limits are enforced at the [FileStager] level; the bridge just chooses the staging area
 * subdirectory so general files do not collide with APK staging slots, and audits the result
 * with the redaction guarantees Stage 7 already provides.
 */
class FileBridge(
    private val auditLog: BridgeAuditLog,
    private val stager: FileStager = FileStager(),
    private val exporter: FileExporter = FileExporter(),
    private val sizeLimitBytes: Long = DEFAULT_SIZE_LIMIT_BYTES,
) {
    enum class Direction { IMPORT, EXPORT }

    @Volatile
    private var enabled: Boolean = true

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun isEnabled(): Boolean = enabled

    /**
     * Stages a host file into `<instance>/staging/files/`. Returns a [FileImportResult] whose
     * `success` flag mirrors [FileBridgeResponse.allowed]. The audit log records source name and
     * sha256 hash, never the file contents.
     */
    fun import(
        instancePaths: InstancePaths,
        sourceName: String,
        totalBytes: Long? = null,
        source: () -> InputStream,
    ): FileBridgeResponse {
        if (!enabled) {
            audit(instancePaths.id, Direction.IMPORT, allowed = false, "file_bridge_disabled")
            return FileBridgeResponse.Denied("file_bridge_disabled")
        }
        val stagingRoot = File(instancePaths.stagingDir, FILES_STAGING_SUBDIR).apply { mkdirs() }
        val result = stager.stage(
            stagingDir = stagingRoot,
            sourceName = sourceName,
            sizeLimitBytes = sizeLimitBytes,
            totalBytes = totalBytes,
            source = source,
        )
        return when {
            result.success -> {
                audit(instancePaths.id, Direction.IMPORT, allowed = true,
                    "import_ok name=${result.sourceName} size=${result.sizeBytes}")
                FileBridgeResponse.Imported(result)
            }
            result.errorCode == FileImportErrorCode.SIZE_EXCEEDED -> {
                audit(instancePaths.id, Direction.IMPORT, allowed = false, "size_exceeded")
                FileBridgeResponse.Denied("file_too_large")
            }
            else -> {
                audit(instancePaths.id, Direction.IMPORT, allowed = false, "import_failed:${result.errorCode}")
                FileBridgeResponse.Failed(result.errorCode ?: "unknown", result.message)
            }
        }
    }

    /**
     * Copies a guest file (already inside the rootfs) into the per-instance export directory so
     * the host UI can hand it off via SAF. Path-traversal is rejected by [FileExporter].
     */
    fun export(
        instancePaths: InstancePaths,
        rootfsRelativePath: String,
        exportName: String? = null,
    ): FileBridgeResponse {
        if (!enabled) {
            audit(instancePaths.id, Direction.EXPORT, allowed = false, "file_bridge_disabled")
            return FileBridgeResponse.Denied("file_bridge_disabled")
        }
        val result = exporter.exportFromRootfs(instancePaths, rootfsRelativePath, exportName)
        return when (result.outcome) {
            FileExportOutcome.OK -> {
                audit(instancePaths.id, Direction.EXPORT, allowed = true,
                    "export_ok size=${result.sizeBytes}")
                FileBridgeResponse.Exported(result)
            }
            FileExportOutcome.ESCAPE_REJECTED -> {
                audit(instancePaths.id, Direction.EXPORT, allowed = false, "escape_rejected")
                FileBridgeResponse.Denied("escape_rejected")
            }
            FileExportOutcome.NOT_FOUND -> {
                audit(instancePaths.id, Direction.EXPORT, allowed = false, "not_found")
                FileBridgeResponse.Denied("not_found")
            }
            FileExportOutcome.IO_ERROR -> {
                audit(instancePaths.id, Direction.EXPORT, allowed = false, "io_error")
                FileBridgeResponse.Failed(result.errorCode ?: "io_error", result.message)
            }
        }
    }

    private fun audit(
        instanceId: String,
        direction: Direction,
        allowed: Boolean,
        reason: String,
    ) {
        // Reuse the existing bridge audit channel via DEVICE_PROFILE-shaped entries; the operation
        // string carries the direction so the log stays grep-able.
        val op = if (direction == Direction.IMPORT) "file_import" else "file_export"
        val decision = if (allowed) BridgeDecision.allowed(reason) else BridgeDecision.denied(reason)
        // Use AUDIO_OUTPUT bucket as a transport-only category so we don't have to extend
        // BridgeType for Phase D. The reason string preserves the file-bridge identity.
        auditLog.appendDecision(
            instanceId = instanceId,
            bridge = BridgeType.AUDIO_OUTPUT,
            operation = "file_bridge_$op",
            decision = decision,
        )
    }

    sealed class FileBridgeResponse {
        abstract val allowed: Boolean
        abstract val message: String

        data class Imported(val result: FileImportResult) : FileBridgeResponse() {
            override val allowed: Boolean = true
            override val message: String = result.message
        }

        data class Exported(val result: FileExportResult) : FileBridgeResponse() {
            override val allowed: Boolean = true
            override val message: String = result.message
        }

        data class Denied(val reason: String) : FileBridgeResponse() {
            override val allowed: Boolean = false
            override val message: String = reason
        }

        data class Failed(val errorCode: String, override val message: String) : FileBridgeResponse() {
            override val allowed: Boolean = false
        }
    }

    companion object {
        const val DEFAULT_SIZE_LIMIT_BYTES: Long = 256L * 1024L * 1024L
        const val FILES_STAGING_SUBDIR: String = "files"
    }
}

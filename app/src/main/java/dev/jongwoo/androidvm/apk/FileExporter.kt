package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest

enum class FileExportOutcome { OK, NOT_FOUND, ESCAPE_REJECTED, IO_ERROR }

data class FileExportResult(
    val outcome: FileExportOutcome,
    val exportedPath: String?,
    val sha256: String?,
    val sizeBytes: Long?,
    val errorCode: String?,
    val message: String,
)

object FileExportErrorCode {
    const val NOT_FOUND = "EXPORT_NOT_FOUND"
    const val ESCAPE_REJECTED = "EXPORT_ESCAPE_REJECTED"
    const val IO_ERROR = "EXPORT_IO_ERROR"
}

/**
 * Copies a file from the guest rootfs to the per-instance export directory so
 * the host UI can hand it off via SAF (`ACTION_CREATE_DOCUMENT`). Validates
 * that the resolved source path stays under `rootfsDir` to prevent symlink or
 * `..` based escape attempts.
 */
class FileExporter {

    fun exportFromRootfs(
        instancePaths: InstancePaths,
        rootfsRelativePath: String,
        exportName: String? = null,
    ): FileExportResult {
        val rootfsCanonical = try {
            instancePaths.rootfsDir.canonicalFile
        } catch (error: IOException) {
            return failure(
                FileExportOutcome.IO_ERROR,
                FileExportErrorCode.IO_ERROR,
                error.message ?: "Cannot resolve rootfs directory",
            )
        }
        val candidate = File(rootfsCanonical, rootfsRelativePath.removePrefix("/"))
        val canonicalSource = try {
            candidate.canonicalFile
        } catch (error: IOException) {
            return failure(
                FileExportOutcome.IO_ERROR,
                FileExportErrorCode.IO_ERROR,
                error.message ?: "Cannot canonicalise source path",
            )
        }
        if (!canonicalSource.path.startsWith(rootfsCanonical.path + File.separator) &&
            canonicalSource.path != rootfsCanonical.path
        ) {
            return failure(
                FileExportOutcome.ESCAPE_REJECTED,
                FileExportErrorCode.ESCAPE_REJECTED,
                "Source path resolves outside rootfs: ${canonicalSource.path}",
            )
        }
        if (!canonicalSource.exists()) {
            return failure(
                FileExportOutcome.NOT_FOUND,
                FileExportErrorCode.NOT_FOUND,
                "Source file not found: $rootfsRelativePath",
            )
        }
        if (!canonicalSource.isFile) {
            return failure(
                FileExportOutcome.IO_ERROR,
                FileExportErrorCode.IO_ERROR,
                "Source is not a regular file: ${canonicalSource.path}",
            )
        }

        val resolvedName = (exportName ?: canonicalSource.name).let(::sanitize)
        val destination = File(instancePaths.exportDir, resolvedName)
        if (!instancePaths.exportDir.exists() && !instancePaths.exportDir.mkdirs()) {
            return failure(
                FileExportOutcome.IO_ERROR,
                FileExportErrorCode.IO_ERROR,
                "Cannot create export directory ${instancePaths.exportDir.absolutePath}",
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            canonicalSource.inputStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        size += read
                    }
                }
            }
        } catch (error: IOException) {
            destination.delete()
            return failure(
                FileExportOutcome.IO_ERROR,
                FileExportErrorCode.IO_ERROR,
                error.message ?: "Failed to copy file to export directory",
            )
        }

        val sha = digest.digest().toHex()
        return FileExportResult(
            outcome = FileExportOutcome.OK,
            exportedPath = destination.absolutePath,
            sha256 = sha,
            sizeBytes = size,
            errorCode = null,
            message = "Exported ${canonicalSource.name} to ${destination.name}",
        )
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "exported.bin" }.take(120)
    }

    private fun failure(
        outcome: FileExportOutcome,
        errorCode: String,
        message: String,
    ): FileExportResult = FileExportResult(
        outcome = outcome,
        exportedPath = null,
        sha256 = null,
        sizeBytes = null,
        errorCode = errorCode,
        message = message,
    )

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
    }
}

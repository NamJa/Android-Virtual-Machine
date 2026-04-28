package dev.jongwoo.androidvm.apk

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class FileImportPhase { OPEN_SOURCE, COPY_TO_STAGING, HASH, WRITE_METADATA, DONE }

data class FileImportProgress(
    val phase: FileImportPhase,
    val bytesCopied: Long,
    val totalBytes: Long?,
    val message: String,
)

data class FileImportResult(
    val success: Boolean,
    val stagedPath: String?,
    val metadataPath: String?,
    val errorCode: String?,
    val message: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val sourceName: String? = null,
)

object FileImportErrorCode {
    const val OPEN_FAILED = "OPEN_FAILED"
    const val SIZE_EXCEEDED = "SIZE_EXCEEDED"
    const val EMPTY_FILE = "EMPTY_FILE"
    const val IO_ERROR = "IO_ERROR"
    const val STAGING_DIR_FAILED = "STAGING_DIR_FAILED"
}

/**
 * Stages arbitrary user-selected files (non-APK) inside the instance staging
 * directory. Uses a distinct slot prefix (`file_NNNN`) so it does not collide
 * with the APK pipeline's `import_NNNN.apk` files.
 */
class FileStager {

    fun stage(
        stagingDir: File,
        sourceName: String,
        sizeLimitBytes: Long,
        totalBytes: Long? = null,
        source: () -> InputStream,
        clock: () -> Long = System::currentTimeMillis,
        onProgress: (FileImportProgress) -> Unit = {},
    ): FileImportResult {
        val tmpDir = File(stagingDir, ApkStager.TMP_DIR_NAME)
        if (!ensureDirs(stagingDir, tmpDir)) {
            return failure(
                errorCode = FileImportErrorCode.STAGING_DIR_FAILED,
                message = "Cannot create staging directories at ${stagingDir.absolutePath}",
                sourceName = sourceName,
            )
        }

        val slot = nextSlot(stagingDir)
        val finalFile = File(stagingDir, "file_${slot}_${sanitize(sourceName)}")
        val finalMeta = File(stagingDir, "file_${slot}.json")
        val tmpFile = File(tmpDir, "file_${slot}.bin.tmp")
        val tmpMeta = File(tmpDir, "file_${slot}.json.tmp")
        tmpFile.delete()
        tmpMeta.delete()

        onProgress(
            FileImportProgress(
                phase = FileImportPhase.OPEN_SOURCE,
                bytesCopied = 0L,
                totalBytes = totalBytes,
                message = "Opening $sourceName",
            ),
        )
        val stream = try {
            source()
        } catch (error: Throwable) {
            return failure(
                errorCode = FileImportErrorCode.OPEN_FAILED,
                message = error.message ?: "Failed to open source stream",
                sourceName = sourceName,
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var bytesCopied = 0L
        try {
            stream.use { input ->
                tmpFile.outputStream().use { output ->
                    onProgress(
                        FileImportProgress(
                            phase = FileImportPhase.COPY_TO_STAGING,
                            bytesCopied = 0L,
                            totalBytes = totalBytes,
                            message = "Copying to ${tmpFile.name}",
                        ),
                    )
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        bytesCopied += read
                        if (bytesCopied > sizeLimitBytes) {
                            cleanup(tmpFile, tmpMeta)
                            return failure(
                                errorCode = FileImportErrorCode.SIZE_EXCEEDED,
                                message = "File exceeds size limit $sizeLimitBytes bytes",
                                sourceName = sourceName,
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(
                            FileImportProgress(
                                phase = FileImportPhase.COPY_TO_STAGING,
                                bytesCopied = bytesCopied,
                                totalBytes = totalBytes,
                                message = "Copied $bytesCopied bytes",
                            ),
                        )
                    }
                }
            }
        } catch (error: IOException) {
            cleanup(tmpFile, tmpMeta)
            return failure(
                errorCode = FileImportErrorCode.IO_ERROR,
                message = error.message ?: "I/O error while copying file",
                sourceName = sourceName,
            )
        }

        if (bytesCopied == 0L) {
            cleanup(tmpFile, tmpMeta)
            return failure(
                errorCode = FileImportErrorCode.EMPTY_FILE,
                message = "Source $sourceName is empty",
                sourceName = sourceName,
            )
        }

        val sha256 = digest.digest().toHex()
        onProgress(
            FileImportProgress(
                phase = FileImportPhase.HASH,
                bytesCopied = bytesCopied,
                totalBytes = totalBytes,
                message = "Computed sha256",
            ),
        )

        val createdAt = formatTimestampUtc(clock())
        val metadata = JSONObject()
            .put("kind", "file")
            .put("sourceName", sourceName)
            .put("stagedPath", finalFile.absolutePath)
            .put("size", bytesCopied)
            .put("sha256", sha256)
            .put("createdAt", createdAt)
            .toString(2)

        onProgress(
            FileImportProgress(
                phase = FileImportPhase.WRITE_METADATA,
                bytesCopied = bytesCopied,
                totalBytes = totalBytes,
                message = "Writing metadata",
            ),
        )

        try {
            tmpMeta.writeText(metadata)
        } catch (error: IOException) {
            cleanup(tmpFile, tmpMeta)
            return failure(
                errorCode = FileImportErrorCode.IO_ERROR,
                message = error.message ?: "Failed to write metadata",
                sourceName = sourceName,
            )
        }

        if (!tmpFile.renameTo(finalFile)) {
            cleanup(tmpFile, tmpMeta)
            return failure(
                errorCode = FileImportErrorCode.IO_ERROR,
                message = "Cannot rename ${tmpFile.absolutePath} to ${finalFile.absolutePath}",
                sourceName = sourceName,
            )
        }
        if (!tmpMeta.renameTo(finalMeta)) {
            finalFile.delete()
            cleanup(tmpFile, tmpMeta)
            return failure(
                errorCode = FileImportErrorCode.IO_ERROR,
                message = "Cannot rename ${tmpMeta.absolutePath} to ${finalMeta.absolutePath}",
                sourceName = sourceName,
            )
        }

        onProgress(
            FileImportProgress(
                phase = FileImportPhase.DONE,
                bytesCopied = bytesCopied,
                totalBytes = totalBytes,
                message = "Staged ${finalFile.name}",
            ),
        )

        return FileImportResult(
            success = true,
            stagedPath = finalFile.absolutePath,
            metadataPath = finalMeta.absolutePath,
            errorCode = null,
            message = "Staged ${finalFile.name}",
            sha256 = sha256,
            sizeBytes = bytesCopied,
            sourceName = sourceName,
        )
    }

    private fun ensureDirs(vararg dirs: File): Boolean = dirs.all { it.exists() || it.mkdirs() }

    private fun cleanup(vararg files: File) {
        files.forEach { it.delete() }
    }

    private fun nextSlot(stagingDir: File): String {
        val maxN = stagingDir.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 0
        return SLOT_FORMAT.format(maxN + 1)
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "blob" }.take(60)
    }

    private fun failure(errorCode: String, message: String, sourceName: String): FileImportResult =
        FileImportResult(
            success = false,
            stagedPath = null,
            metadataPath = null,
            errorCode = errorCode,
            message = message,
            sourceName = sourceName,
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
        const val DEFAULT_SIZE_LIMIT_BYTES: Long = 256L * 1024L * 1024L
        private const val SLOT_FORMAT = "%04d"
        private val FILE_PATTERN = Regex("file_(\\d{4,})(?:[._].*)?")
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

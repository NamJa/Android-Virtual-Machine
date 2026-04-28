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

class ApkStager {

    fun stage(
        stagingDir: File,
        sourceName: String,
        sizeLimitBytes: Long,
        totalBytes: Long? = null,
        source: () -> InputStream,
        clock: () -> Long = System::currentTimeMillis,
        onProgress: (ApkImportProgress) -> Unit = {},
    ): ApkImportResult {
        if (!hasApkExtension(sourceName)) {
            return failure(
                errorCode = ApkImportErrorCode.INVALID_EXTENSION,
                message = "Source name '$sourceName' does not end with .apk",
                sourceName = sourceName,
            )
        }
        val tmpDir = File(stagingDir, TMP_DIR_NAME)
        if (!ensureDirs(stagingDir, tmpDir)) {
            return failure(
                errorCode = ApkImportErrorCode.STAGING_DIR_FAILED,
                message = "Cannot create staging directories at ${stagingDir.absolutePath}",
                sourceName = sourceName,
            )
        }

        val slot = nextSlot(stagingDir)
        val finalApk = File(stagingDir, "import_${slot}.apk")
        val finalMeta = File(stagingDir, "import_${slot}.json")
        val tmpApk = File(tmpDir, "import_${slot}.apk.tmp")
        val tmpMeta = File(tmpDir, "import_${slot}.json.tmp")

        tmpApk.delete()
        tmpMeta.delete()

        onProgress(
            ApkImportProgress(
                phase = ApkImportPhase.OPEN_SOURCE,
                bytesCopied = 0L,
                totalBytes = totalBytes,
                message = "Opening $sourceName",
            ),
        )

        val stream = try {
            source()
        } catch (error: Throwable) {
            return failure(
                errorCode = ApkImportErrorCode.OPEN_FAILED,
                message = error.message ?: "Failed to open source stream",
                sourceName = sourceName,
            )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesCopied = 0L
        var headerValidated = false

        try {
            stream.use { input ->
                tmpApk.outputStream().use { output ->
                    onProgress(
                        ApkImportProgress(
                            phase = ApkImportPhase.COPY_TO_STAGING,
                            bytesCopied = 0L,
                            totalBytes = totalBytes,
                            message = "Copying to ${tmpApk.name}",
                        ),
                    )
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (!headerValidated) {
                            val ok = checkApkMagic(buffer, read, bytesCopied)
                            if (!ok) {
                                cleanupPartial(tmpApk, tmpMeta)
                                return failure(
                                    errorCode = ApkImportErrorCode.INVALID_HEADER,
                                    message = "ZIP/APK magic header not found in $sourceName",
                                    sourceName = sourceName,
                                )
                            }
                            if (bytesCopied + read >= APK_MAGIC.size) {
                                headerValidated = true
                            }
                        }
                        bytesCopied += read
                        if (bytesCopied > sizeLimitBytes) {
                            cleanupPartial(tmpApk, tmpMeta)
                            return failure(
                                errorCode = ApkImportErrorCode.SIZE_EXCEEDED,
                                message = "APK exceeds size limit $sizeLimitBytes bytes",
                                sourceName = sourceName,
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(
                            ApkImportProgress(
                                phase = ApkImportPhase.COPY_TO_STAGING,
                                bytesCopied = bytesCopied,
                                totalBytes = totalBytes,
                                message = "Copied $bytesCopied bytes",
                            ),
                        )
                    }
                }
            }
        } catch (error: IOException) {
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.IO_ERROR,
                message = error.message ?: "I/O error while copying APK",
                sourceName = sourceName,
            )
        }

        if (bytesCopied == 0L) {
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.EMPTY_FILE,
                message = "Source $sourceName is empty",
                sourceName = sourceName,
            )
        }
        if (!headerValidated) {
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.INVALID_HEADER,
                message = "Source $sourceName too small to contain a valid APK header",
                sourceName = sourceName,
            )
        }

        val sha256 = digest.digest().toHex()

        onProgress(
            ApkImportProgress(
                phase = ApkImportPhase.HASH,
                bytesCopied = bytesCopied,
                totalBytes = totalBytes,
                message = "Computed sha256",
            ),
        )

        val createdAt = formatTimestampUtc(clock())
        val metadataJson = JSONObject()
            .put("sourceName", sourceName)
            .put("stagedPath", finalApk.absolutePath)
            .put("size", bytesCopied)
            .put("sha256", sha256)
            .put("createdAt", createdAt)
            .toString(2)

        onProgress(
            ApkImportProgress(
                phase = ApkImportPhase.WRITE_METADATA,
                bytesCopied = bytesCopied,
                totalBytes = totalBytes,
                message = "Writing metadata",
            ),
        )

        try {
            tmpMeta.writeText(metadataJson)
        } catch (error: IOException) {
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.IO_ERROR,
                message = error.message ?: "Failed to write metadata",
                sourceName = sourceName,
            )
        }

        if (finalApk.exists() || finalMeta.exists()) {
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.IO_ERROR,
                message = "Staging slot $slot is already occupied",
                sourceName = sourceName,
            )
        }

        if (!tmpApk.renameTo(finalApk)) {
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.IO_ERROR,
                message = "Cannot rename ${tmpApk.absolutePath} to ${finalApk.absolutePath}",
                sourceName = sourceName,
            )
        }
        if (!tmpMeta.renameTo(finalMeta)) {
            finalApk.delete()
            cleanupPartial(tmpApk, tmpMeta)
            return failure(
                errorCode = ApkImportErrorCode.IO_ERROR,
                message = "Cannot rename ${tmpMeta.absolutePath} to ${finalMeta.absolutePath}",
                sourceName = sourceName,
            )
        }

        onProgress(
            ApkImportProgress(
                phase = ApkImportPhase.DONE,
                bytesCopied = bytesCopied,
                totalBytes = totalBytes,
                message = "Staged ${finalApk.name}",
            ),
        )

        return ApkImportResult(
            success = true,
            stagedPath = finalApk.absolutePath,
            packageName = null,
            errorCode = null,
            message = "Staged ${finalApk.name}",
            sha256 = sha256,
            sizeBytes = bytesCopied,
            metadataPath = finalMeta.absolutePath,
            sourceName = sourceName,
        )
    }

    private fun cleanupPartial(vararg files: File) {
        files.forEach { it.delete() }
    }

    private fun ensureDirs(vararg dirs: File): Boolean {
        return dirs.all { it.exists() || it.mkdirs() }
    }

    private fun nextSlot(stagingDir: File): String {
        val maxN = stagingDir.listFiles()
            .orEmpty()
            .mapNotNull { file ->
                IMPORT_FILE_PATTERN.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 0
        return SLOT_FORMAT.format(maxN + 1)
    }

    private fun checkApkMagic(buffer: ByteArray, read: Int, alreadyCopied: Long): Boolean {
        if (alreadyCopied >= APK_MAGIC.size) return true
        var i = 0
        while (i < read && (alreadyCopied + i) < APK_MAGIC.size) {
            val expected = APK_MAGIC[(alreadyCopied + i).toInt()]
            if (buffer[i] != expected) return false
            i++
        }
        return true
    }

    private fun failure(
        errorCode: String,
        message: String,
        sourceName: String?,
    ): ApkImportResult = ApkImportResult(
        success = false,
        stagedPath = null,
        packageName = null,
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

    private fun hasApkExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower.endsWith(".apk")
    }

    companion object {
        const val DEFAULT_SIZE_LIMIT_BYTES: Long = 256L * 1024L * 1024L
        const val TMP_DIR_NAME = "tmp"
        private const val BUFFER_SIZE = 64 * 1024
        private const val SLOT_FORMAT = "%04d"
        private val IMPORT_FILE_PATTERN = Regex("import_(\\d{4,})\\.(apk|json)")
        private val APK_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

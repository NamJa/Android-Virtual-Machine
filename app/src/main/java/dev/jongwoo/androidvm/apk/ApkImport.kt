package dev.jongwoo.androidvm.apk

import android.net.Uri

data class ApkImportRequest(
    val instanceId: String,
    val sourceUri: Uri,
    val displayName: String?,
    val sizeLimitBytes: Long,
)

enum class ApkImportPhase {
    OPEN_SOURCE,
    COPY_TO_STAGING,
    HASH,
    WRITE_METADATA,
    IMPORT_TO_GUEST,
    REFRESH_PACKAGES,
    DONE,
}

data class ApkImportProgress(
    val phase: ApkImportPhase,
    val bytesCopied: Long,
    val totalBytes: Long?,
    val message: String,
)

data class ApkImportResult(
    val success: Boolean,
    val stagedPath: String?,
    val packageName: String?,
    val errorCode: String?,
    val message: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val metadataPath: String? = null,
    val sourceName: String? = null,
)

object ApkImportErrorCode {
    const val OPEN_FAILED = "OPEN_FAILED"
    const val INVALID_EXTENSION = "INVALID_EXTENSION"
    const val INVALID_HEADER = "INVALID_HEADER"
    const val SIZE_EXCEEDED = "SIZE_EXCEEDED"
    const val EMPTY_FILE = "EMPTY_FILE"
    const val IO_ERROR = "IO_ERROR"
    const val STAGING_DIR_FAILED = "STAGING_DIR_FAILED"
}

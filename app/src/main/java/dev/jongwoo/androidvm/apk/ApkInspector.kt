package dev.jongwoo.androidvm.apk

import java.io.File

data class ApkInspectionResult(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val label: String?,
    val nativeAbis: Set<String>,
    val launcherActivity: String?,
)

sealed class ApkInspectionOutcome {
    data class Success(val info: ApkInspectionResult) : ApkInspectionOutcome()
    data class Failed(val errorCode: String, val message: String) : ApkInspectionOutcome()
}

interface ApkInspector {
    fun inspect(apkFile: File): ApkInspectionOutcome
}

object ApkInspectionErrorCode {
    const val PARSE_FAILED = "INSPECT_PARSE_FAILED"
    const val MISSING_PACKAGE_NAME = "INSPECT_MISSING_PACKAGE_NAME"
    const val IO_ERROR = "INSPECT_IO_ERROR"
}

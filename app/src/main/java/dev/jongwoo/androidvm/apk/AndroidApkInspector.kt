package dev.jongwoo.androidvm.apk

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.util.zip.ZipFile

class AndroidApkInspector(
    private val context: Context,
    private val launcherResolver: LauncherActivityResolver = LauncherActivityResolver(),
) : ApkInspector {

    override fun inspect(apkFile: File): ApkInspectionOutcome {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return ApkInspectionOutcome.Failed(
                errorCode = ApkInspectionErrorCode.IO_ERROR,
                message = "Cannot read APK at ${apkFile.absolutePath}",
            )
        }
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_META_DATA
        val info = try {
            pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
        } catch (error: Throwable) {
            return ApkInspectionOutcome.Failed(
                errorCode = ApkInspectionErrorCode.PARSE_FAILED,
                message = error.message ?: "Failed to parse APK",
            )
        } ?: return ApkInspectionOutcome.Failed(
            errorCode = ApkInspectionErrorCode.PARSE_FAILED,
            message = "PackageManager returned no info for APK",
        )

        val packageName = info.packageName
        if (packageName.isNullOrBlank()) {
            return ApkInspectionOutcome.Failed(
                errorCode = ApkInspectionErrorCode.MISSING_PACKAGE_NAME,
                message = "APK has no package name",
            )
        }

        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        info.applicationInfo?.sourceDir = apkFile.absolutePath
        info.applicationInfo?.publicSourceDir = apkFile.absolutePath
        val label = info.applicationInfo?.let { app ->
            runCatching { pm.getApplicationLabel(app).toString() }.getOrNull()
        }

        val nativeAbis = readNativeAbis(apkFile)
        val launcherActivity = runCatching { launcherResolver.resolve(apkFile) }.getOrNull()

        return ApkInspectionOutcome.Success(
            ApkInspectionResult(
                packageName = packageName,
                versionCode = versionCode,
                versionName = info.versionName,
                label = label?.takeIf { it.isNotBlank() } ?: packageName,
                nativeAbis = nativeAbis,
                launcherActivity = launcherActivity,
            ),
        )
    }

    private fun readNativeAbis(apkFile: File): Set<String> {
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val abis = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    val match = LIB_ENTRY.matchEntire(name) ?: continue
                    abis += match.groupValues[1]
                }
                abis.toSet()
            }
        }.getOrDefault(emptySet())
    }

    companion object {
        private val LIB_ENTRY = Regex("lib/([^/]+)/[^/]+\\.so")
    }
}

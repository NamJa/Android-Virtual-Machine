package dev.jongwoo.androidvm.apk

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.jongwoo.androidvm.storage.PathLayout
import dev.jongwoo.androidvm.vm.VmInstanceService
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

data class ApkPipelineProgress(
    val stage: ApkPipelineStage,
    val staging: ApkImportProgress?,
    val install: ApkInstallProgress?,
)

enum class ApkPipelineStage { STAGING, INSPECT, ENRICH, DISPATCH, DONE }

data class ApkPipelineResult(
    val success: Boolean,
    val errorCode: String?,
    val message: String,
    val import: ApkImportResult?,
    val packageName: String?,
    val launcherActivity: String?,
    val sidecarPath: String?,
    val dispatched: Boolean,
)

object ApkPipelineErrorCode {
    const val INSPECT_FAILED = "INSPECT_FAILED"
    const val SIDECAR_WRITE_FAILED = "SIDECAR_WRITE_FAILED"
    const val RUNTIME_IMPORT_TIMEOUT = "RUNTIME_IMPORT_TIMEOUT"
}

interface ApkImportDispatcher {
    fun dispatch(instanceId: String, stagedPath: String)
}

class ServiceApkImportDispatcher(private val context: Context) : ApkImportDispatcher {
    override fun dispatch(instanceId: String, stagedPath: String) {
        VmInstanceService.importApk(context, instanceId, stagedPath)
    }
}

class ApkInstallPipeline(
    private val context: Context,
    private val pathLayout: PathLayout = PathLayout(context),
    private val stager: ApkStager = ApkStager(),
    private val inspector: ApkInspector = AndroidApkInspector(context),
    private val launcherResolver: LauncherActivityResolver = LauncherActivityResolver(),
    private val dispatcher: ApkImportDispatcher = ServiceApkImportDispatcher(context),
) {

    fun uninstall(instanceId: String, packageName: String) {
        VmInstanceService.uninstallPackage(context, instanceId, packageName)
    }

    fun clearData(instanceId: String, packageName: String) {
        VmInstanceService.clearPackageData(context, instanceId, packageName)
    }

    fun launch(instanceId: String, packageName: String) {
        VmInstanceService.launchPackage(context, instanceId, packageName)
    }

    fun stop(instanceId: String, packageName: String) {
        VmInstanceService.stopPackage(context, instanceId, packageName)
    }

    fun importAndInstall(
        request: ApkImportRequest,
        @Suppress("UNUSED_PARAMETER") duplicatePolicy: DuplicateInstallPolicy = DuplicateInstallPolicy.UPDATE,
        onProgress: (ApkPipelineProgress) -> Unit = {},
    ): ApkPipelineResult {
        val resolver = context.contentResolver
        val displayName = request.displayName
            ?: queryDisplayName(resolver, request.sourceUri)
            ?: "import.apk"
        val totalBytes = querySize(resolver, request.sourceUri)
        val instancePaths = pathLayout.ensureInstance(request.instanceId)

        val importResult = stager.stage(
            stagingDir = instancePaths.stagingDir,
            sourceName = displayName,
            sizeLimitBytes = request.sizeLimitBytes,
            totalBytes = totalBytes,
            source = {
                resolver.openInputStream(request.sourceUri)
                    ?: throw IOException("ContentResolver returned null stream for ${request.sourceUri}")
            },
            onProgress = { progress ->
                onProgress(ApkPipelineProgress(ApkPipelineStage.STAGING, progress, null))
            },
        )

        if (!importResult.success || importResult.stagedPath == null) {
            return ApkPipelineResult(
                success = false,
                errorCode = importResult.errorCode,
                message = importResult.message,
                import = importResult,
                packageName = null,
                launcherActivity = null,
                sidecarPath = null,
                dispatched = false,
            )
        }

        val stagedFile = File(importResult.stagedPath)
        onProgress(ApkPipelineProgress(ApkPipelineStage.INSPECT, null, null))
        val inspection = inspector.inspect(stagedFile)
        val info = when (inspection) {
            is ApkInspectionOutcome.Failed -> {
                return ApkPipelineResult(
                    success = false,
                    errorCode = ApkPipelineErrorCode.INSPECT_FAILED,
                    message = inspection.message,
                    import = importResult,
                    packageName = null,
                    launcherActivity = null,
                    sidecarPath = null,
                    dispatched = false,
                )
            }
            is ApkInspectionOutcome.Success -> inspection.info
        }

        val launcherActivity = info.launcherActivity
            ?: runCatching { launcherResolver.resolve(stagedFile) }.getOrNull()

        val installedPath = "${instancePaths.dataDir.absolutePath}/app/${info.packageName}/base.apk"
        val dataPath = "${instancePaths.dataDir.absolutePath}/data/${info.packageName}"

        onProgress(ApkPipelineProgress(ApkPipelineStage.ENRICH, null, null))
        val sidecarFile = File(importResult.metadataPath ?: deriveSidecarPath(stagedFile))
        val enriched = JSONObject()
            .put("kind", "apk")
            .put("sourceName", importResult.sourceName ?: displayName)
            .put("stagedPath", stagedFile.absolutePath)
            .put("size", importResult.sizeBytes ?: 0L)
            .put("sha256", importResult.sha256 ?: JSONObject.NULL)
            .put("createdAt", readCreatedAt(sidecarFile))
            .put("packageName", info.packageName)
            .put("label", info.label ?: info.packageName)
            .put("versionCode", info.versionCode)
            .put("versionName", info.versionName ?: JSONObject.NULL)
            .put("launcherActivity", launcherActivity ?: JSONObject.NULL)
            .put("installedPath", installedPath)
            .put("dataPath", dataPath)
            .put(
                "nativeAbis",
                JSONArray().apply { info.nativeAbis.sorted().forEach { put(it) } },
            )

        val sidecarOk = runCatching { sidecarFile.writeText(enriched.toString(2)) }.isSuccess
        if (!sidecarOk) {
            return ApkPipelineResult(
                success = false,
                errorCode = ApkPipelineErrorCode.SIDECAR_WRITE_FAILED,
                message = "Failed to write enriched sidecar at ${sidecarFile.absolutePath}",
                import = importResult,
                packageName = info.packageName,
                launcherActivity = launcherActivity,
                sidecarPath = sidecarFile.absolutePath,
                dispatched = false,
            )
        }

        onProgress(ApkPipelineProgress(ApkPipelineStage.DISPATCH, null, null))
        val wasInstalled = loadPackages(request.instanceId).any { it.packageName == info.packageName }
        dispatcher.dispatch(request.instanceId, stagedFile.absolutePath)

        val committed = awaitPackageCommit(
            instanceId = request.instanceId,
            packageName = info.packageName,
            expectedSha256 = importResult.sha256,
        )
        if (committed == null) {
            return ApkPipelineResult(
                success = false,
                errorCode = ApkPipelineErrorCode.RUNTIME_IMPORT_TIMEOUT,
                message = "Runtime importer did not commit ${info.packageName}",
                import = importResult,
                packageName = info.packageName,
                launcherActivity = launcherActivity,
                sidecarPath = sidecarFile.absolutePath,
                dispatched = true,
            )
        }

        onProgress(ApkPipelineProgress(ApkPipelineStage.DONE, null, null))
        return ApkPipelineResult(
            success = true,
            errorCode = null,
            message = if (wasInstalled) {
                "Updated ${committed.label}"
            } else {
                "Installed ${committed.label}"
            },
            import = importResult,
            packageName = info.packageName,
            launcherActivity = launcherActivity,
            sidecarPath = sidecarFile.absolutePath,
            dispatched = true,
        )
    }

    fun loadPackages(instanceId: String): List<GuestPackageInfo> {
        val instancePaths = pathLayout.ensureInstance(instanceId)
        val indexFile = File(instancePaths.runtimeDir, "package-index.json")
        if (!indexFile.exists()) return emptyList()
        return PackageIndex(indexFile).load(instanceId, "").packages
    }

    private fun awaitPackageCommit(
        instanceId: String,
        packageName: String,
        expectedSha256: String?,
        timeoutMillis: Long = 5_000L,
    ): GuestPackageInfo? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() <= deadline) {
            val entry = loadPackages(instanceId).firstOrNull { it.packageName == packageName }
            if (entry != null && (expectedSha256 == null || entry.sha256.equals(expectedSha256, ignoreCase = true))) {
                return entry
            }
            Thread.sleep(100L)
        }
        return null
    }

    private fun deriveSidecarPath(staged: File): String {
        val name = staged.name
        val dot = name.lastIndexOf('.')
        val base = if (dot < 0) name else name.substring(0, dot)
        return File(staged.parentFile, "$base.json").absolutePath
    }

    private fun readCreatedAt(sidecar: File): String {
        if (!sidecar.exists()) return ""
        return runCatching {
            JSONObject(sidecar.readText()).optString("createdAt", "")
        }.getOrDefault("")
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else null
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }
}

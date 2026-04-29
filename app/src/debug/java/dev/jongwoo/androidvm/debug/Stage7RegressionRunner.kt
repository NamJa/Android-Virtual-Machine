package dev.jongwoo.androidvm.debug

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import dev.jongwoo.androidvm.apk.ApkImportResult
import dev.jongwoo.androidvm.apk.ApkStager
import dev.jongwoo.androidvm.apk.AxmlBuilder
import dev.jongwoo.androidvm.bridge.Stage7RegressionResult
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.RomInstallStatus
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.vm.GuestPathStatus
import dev.jongwoo.androidvm.vm.VmNativeBridge
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shared driver for the Stage 4/5/6 native smokes. Runs the rootfs install, native runtime
 * bring-up, and per-stage probes that Stage 07's final gate uses, returning a structured
 * [Stage7RegressionResult]. Extracted out of `Stage7DiagnosticsReceiver` so the Phase A receiver
 * can run the same smokes without duplicating the synthesis / smoke logic.
 *
 * Caller decides which logcat tag to emit per-stage diagnostic lines under (typical values are
 * `AVM.Stage7Diag` and `AVM.PhaseADiag`).
 */
object Stage7RegressionRunner {
    fun run(context: Context, tag: String = DEFAULT_TAG): Stage7RegressionResult {
        val config = InstanceStore(context).ensureDefaultConfig()
        if (!ensureRomInstalled(context, config.instanceId, tag)) {
            return Stage7RegressionResult(stage4 = false, stage5 = false, stage6 = false)
        }
        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(config.instanceId, config.toJson())

        return try {
            val stage4 = runStage4Smoke(config.instanceId, tag)
            val stage5 = runStage5Smoke(config.instanceId, tag)
            val stage6 = runStage6Smoke(context, config.instanceId, tag)
            Stage7RegressionResult(stage4 = stage4, stage5 = stage5, stage6 = stage6)
        } finally {
            VmNativeBridge.stopGuest(config.instanceId)
        }
    }

    private fun ensureRomInstalled(context: Context, instanceId: String, tag: String): Boolean {
        val installer = RomInstaller(context)
        var snapshot = installer.snapshot(instanceId)
        if (snapshot.isInstalled) return true
        val outcome = installer.installDefault(instanceId)
        if (outcome.status != RomInstallStatus.INSTALLED &&
            outcome.status != RomInstallStatus.ALREADY_HEALTHY
        ) {
            Log.e(tag, "STAGE_REGRESSION_ROM passed=false status=${outcome.status}")
            return false
        }
        snapshot = installer.snapshot(instanceId)
        return snapshot.isInstalled
    }

    private fun runStage4Smoke(instanceId: String, tag: String): Boolean {
        val start = VmNativeBridge.startGuest(instanceId)
        Thread.sleep(200)
        val bootstrap = VmNativeBridge.getBootstrapStatus(instanceId)
        val resolved = VmNativeBridge.resolveGuestPathResult(instanceId, "/data/phase-regression.txt", true)
        val fd = VmNativeBridge.openGuestPath(instanceId, "/data/phase-regression.txt", true)
        val wrote = if (fd > 0) VmNativeBridge.writeGuestFile(instanceId, fd, "ok") else -1
        if (fd > 0) VmNativeBridge.closeGuestFile(instanceId, fd)
        val passed = start == 0 &&
            bootstrap.contains("virtual_init=ok") &&
            bootstrap.contains("servicemanager=ok") &&
            resolved.status == GuestPathStatus.OK &&
            fd > 0 &&
            wrote == "ok".length
        Log.i(
            tag,
            "STAGE_REGRESSION_STAGE4 passed=$passed start=$start " +
                "pathStatus=${resolved.status} fd=$fd wrote=$wrote bootstrap=\"$bootstrap\"",
        )
        return passed
    }

    private fun runStage5Smoke(instanceId: String, tag: String): Boolean {
        val resize = VmNativeBridge.resizeSurface(instanceId, 720, 1280, 320)
        val pattern = VmNativeBridge.writeFramebufferTestPattern(instanceId, 7)
        val graphics = JSONObject(VmNativeBridge.getGraphicsStats(instanceId))
        val audio = VmNativeBridge.generateAudioTestTone(instanceId, 48_000, 480, false)
        val audioStats = JSONObject(VmNativeBridge.getAudioStats(instanceId))
        VmNativeBridge.resetInputQueue(instanceId)
        val input = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val passed = resize == 0 &&
            pattern == 0 &&
            graphics.optInt("framebufferWidth") > 0 &&
            audio > 0 &&
            audioStats.optInt("sampleRate") > 0 &&
            input.optInt("queueSize", -1) >= 0
        Log.i(
            tag,
            "STAGE_REGRESSION_STAGE5 passed=$passed resize=$resize pattern=$pattern " +
                "audio=$audio fbW=${graphics.optInt("framebufferWidth")} " +
                "sampleRate=${audioStats.optInt("sampleRate")} queueSize=${input.optInt("queueSize", -1)}",
        )
        return passed
    }

    private fun runStage6Smoke(context: Context, instanceId: String, tag: String): Boolean {
        val store = InstanceStore(context)
        val instancePaths = store.pathsFor(instanceId)
        val packageName = "dev.jongwoo.androidvm.diag.phase"
        val launcher = "$packageName.MainActivity"
        val apkBytes = synthesizeApk(packageName)
        val staged = ApkStager().stage(
            stagingDir = instancePaths.stagingDir,
            sourceName = "phase_regression.apk",
            sizeLimitBytes = ApkStager.DEFAULT_SIZE_LIMIT_BYTES,
            source = { ByteArrayInputStream(apkBytes) },
        )
        if (!staged.success || staged.stagedPath == null) {
            Log.e(tag, "STAGE_REGRESSION_STAGE6 passed=false reason=staging_failed")
            return false
        }
        val stagedFile = File(staged.stagedPath!!)
        val sidecarFile = File(staged.metadataPath ?: stagedFile.absolutePath.removeSuffix(".apk") + ".json")
        val installedPath = "${instancePaths.dataDir.absolutePath}/app/$packageName/base.apk"
        val dataPath = "${instancePaths.dataDir.absolutePath}/data/$packageName"
        enrichApkSidecar(
            sidecarFile = sidecarFile,
            staged = staged,
            packageName = packageName,
            launcher = launcher,
            installedPath = installedPath,
            dataPath = dataPath,
        )

        val importRc = VmNativeBridge.importApk(instanceId, stagedFile.absolutePath)
        val packages = JSONObject(VmNativeBridge.listPackages(instanceId)).optJSONArray("packages") ?: JSONArray()
        val listed = (0 until packages.length())
            .map { packages.getJSONObject(it) }
            .firstOrNull { it.optString("packageName") == packageName }
        val runtimeStart = VmNativeBridge.startGuest(instanceId)
        Thread.sleep(200)
        val launchRc = VmNativeBridge.launchPackage(instanceId, packageName)
        val launchStatus = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_DOWN, 0, 120f, 240f)
        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_UP, 0, 120f, 240f)
        VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        val inputStatus = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))

        VmNativeBridge.stopPackage(instanceId, packageName)
        val uninstallRc = VmNativeBridge.uninstallPackage(instanceId, packageName)
        cleanup(stagedFile, sidecarFile)

        val passed = importRc == 0 &&
            listed?.optBoolean("launchable") == true &&
            runtimeStart == 0 &&
            launchRc == 0 &&
            launchStatus.optString("foregroundPackage") == packageName &&
            launchStatus.optBoolean("foregroundAppProcessRunning") &&
            launchStatus.optBoolean("foregroundWindowAttached") &&
            launchStatus.optString("foregroundLaunchMode") == "runtime_compatible_activity" &&
            inputStatus.optInt("inputDispatchCount") >= 4 &&
            uninstallRc == 0
        Log.i(
            tag,
            "STAGE_REGRESSION_STAGE6 passed=$passed importRc=$importRc runtimeStart=$runtimeStart " +
                "launchRc=$launchRc listed=${listed != null} process=${launchStatus.optBoolean("foregroundAppProcessRunning")} " +
                "window=${launchStatus.optBoolean("foregroundWindowAttached")} " +
                "inputDispatches=${inputStatus.optInt("inputDispatchCount")} uninstallRc=$uninstallRc",
        )
        return passed
    }

    private fun enrichApkSidecar(
        sidecarFile: File,
        staged: ApkImportResult,
        packageName: String,
        launcher: String,
        installedPath: String,
        dataPath: String,
    ) {
        val createdAt = runCatching { JSONObject(sidecarFile.readText()).optString("createdAt", "") }
            .getOrDefault("")
        val json = JSONObject()
            .put("kind", "apk")
            .put("sourceName", staged.sourceName)
            .put("stagedPath", staged.stagedPath)
            .put("size", staged.sizeBytes ?: 0L)
            .put("sha256", staged.sha256 ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("packageName", packageName)
            .put("label", "Phase Regression")
            .put("versionCode", 1L)
            .put("versionName", "1.0")
            .put("launcherActivity", launcher)
            .put("installedPath", installedPath)
            .put("dataPath", dataPath)
            .put("nativeAbis", JSONArray().put("arm64-v8a"))
        sidecarFile.writeText(json.toString(2))
    }

    private fun synthesizeApk(packageName: String): ByteArray {
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", packageName))
            .start("application", AxmlBuilder.Attr.string("label", "Phase Regression"))
            .start("activity", AxmlBuilder.Attr.string("name", ".MainActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(16) { 0 })
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun cleanup(vararg files: File) {
        files.forEach { runCatching { it.delete() } }
    }

    private const val DEFAULT_TAG = "AVM.StageReg"
}

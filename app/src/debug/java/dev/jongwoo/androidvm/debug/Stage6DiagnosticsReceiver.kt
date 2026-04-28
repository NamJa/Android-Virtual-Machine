package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import dev.jongwoo.androidvm.apk.ApkInspectionOutcome
import dev.jongwoo.androidvm.apk.ApkInspectionResult
import dev.jongwoo.androidvm.apk.ApkStager
import dev.jongwoo.androidvm.apk.AndroidApkInspector
import dev.jongwoo.androidvm.apk.AxmlBuilder
import dev.jongwoo.androidvm.apk.LauncherActivityResolver
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

class Stage6DiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_STAGE6_DIAGNOSTICS) return

        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        val instancePaths = store.pathsFor(config.instanceId)
        val installer = RomInstaller(context)
        var rom = installer.snapshot(config.instanceId)
        if (!rom.isInstalled) {
            Log.i(TAG, "STAGE6_ROM_BOOTSTRAP installing default rootfs")
            val outcome = installer.installDefault(config.instanceId)
            Log.i(TAG, "STAGE6_ROM_BOOTSTRAP status=${outcome.status} message=${outcome.message}")
            if (outcome.status != RomInstallStatus.INSTALLED &&
                outcome.status != RomInstallStatus.ALREADY_HEALTHY
            ) {
                Log.e(
                    TAG,
                    "STAGE6_RESULT passed=false reason=rom_install_failed status=${outcome.status}",
                )
                return
            }
            rom = installer.snapshot(config.instanceId)
            if (!rom.isInstalled) {
                Log.e(TAG, "STAGE6_RESULT passed=false reason=rom_not_installed_after_bootstrap")
                return
            }
        }

        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(config.instanceId, config.toJson())
        VmNativeBridge.resizeSurface(
            config.instanceId,
            DIAGNOSTIC_SURFACE_WIDTH,
            DIAGNOSTIC_SURFACE_HEIGHT,
            DIAGNOSTIC_DENSITY,
        )

        val packageName = "dev.jongwoo.androidvm.diag.stage6"
        val expectedLauncher = "$packageName.MainActivity"
        val apkBytes = synthesizeApk(packageName)

        val stager = ApkStager()
        val staged = stager.stage(
            stagingDir = instancePaths.stagingDir,
            sourceName = "stage6_diagnostic.apk",
            sizeLimitBytes = ApkStager.DEFAULT_SIZE_LIMIT_BYTES,
            source = { ByteArrayInputStream(apkBytes) },
        )
        val stagingPassed = staged.success && staged.stagedPath != null
        Log.i(
            TAG,
            "STAGE6_STAGING_RESULT passed=$stagingPassed errorCode=${staged.errorCode ?: "null"} " +
                "size=${staged.sizeBytes} sha256=${staged.sha256}",
        )
        if (!stagingPassed) {
            emitFinal(false, stagingPassed, false, false, false, false, false)
            return
        }

        val stagedFile = File(staged.stagedPath!!)
        val inspector = AndroidApkInspector(context)
        val inspectionResult = when (val out = inspector.inspect(stagedFile)) {
            is ApkInspectionOutcome.Success -> out.info
            is ApkInspectionOutcome.Failed -> {
                Log.w(
                    TAG,
                    "STAGE6_INSPECT_FALLBACK reason=${out.errorCode} message=${out.message}",
                )
                ApkInspectionResult(
                    packageName = packageName,
                    versionCode = 1L,
                    versionName = "1.0",
                    label = "Stage6 Diagnostic",
                    nativeAbis = setOf("arm64-v8a"),
                    launcherActivity = LauncherActivityResolver().resolve(stagedFile)
                        ?: expectedLauncher,
                )
            }
        }
        val resolvedLauncher = inspectionResult.launcherActivity
            ?: LauncherActivityResolver().resolve(stagedFile)
            ?: expectedLauncher

        val sidecarFile = File(staged.metadataPath ?: stagedFile.absolutePath.removeSuffix(".apk") + ".json")
        val installedPath = "${instancePaths.dataDir.absolutePath}/app/${inspectionResult.packageName}/base.apk"
        val dataPath = "${instancePaths.dataDir.absolutePath}/data/${inspectionResult.packageName}"
        val createdAt = runCatching { JSONObject(sidecarFile.readText()).optString("createdAt", "") }.getOrDefault("")
        val enriched = JSONObject()
            .put("kind", "apk")
            .put("sourceName", staged.sourceName)
            .put("stagedPath", stagedFile.absolutePath)
            .put("size", staged.sizeBytes ?: 0)
            .put("sha256", staged.sha256 ?: JSONObject.NULL)
            .put("createdAt", createdAt)
            .put("packageName", inspectionResult.packageName)
            .put("label", inspectionResult.label ?: inspectionResult.packageName)
            .put("versionCode", inspectionResult.versionCode)
            .put("versionName", inspectionResult.versionName ?: JSONObject.NULL)
            .put("launcherActivity", resolvedLauncher)
            .put("installedPath", installedPath)
            .put("dataPath", dataPath)
            .put(
                "nativeAbis",
                JSONArray().apply { inspectionResult.nativeAbis.sorted().forEach { put(it) } },
            )
        sidecarFile.writeText(enriched.toString(2))

        val importRc = VmNativeBridge.importApk(config.instanceId, stagedFile.absolutePath)
        val importStatus = JSONObject(VmNativeBridge.getPackageOperationStatus(config.instanceId))
        val installPassed = importRc == 0 &&
            File(installedPath).exists() &&
            File(dataPath).isDirectory &&
            importStatus.optString("lastOutcome") == "ok"
        Log.i(
            TAG,
            "STAGE6_INSTALL_RESULT passed=$installPassed rc=$importRc outcome=${importStatus.optString("lastOutcome")} " +
                "message=${importStatus.optString("lastMessage")}",
        )

        val packageList = JSONObject(VmNativeBridge.listPackages(config.instanceId))
        val packagesArray = packageList.optJSONArray("packages") ?: JSONArray()
        var listedPackage: JSONObject? = null
        for (i in 0 until packagesArray.length()) {
            val entry = packagesArray.getJSONObject(i)
            if (entry.optString("packageName") == packageName) {
                listedPackage = entry
                break
            }
        }
        val packageListPassed = listedPackage != null &&
            listedPackage.optString("launcherActivity") == resolvedLauncher &&
            listedPackage.optBoolean("launchable") &&
            listedPackage.optString("installedPath") == installedPath
        Log.i(
            TAG,
            "STAGE6_PACKAGE_LIST_RESULT passed=$packageListPassed total=${packagesArray.length()} " +
                "launcher=${listedPackage?.optString("launcherActivity") ?: "null"}",
        )

        val runtimeStart = VmNativeBridge.startGuest(config.instanceId)
        Thread.sleep(300)
        Log.i(TAG, "STAGE6_RUNTIME_START rc=$runtimeStart")

        val launchPassed = exerciseForeground(config.instanceId, packageName, resolvedLauncher)
        val managementPassed = exerciseManagement(config.instanceId, packageName, installedPath, dataPath)

        val stage4Passed = stage4Regression(config.instanceId)
        val stage5Passed = stage5Regression(config.instanceId)
        val regressionsPassed = stage4Passed && stage5Passed
        Log.i(
            TAG,
            "STAGE6_REGRESSION_RESULT passed=$regressionsPassed stage4=$stage4Passed stage5=$stage5Passed",
        )

        emitFinal(
            true,
            stagingPassed,
            installPassed,
            packageListPassed,
            launchPassed,
            managementPassed,
            regressionsPassed,
        )

        cleanupStaging(stagedFile, sidecarFile, instancePaths.stagingDir)
    }

    private fun exerciseForeground(
        instanceId: String,
        packageName: String,
        expectedLauncher: String,
    ): Boolean {
        val baseFrames = JSONObject(VmNativeBridge.getGraphicsStats(instanceId)).optLong("framebufferFrames")

        val launchRc = VmNativeBridge.launchPackage(instanceId, packageName)
        val statusAfterLaunch = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        val foreground = statusAfterLaunch.optString("foregroundPackage")
        val activity = statusAfterLaunch.optString("foregroundActivity")
        val processRunning = statusAfterLaunch.optBoolean("foregroundAppProcessRunning")
        val windowAttached = statusAfterLaunch.optBoolean("foregroundWindowAttached")
        val launchMode = statusAfterLaunch.optString("foregroundLaunchMode")
        val activityTransactions = statusAfterLaunch.optInt("activityManagerTransactions")
        val processLaunches = statusAfterLaunch.optInt("appProcessLaunches")
        val windowCommitsAtLaunch = statusAfterLaunch.optInt("windowCommitCount")
        val framesAfterLaunch = JSONObject(VmNativeBridge.getGraphicsStats(instanceId)).optLong("framebufferFrames")
        val launchVisible = launchRc == 0 &&
            foreground == packageName &&
            activity == expectedLauncher &&
            processRunning &&
            windowAttached &&
            launchMode == "runtime_compatible_activity" &&
            activityTransactions > 0 &&
            processLaunches > 0 &&
            windowCommitsAtLaunch > 0 &&
            framesAfterLaunch > baseFrames

        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_DOWN, 0, 200f, 480f)
        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_MOVE, 0, 220f, 500f)
        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_UP, 0, 240f, 520f)
        VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)

        val statusAfterInput = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        val touchEvents = statusAfterInput.optInt("foregroundTouchEvents")
        val keyEvents = statusAfterInput.optInt("foregroundKeyEvents")
        val lastTouchX = statusAfterInput.optInt("foregroundLastTouchX")
        val lastTouchY = statusAfterInput.optInt("foregroundLastTouchY")
        val lastKey = statusAfterInput.optInt("foregroundLastKeyCode")
        val inputDispatches = statusAfterInput.optInt("inputDispatchCount")
        val windowCommitsAfterInput = statusAfterInput.optInt("windowCommitCount")
        val framesAfterInput = JSONObject(VmNativeBridge.getGraphicsStats(instanceId)).optLong("framebufferFrames")

        val inputResponded = touchEvents >= 3 &&
            keyEvents >= 2 &&
            inputDispatches >= 5 &&
            lastTouchX >= 0 && lastTouchY >= 0 &&
            lastKey == KeyEvent.KEYCODE_BACK &&
            windowCommitsAfterInput > windowCommitsAtLaunch &&
            framesAfterInput > framesAfterLaunch

        Log.i(
            TAG,
            "STAGE6_LAUNCH_RESULT passed=${launchVisible && inputResponded} launchVisible=$launchVisible " +
                "inputResponded=$inputResponded foreground=$foreground activity=$activity " +
                "processRunning=$processRunning windowAttached=$windowAttached launchMode=$launchMode " +
                "activityTransactions=$activityTransactions processLaunches=$processLaunches " +
                "touchEvents=$touchEvents keyEvents=$keyEvents inputDispatches=$inputDispatches " +
                "lastTouch=($lastTouchX,$lastTouchY) " +
                "lastKey=$lastKey baseFrames=$baseFrames framesAfterLaunch=$framesAfterLaunch " +
                "framesAfterInput=$framesAfterInput",
        )
        return launchVisible && inputResponded
    }

    private fun exerciseManagement(
        instanceId: String,
        packageName: String,
        installedPath: String,
        dataPath: String,
    ): Boolean {
        // Drop a marker into data dir to confirm clearPackageData wipes it.
        runCatching {
            val marker = File(dataPath, "diag-marker.txt")
            marker.parentFile?.mkdirs()
            marker.writeText("preserved")
        }
        val clearRc = VmNativeBridge.clearPackageData(instanceId, packageName)
        val markerGone = !File(dataPath, "diag-marker.txt").exists()
        val dataDirStillExists = File(dataPath).isDirectory

        VmNativeBridge.stopPackage(instanceId, packageName)
        val uninstallRc = VmNativeBridge.uninstallPackage(instanceId, packageName)
        val apkGone = !File(installedPath).exists()
        val dataGone = !File(dataPath).exists()
        val listAfter = JSONObject(VmNativeBridge.listPackages(instanceId)).optJSONArray("packages") ?: JSONArray()
        var stillListed = false
        for (i in 0 until listAfter.length()) {
            if (listAfter.getJSONObject(i).optString("packageName") == packageName) {
                stillListed = true
                break
            }
        }
        val finalStatus = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))

        val passed = clearRc == 0 && markerGone && dataDirStillExists &&
            uninstallRc == 0 && apkGone && dataGone && !stillListed &&
            finalStatus.optString("foregroundPackage").isEmpty()
        Log.i(
            TAG,
            "STAGE6_PACKAGE_MANAGEMENT_RESULT passed=$passed clearRc=$clearRc markerGone=$markerGone " +
                "dataDirKept=$dataDirStillExists uninstallRc=$uninstallRc apkGone=$apkGone dataGone=$dataGone " +
                "stillListed=$stillListed foregroundCleared=${finalStatus.optString("foregroundPackage").isEmpty()}",
        )
        return passed
    }

    private fun emitFinal(
        prerequisites: Boolean,
        staging: Boolean,
        install: Boolean,
        packages: Boolean,
        launch: Boolean,
        management: Boolean,
        regressions: Boolean,
    ) {
        val overall = prerequisites && staging && install && packages && launch && management && regressions
        Log.i(
            TAG,
            "STAGE6_RESULT passed=$overall staging=$staging install=$install " +
                "packages=$packages launch=$launch management=$management regressions=$regressions",
        )
    }

    private fun stage4Regression(instanceId: String): Boolean {
        val start = VmNativeBridge.startGuest(instanceId)
        Thread.sleep(300)
        val bootstrap = VmNativeBridge.getBootstrapStatus(instanceId)
        val bootstrapOk = bootstrap.contains("virtual_init=ok") &&
            bootstrap.contains("servicemanager=ok")
        val resolved = VmNativeBridge.resolveGuestPathResult(instanceId, "/data/diag.txt", true)
        val openFd = VmNativeBridge.openGuestPath(instanceId, "/data/diag.txt", true)
        val wrote = if (openFd > 0) VmNativeBridge.writeGuestFile(instanceId, openFd, "ok") else 0
        if (openFd > 0) VmNativeBridge.closeGuestFile(instanceId, openFd)
        VmNativeBridge.stopGuest(instanceId)
        val passed = start == 0 && bootstrapOk &&
            resolved.status == GuestPathStatus.OK &&
            openFd > 0 &&
            wrote == "ok".length
        Log.i(
            TAG,
            "STAGE6_STAGE4_REGRESSION start=$start bootstrapOk=$bootstrapOk bootstrap=\"$bootstrap\" " +
                "pathStatus=${resolved.status} fd=$openFd wrote=$wrote",
        )
        return passed
    }

    private fun stage5Regression(instanceId: String): Boolean {
        val resize = VmNativeBridge.resizeSurface(instanceId, 720, 1280, 320)
        val pattern = VmNativeBridge.writeFramebufferTestPattern(instanceId, 1)
        val graphicsStr = VmNativeBridge.getGraphicsStats(instanceId)
        val graphics = if (graphicsStr.isNotBlank()) JSONObject(graphicsStr) else JSONObject()
        val audio = VmNativeBridge.generateAudioTestTone(instanceId, 48000, 480, false)
        val audioStr = VmNativeBridge.getAudioStats(instanceId)
        val audioStats = if (audioStr.isNotBlank()) JSONObject(audioStr) else JSONObject()
        VmNativeBridge.resetInputQueue(instanceId)
        val inputStr = VmNativeBridge.getInputStats(instanceId)
        val input = if (inputStr.isNotBlank()) JSONObject(inputStr) else JSONObject()
        val passed = resize == 0 && pattern == 0 &&
            graphics.optInt("framebufferWidth") > 0 &&
            audio > 0 && audioStats.optInt("sampleRate") > 0 &&
            input.optInt("queueSize", -1) >= 0
        Log.i(
            TAG,
            "STAGE6_STAGE5_REGRESSION resize=$resize pattern=$pattern audio=$audio " +
                "fbW=${graphics.optInt("framebufferWidth")} sampleRate=${audioStats.optInt("sampleRate")} " +
                "queueSize=${input.optInt("queueSize", -1)}",
        )
        return passed
    }

    private fun synthesizeApk(packageName: String): ByteArray {
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", packageName))
            .start("application", AxmlBuilder.Attr.string("label", "Stage6 Diagnostic"))
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
            zip.write(ByteArray(16) { it.toByte() })
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun cleanupStaging(stagedFile: File, sidecar: File, stagingDir: File) {
        stagedFile.delete()
        sidecar.delete()
        File(stagingDir, ApkStager.TMP_DIR_NAME).listFiles().orEmpty().forEach { it.delete() }
    }

    companion object {
        private const val TAG = "AVM.Stage6Diag"
        private const val ACTION_RUN_STAGE6_DIAGNOSTICS = "dev.jongwoo.androidvm.debug.RUN_STAGE6_DIAGNOSTICS"
        private const val DIAGNOSTIC_SURFACE_WIDTH = 720
        private const val DIAGNOSTIC_SURFACE_HEIGHT = 1280
        private const val DIAGNOSTIC_DENSITY = 320
    }
}

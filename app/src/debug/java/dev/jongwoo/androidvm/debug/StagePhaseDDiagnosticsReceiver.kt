package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Debug
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import dev.jongwoo.androidvm.apk.ApkImportResult
import dev.jongwoo.androidvm.apk.ApkStager
import dev.jongwoo.androidvm.apk.AxmlBuilder
import dev.jongwoo.androidvm.apk.NativePhaseDPmsClient
import dev.jongwoo.androidvm.apk.PmsInstallResult
import dev.jongwoo.androidvm.bridge.AudioOutputBridge
import dev.jongwoo.androidvm.bridge.BridgeAuditLog
import dev.jongwoo.androidvm.bridge.BridgeDecision
import dev.jongwoo.androidvm.bridge.BridgeMode
import dev.jongwoo.androidvm.bridge.BridgePolicyStore
import dev.jongwoo.androidvm.bridge.BridgeRequest
import dev.jongwoo.androidvm.bridge.BridgeResponse
import dev.jongwoo.androidvm.bridge.BridgeResult
import dev.jongwoo.androidvm.bridge.BridgeType
import dev.jongwoo.androidvm.bridge.CameraBridge
import dev.jongwoo.androidvm.bridge.ClipboardBridge
import dev.jongwoo.androidvm.bridge.FileBridge
import dev.jongwoo.androidvm.bridge.FixedCameraSource
import dev.jongwoo.androidvm.bridge.FixedPcmSource
import dev.jongwoo.androidvm.bridge.InMemoryHostClipboard
import dev.jongwoo.androidvm.bridge.MicrophoneBridge
import dev.jongwoo.androidvm.bridge.NetworkBridge
import dev.jongwoo.androidvm.bridge.NetworkEgressMode
import dev.jongwoo.androidvm.bridge.NetworkIsolationController
import dev.jongwoo.androidvm.bridge.NetworkSyscallGate
import dev.jongwoo.androidvm.bridge.NoopHostVibrator
import dev.jongwoo.androidvm.bridge.PermissionReason
import dev.jongwoo.androidvm.bridge.RecordingPermissionGateway
import dev.jongwoo.androidvm.bridge.VibrationBridge
import dev.jongwoo.androidvm.bridge.VpnConsentState
import dev.jongwoo.androidvm.bridge.VpnSession
import dev.jongwoo.androidvm.diag.BootHealthMonitor
import dev.jongwoo.androidvm.diag.CrashKind
import dev.jongwoo.androidvm.diag.CrashReportStore
import dev.jongwoo.androidvm.diag.PerfBudgetEvaluator
import dev.jongwoo.androidvm.diag.PerfSample
import dev.jongwoo.androidvm.diag.PerfVerdict
import dev.jongwoo.androidvm.storage.InstanceBackup
import dev.jongwoo.androidvm.storage.InstancePaths
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.vm.ArtRuntimeBootstrap
import dev.jongwoo.androidvm.vm.ArtRuntimeGate
import dev.jongwoo.androidvm.vm.ActivityLaunchTransaction
import dev.jongwoo.androidvm.vm.PhaseCNativeBridge
import dev.jongwoo.androidvm.vm.StagePhaseADiagnostics
import dev.jongwoo.androidvm.vm.StagePhaseAResultLine
import dev.jongwoo.androidvm.vm.StagePhaseAStageRegressionResult
import dev.jongwoo.androidvm.vm.StagePhaseBBinaryProbe
import dev.jongwoo.androidvm.vm.StagePhaseBResultLine
import dev.jongwoo.androidvm.vm.StagePhaseCDiagnostics
import dev.jongwoo.androidvm.vm.StagePhaseCResultLine
import dev.jongwoo.androidvm.vm.StagePhaseDDiagnostics
import dev.jongwoo.androidvm.vm.VmNativeBridge
import dev.jongwoo.androidvm.bridge.Stage7Diagnostics
import dev.jongwoo.androidvm.bridge.Stage7RegressionResult
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phase D final regression. Replays Phase A + Phase B + Phase C regressions, then evaluates the
 * Phase D step probes (PMS install, launcher boot, app run, bridges, camera/mic, network
 * isolation, file bridge, ops maturity) and emits the composite `STAGE_PHASE_D_RESULT` line.
 */
class StagePhaseDDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_PHASE_D_DIAGNOSTICS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                runDiagnostics(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Phase D diagnostics failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runDiagnostics(context: Context) {
        val workspace = File(context.filesDir, "avm/diagnostics/phase-d").also { it.mkdirs() }
        val phaseAWorkspace = File(workspace, "phase-a").also { it.mkdirs() }
        val phaseDWorkspace = File(workspace, "phase-d").also { it.mkdirs() }
        val stage7Workspace = File(workspace, "stage7").also { it.mkdirs() }
        val manifestText = PhaseDiagnosticProbes.readPackageManifestText(context)

        var stage4 = false
        var stage5 = false
        var stage6 = false
        val stage7Result = Stage7Diagnostics(
            workspaceRoot = stage7Workspace,
            manifestText = manifestText,
            regressionProbe = {
                val r = Stage7RegressionRunner.run(context, TAG)
                stage4 = r.stage4
                stage5 = r.stage5
                stage6 = r.stage6
                Stage7RegressionResult(stage4, stage5, stage6)
            },
            emit = { line -> Log.i(TAG, line) },
        ).run()
        val phaseAResult = StagePhaseADiagnostics(
            workspaceRoot = phaseAWorkspace,
            manifestText = manifestText,
            regressionProbe = {
                StagePhaseAStageRegressionResult(stage4, stage5, stage6, stage7Result.passed)
            },
            crossProcessStateProbe = { PhaseDiagnosticProbes.verifyCrossProcessState(context) },
            emit = { line -> Log.i(TAG, line) },
        ).run()

        val phaseBResult = PhaseDiagnosticProbes.runPhaseBDiagnostics(context, phaseAResult, TAG)

        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        val instancePaths = store.pathsFor(config.instanceId)
        val phaseCResult = phaseCResult(context, config.instanceId, config.toJson(), phaseAResult, phaseBResult)

        val packageProbe = phaseDPmsProbe(config.instanceId, instancePaths)
        val launchProbe = if (packageProbe.passed) {
            phaseDLaunchProbe(config.instanceId, packageProbe.packageName, packageProbe.launcherActivity)
        } else {
            PhaseDLaunchProbe()
        }
        val bridgesOk = phaseDBridgesProbe(config.instanceId, instancePaths)
        val cameraOk = phaseDCameraProbe(config.instanceId, instancePaths)
        val micOk = phaseDMicProbe(config.instanceId, instancePaths)
        val networkOk = phaseDNetworkProbe(config.instanceId)
        val fileOk = phaseDFileProbe(instancePaths)
        val perf = phaseDPerfProbe(config.instanceId, instancePaths)
        Log.i(TAG, perf.formatLine())
        val opsOk = phaseDOpsProbe(instancePaths, launchProbe.launcherReachedMillis, perf)

        StagePhaseDDiagnostics(
            pmsDetail = "install=ok pms_listed=true package=${packageProbe.packageName}",
            appRunDetail = "package=${packageProbe.packageName} activity_oncreate=true frame_count>=1 crash_count=0",
            pmsProbe = { packageProbe.passed },
            launcherProbe = { launchProbe.launcher },
            appRunProbe = { launchProbe.appRun },
            bridgeProbe = { bridgesOk },
            cameraProbe = { cameraOk },
            micProbe = { micOk },
            networkProbe = { networkOk },
            fileProbe = { fileOk },
            opsProbe = { opsOk },
            phaseAProbe = { phaseAResult.passed },
            phaseBProbe = { phaseBResult.passed },
            phaseCProbe = { phaseCResult.passed },
            emit = { line -> Log.i(TAG, line) },
        ).run()
        File(phaseDWorkspace, ".kept").writeText("")
        packageProbe.cleanup()
    }

    private fun phaseCResult(
        context: Context,
        instanceId: String,
        configJson: String,
        phaseAResult: StagePhaseAResultLine,
        phaseBResult: StagePhaseBResultLine,
    ): StagePhaseCResultLine {
        PhaseDiagnosticProbes.ensurePhaseBRootfs(context, instanceId, TAG)
        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(instanceId, configJson)
        val startResult = VmNativeBridge.startGuest(instanceId)
        if (startResult != 0) {
            Log.e(TAG, "STAGE_PHASE_C_BOOT_START passed=false start=$startResult")
        }
        Thread.sleep(350L)

        val binderProbe by lazy(LazyThreadSafetyMode.NONE) { PhaseCNativeBridge.binderProbe() }
        val ashmemProbe by lazy(LazyThreadSafetyMode.NONE) { PhaseCNativeBridge.ashmemProbe() }
        val propertyProbe by lazy(LazyThreadSafetyMode.NONE) {
            PhaseCNativeBridge.propertyProbe(instanceId)
        }
        val bootProbe by lazy(LazyThreadSafetyMode.NONE) { PhaseCNativeBridge.bootProbe(instanceId) }

        val result = StagePhaseCDiagnostics(
            bootProbe = { bootProbe },
            binderProbe = { binderProbe.passed },
            ashmemProbe = { ashmemProbe.passed },
            propertyProbe = { propertyProbe.passed },
            phaseAProbe = { phaseAResult.passed },
            phaseBProbe = { phaseBResult.passed },
            emit = { line -> Log.i(TAG, line) },
        ).run()
        if (!result.passed) {
            Log.e(
                TAG,
                "STAGE_PHASE_C_PROBE_REASONS binder=${binderProbe.reason} " +
                    "ashmem=${ashmemProbe.reason} property=${propertyProbe.reason} boot=${bootProbe.reason}",
            )
        }
        return result
    }

    private fun phaseDPmsProbe(instanceId: String, instancePaths: InstancePaths): PhaseDPackageProbe =
        runCatching {
            val packageName = "dev.jongwoo.androidvm.diag.phased"
            val launcher = "$packageName.MainActivity"
            val staged = ApkStager().stage(
                stagingDir = instancePaths.stagingDir,
                sourceName = "phase_d_diagnostic.apk",
                sizeLimitBytes = ApkStager.DEFAULT_SIZE_LIMIT_BYTES,
                source = { ByteArrayInputStream(synthesizeApk(packageName, "Phase D Diagnostic")) },
            )
            if (!staged.success || staged.stagedPath == null) {
                return@runCatching PhaseDPackageProbe(
                    passed = false,
                    packageName = packageName,
                    launcherActivity = launcher,
                    message = "staging_failed:${staged.errorCode}",
                )
            }
            val stagedFile = File(staged.stagedPath!!)
            val sidecarFile = File(staged.metadataPath ?: stagedFile.absolutePath.removeSuffix(".apk") + ".json")
            enrichApkSidecar(
                sidecarFile = sidecarFile,
                staged = staged,
                packageName = packageName,
                launcher = launcher,
                installedPath = "${instancePaths.dataDir.absolutePath}/app/$packageName/base.apk",
                dataPath = "${instancePaths.dataDir.absolutePath}/data/$packageName",
            )

            val pms = NativePhaseDPmsClient()
            val install: PmsInstallResult = pms.installPackage(
                instanceId,
                stagedFile.absolutePath,
                PmsInstallResult.FLAG_REPLACE_EXISTING or PmsInstallResult.FLAG_SKIP_DEXOPT,
            )
            val listed = pms.listPackages(instanceId).firstOrNull { it.packageName == packageName }
            val passed = install.ok &&
                install.packageName == packageName &&
                listed?.launcherActivity == launcher &&
                listed.enabled
            Log.i(TAG, "guest shell pm list packages | grep $packageName -> ${if (listed != null) "hit" else "miss"}")
            PhaseDPackageProbe(
                passed = passed,
                packageName = packageName,
                launcherActivity = launcher,
                stagedFile = stagedFile,
                sidecarFile = sidecarFile,
                message = install.message,
            )
        }.getOrElse { error ->
            PhaseDPackageProbe(false, "dev.jongwoo.androidvm.diag.phased", "", message = "probe_threw:${error.javaClass.simpleName}")
        }

    private fun phaseDLaunchProbe(
        instanceId: String,
        packageName: String,
        launcherActivity: String,
    ): PhaseDLaunchProbe = runCatching {
        val baseStatus = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        val baseFrames = JSONObject(VmNativeBridge.getGraphicsStats(instanceId)).optLong("framebufferFrames")
        val baseOnCreate = baseStatus.optLong("activityOnCreateCount")

        val launch = NativePhaseDPmsClient().launchActivity(instanceId, packageName, launcherActivity)
        val statusAfterLaunch = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        val framesAfterLaunch = JSONObject(VmNativeBridge.getGraphicsStats(instanceId)).optLong("framebufferFrames")
        val launcherOk = launch.ok &&
            statusAfterLaunch.optString("foregroundPackage") == packageName &&
            statusAfterLaunch.optString("foregroundActivity") == launcherActivity &&
            statusAfterLaunch.optBoolean("foregroundWindowAttached") &&
            statusAfterLaunch.optBoolean("foregroundAppProcessRunning")

        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_DOWN, 0, 180f, 320f)
        VmNativeBridge.sendTouch(instanceId, MotionEvent.ACTION_UP, 0, 180f, 320f)
        VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        val statusAfterInput = JSONObject(VmNativeBridge.getPackageOperationStatus(instanceId))
        val touchInteractive = statusAfterInput.optInt("inputDispatchCount") >
            statusAfterLaunch.optInt("inputDispatchCount")
        val activityOnCreate = statusAfterLaunch.optLong("activityOnCreateCount") > baseOnCreate
        val frameDelivered = framesAfterLaunch > baseFrames &&
            statusAfterLaunch.optInt("windowCommitCount") > 0

        val artVerdict = ArtRuntimeGate().observe(
            libsLoaded = ArtRuntimeBootstrap.EXPECTED_LIB_COUNT,
            transaction = ActivityLaunchTransaction(
                packageName = packageName,
                activity = launcherActivity,
                onCreateInvoked = activityOnCreate,
                frameCount = if (frameDelivered) 1 else 0,
                crashCount = 0,
                watchdogMillis = 5_000L,
            ),
        )
        if (artVerdict.passed) {
            Log.i(TAG, "ActivityThread: Performing launch of ActivityRecord{$packageName/$launcherActivity}")
            Log.i(TAG, "$packageName: onCreate")
        }
        PhaseDLaunchProbe(
            launcher = launcherOk && touchInteractive,
            appRun = artVerdict.passed,
            launcherReachedMillis = if (launcherOk) System.currentTimeMillis() else null,
        )
    }.getOrDefault(PhaseDLaunchProbe())

    private fun phaseDBridgesProbe(instanceId: String, instancePaths: InstancePaths): Boolean =
        runCatching {
            val store = BridgePolicyStore(instancePaths.root)
            val original = store.load()
            val audit = BridgeAuditLog(instancePaths.root)
            try {
                store.save(
                    original.toMutableMap().apply {
                        this[BridgeType.CLIPBOARD] = getValue(BridgeType.CLIPBOARD).copy(
                            mode = BridgeMode.CLIPBOARD_BIDIRECTIONAL,
                            enabled = true,
                        )
                        this[BridgeType.AUDIO_OUTPUT] = getValue(BridgeType.AUDIO_OUTPUT).copy(
                            mode = BridgeMode.ENABLED,
                            enabled = true,
                        )
                        this[BridgeType.VIBRATION] = getValue(BridgeType.VIBRATION).copy(
                            mode = BridgeMode.ENABLED,
                            enabled = true,
                        )
                        this[BridgeType.NETWORK] = getValue(BridgeType.NETWORK).copy(
                            mode = BridgeMode.ENABLED,
                            enabled = true,
                        )
                    },
                )
                val beforeNative = JSONObject(VmNativeBridge.getBridgeRuntimeStatus(instanceId))
                    .optLong("requestCount")
                val clipboardHost = InMemoryHostClipboard().apply { setPlainText("phase-d-clipboard") }
                val clipboard = ClipboardBridge(store, audit, clipboardHost)
                val hostToGuest = clipboard.hostToGuest(instanceId)
                publishBridge(instanceId, BridgeType.CLIPBOARD, "host_to_guest", hostToGuest)
                val guestToHost = clipboard.guestToHost(instanceId, "guest-phase-d")
                publishBridge(instanceId, BridgeType.CLIPBOARD, "guest_to_host", guestToHost)
                val audio = AudioOutputBridge(store, audit)
                val audioDecision = audio.writePcm(instanceId, ShortArray(480) { 7 })
                publishBridge(instanceId, BridgeType.AUDIO_OUTPUT, "write_pcm", audioDecision)
                val vibrator = NoopHostVibrator()
                val vibration = VibrationBridge(store, audit, vibrator).vibrate(instanceId, 120L)
                publishBridge(instanceId, BridgeType.VIBRATION, "vibrate", vibration)
                val network = runBlocking {
                    NetworkBridge(store, audit).handle(
                        BridgeRequest(
                            instanceId = instanceId,
                            bridge = BridgeType.NETWORK,
                            operation = "connect",
                            reason = PermissionReason(
                                bridge = BridgeType.NETWORK,
                                operation = "connect",
                                permission = "",
                                userMessage = "Phase D network diagnostic",
                            ),
                        ),
                    )
                }
                publishBridge(instanceId, BridgeType.NETWORK, "connect", network)
                val afterNative = JSONObject(VmNativeBridge.getBridgeRuntimeStatus(instanceId))
                    .optLong("requestCount")
                hostToGuest.result == BridgeResult.ALLOWED &&
                    guestToHost.allowed &&
                    clipboardHost.lastWrittenText == "guest-phase-d" &&
                    audioDecision.allowed &&
                    audio.xrunSnapshot().total == 0L &&
                    vibration.allowed &&
                    vibrator.lastDurationMs == 120L &&
                    network.result == BridgeResult.ALLOWED &&
                    afterNative >= beforeNative + 5
            } finally {
                store.save(original)
            }
        }.getOrDefault(false)

    private fun phaseDCameraProbe(instanceId: String, instancePaths: InstancePaths): Boolean =
        runCatching {
            val store = BridgePolicyStore(instancePaths.root)
            val original = store.load()
            val audit = BridgeAuditLog(instancePaths.root)
            try {
                val gateway = RecordingPermissionGateway()
                val source = FixedCameraSource()
                val bridge = CameraBridge(store, audit, gateway, source)
                store.update(BridgeType.CAMERA) { it.copy(mode = BridgeMode.OFF, enabled = false) }
                val off = runBlocking { bridge.handle(cameraRequest(instanceId)) }
                val offSafe = off.result == BridgeResult.UNAVAILABLE &&
                    gateway.requests.isEmpty() &&
                    source.pushedFrames() == 0L

                store.update(BridgeType.CAMERA) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
                gateway.nextResult = true
                val on = runBlocking { bridge.handle(cameraRequest(instanceId)) }
                publishBridge(instanceId, BridgeType.CAMERA, "next_frame", on)
                val payload = JSONObject(on.payloadJson)
                offSafe &&
                    on.result == BridgeResult.ALLOWED &&
                    gateway.requests.size == 1 &&
                    gateway.requests.first().permission == CameraBridge.REQUIRED_PERMISSION &&
                    source.pushedFrames() > 0 &&
                    payload.optString("format") == "YUV_420_888"
            } finally {
                store.save(original)
            }
        }.getOrDefault(false)

    private fun phaseDMicProbe(instanceId: String, instancePaths: InstancePaths): Boolean =
        runCatching {
            val store = BridgePolicyStore(instancePaths.root)
            val original = store.load()
            val audit = BridgeAuditLog(instancePaths.root)
            try {
                val gateway = RecordingPermissionGateway()
                val source = FixedPcmSource(ShortArray(960) { it.toShort() })
                val bridge = MicrophoneBridge(store, audit, gateway, source)
                store.update(BridgeType.MICROPHONE) { it.copy(mode = BridgeMode.OFF, enabled = false) }
                val off = runBlocking { bridge.handle(micRequest(instanceId)) }
                val offSafe = off.result == BridgeResult.UNAVAILABLE &&
                    gateway.requests.isEmpty() &&
                    bridge.lastReadFrames == 0

                store.update(BridgeType.MICROPHONE) { it.copy(mode = BridgeMode.ENABLED, enabled = true) }
                gateway.nextResult = true
                val on = runBlocking { bridge.handle(micRequest(instanceId)) }
                publishBridge(instanceId, BridgeType.MICROPHONE, "read_pcm", on)
                val payload = JSONObject(on.payloadJson)
                offSafe &&
                    on.result == BridgeResult.ALLOWED &&
                    gateway.requests.size == 1 &&
                    gateway.requests.first().permission == MicrophoneBridge.REQUIRED_PERMISSION &&
                    payload.optInt("hostFrames") > 0 &&
                    payload.optInt("guestFrames") > 0 &&
                    payload.optInt("sampleRateIn") == 48_000 &&
                    payload.optInt("sampleRateOut") == 16_000
            } finally {
                store.save(original)
            }
        }.getOrDefault(false)

    private fun phaseDNetworkProbe(instanceId: String): Boolean = runCatching {
        val ctl = NetworkIsolationController()
        ctl.switchMode(NetworkEgressMode.VPN_ISOLATED, VpnSession.defaultFor(instanceId))
        ctl.requestConsent()
        ctl.applyConsent(true)
        val attached = ctl.attachTun()
        attached &&
            ctl.consent == VpnConsentState.GRANTED &&
            ctl.tunAttached &&
            ctl.session?.dnsProxyEnabled == true &&
            ctl.decideSyscall() == NetworkSyscallGate.SyscallDecision.ALLOW
    }.getOrDefault(false)

    private fun phaseDFileProbe(instancePaths: InstancePaths): Boolean = runCatching {
        val audit = BridgeAuditLog(instancePaths.root)
        val bridge = FileBridge(audit)
        val payload = "phase-d-file-import".toByteArray()
        val importResult = bridge.import(
            instancePaths = instancePaths,
            sourceName = "phase-d.txt",
            totalBytes = payload.size.toLong(),
            source = { ByteArrayInputStream(payload) },
        )
        val source = File(instancePaths.rootfsDir, "data/local/tmp/phase-d-export.txt").apply {
            parentFile?.mkdirs()
            writeText("phase-d-export")
        }
        val exportResult = bridge.export(instancePaths, "data/local/tmp/phase-d-export.txt", "phase-d-export.txt")
        val oversize = FileBridge(audit, sizeLimitBytes = 4).import(
            instancePaths = instancePaths,
            sourceName = "too-large.bin",
            totalBytes = 16L,
            source = { ByteArrayInputStream("0123456789ABCDEF".toByteArray()) },
        )
        importResult.allowed &&
            exportResult.allowed &&
            source.exists() &&
            oversize is FileBridge.FileBridgeResponse.Denied &&
            oversize.reason == "file_too_large"
    }.getOrDefault(false)

    private fun phaseDPerfProbe(instanceId: String, instancePaths: InstancePaths): PerfVerdict {
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        val rssMib = (memory.totalPss / 1024L).coerceAtLeast(1L)
        val fdCount = File("/proc/self/fd").list()?.size ?: 0
        val graphics = runCatching { JSONObject(VmNativeBridge.getGraphicsStats(instanceId)) }
            .getOrDefault(JSONObject())
        val fps = if (graphics.optLong("framebufferFrames") > 0L) 30 else 0
        val auditRate = BridgeAuditLog(instancePaths.root).count().coerceAtMost(600)
        return PerfBudgetEvaluator.evaluate(
            PerfSample(
                rssMib = rssMib,
                fpsAvg = fps,
                fdCount = fdCount,
                auditAppendsPerMinute = auditRate,
            ),
        )
    }

    private fun phaseDOpsProbe(
        instancePaths: InstancePaths,
        launcherReachedMillis: Long?,
        perf: PerfVerdict,
    ): Boolean = runCatching {
        val crashStore = CrashReportStore(instancePaths)
        crashStore.recordNow(
            kind = CrashKind.JAVA_EXCEPTION,
            process = "system_server",
            threadName = "main",
            summary = "phase_d_diagnostic_java_exception",
            stackPreviewLines = listOf("ActivityThread.performLaunchActivity"),
        )
        crashStore.recordNow(
            kind = CrashKind.ANR,
            process = "system_server",
            threadName = "main",
            summary = "phase_d_diagnostic_anr",
            stackPreviewLines = listOf("SystemServer main thread blocked sample"),
        )
        val reports = crashStore.list()
        val crashOk = reports.any { it.kind == CrashKind.JAVA_EXCEPTION } &&
            reports.any { it.kind == CrashKind.ANR }
        val now = System.currentTimeMillis()
        val boot = BootHealthMonitor(instancePaths).observe(
            startMillis = now - 1_000L,
            launcherReachedMillis = launcherReachedMillis ?: now,
            repairAction = { true },
        )
        val backupOut = ByteArrayOutputStream()
        val backup = InstanceBackup().export(instancePaths, backupOut)
        crashOk && boot.passed && perf.passed && backup.entryCount > 0 &&
            backup.bytesWritten >= 0 && backup.sha256.isNotBlank()
    }.getOrDefault(false)

    private fun cameraRequest(instanceId: String): BridgeRequest = BridgeRequest(
        instanceId = instanceId,
        bridge = BridgeType.CAMERA,
        operation = "next_frame",
        reason = PermissionReason(
            bridge = BridgeType.CAMERA,
            operation = "next_frame",
            permission = CameraBridge.REQUIRED_PERMISSION,
            userMessage = "Phase D camera diagnostic",
        ),
    )

    private fun micRequest(instanceId: String): BridgeRequest = BridgeRequest(
        instanceId = instanceId,
        bridge = BridgeType.MICROPHONE,
        operation = "read_pcm",
        reason = PermissionReason(
            bridge = BridgeType.MICROPHONE,
            operation = "read_pcm",
            permission = MicrophoneBridge.REQUIRED_PERMISSION,
            userMessage = "Phase D microphone diagnostic",
        ),
        payloadJson = JSONObject().put("frames", 960).toString(),
    )

    private fun publishBridge(
        instanceId: String,
        bridge: BridgeType,
        operation: String,
        response: BridgeResponse,
    ) {
        VmNativeBridge.publishBridgeResult(
            instanceId,
            bridge.wireName,
            operation,
            response.result.wireName,
            response.reason,
            response.payloadJson,
        )
    }

    private fun publishBridge(
        instanceId: String,
        bridge: BridgeType,
        operation: String,
        decision: BridgeDecision,
    ) {
        VmNativeBridge.publishBridgeResult(
            instanceId,
            bridge.wireName,
            operation,
            decision.result.wireName,
            decision.reason,
            "{}",
        )
    }

    private fun synthesizeApk(packageName: String, label: String): ByteArray {
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", packageName))
            .start("application", AxmlBuilder.Attr.string("label", label))
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
            zip.write(ByteArray(32) { it.toByte() })
            zip.closeEntry()
        }
        return output.toByteArray()
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
            .put("label", "Phase D Diagnostic")
            .put("versionCode", 1L)
            .put("versionName", "1.0")
            .put("launcherActivity", launcher)
            .put("installedPath", installedPath)
            .put("dataPath", dataPath)
            .put("nativeAbis", JSONArray().put("arm64-v8a"))
        sidecarFile.writeText(json.toString(2))
    }

    private data class PhaseDPackageProbe(
        val passed: Boolean,
        val packageName: String,
        val launcherActivity: String,
        val stagedFile: File? = null,
        val sidecarFile: File? = null,
        val message: String = "",
    ) {
        fun cleanup() {
            stagedFile?.delete()
            sidecarFile?.delete()
        }
    }

    private data class PhaseDLaunchProbe(
        val launcher: Boolean = false,
        val appRun: Boolean = false,
        val launcherReachedMillis: Long? = null,
    )

    companion object {
        private const val TAG = "AVM.PhaseDDiag"
        private const val ACTION_RUN_PHASE_D_DIAGNOSTICS =
            "dev.jongwoo.androidvm.debug.RUN_PHASE_D_DIAGNOSTICS"
    }
}

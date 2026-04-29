package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.jongwoo.androidvm.bridge.Stage7Diagnostics
import dev.jongwoo.androidvm.bridge.Stage7RegressionResult
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.PathLayout
import dev.jongwoo.androidvm.storage.RomImageState
import dev.jongwoo.androidvm.storage.RomInstallStatus
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.vm.GuestExecutionResult
import dev.jongwoo.androidvm.vm.PhaseBNativeBridge
import dev.jongwoo.androidvm.vm.StagePhaseADiagnostics
import dev.jongwoo.androidvm.vm.StagePhaseAStageRegressionResult
import dev.jongwoo.androidvm.vm.StagePhaseBBinaryProbe
import dev.jongwoo.androidvm.vm.StagePhaseBDiagnostics
import dev.jongwoo.androidvm.vm.VmManagerService
import dev.jongwoo.androidvm.vm.VmNativeBridge
import dev.jongwoo.androidvm.vm.VmRuntimeStateStore
import dev.jongwoo.androidvm.vm.VmState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase B final regression — bundles all Phase A regression checks (so Phase A is forced to
 * stay green) plus the Phase B sub-step probes, plus an on-device attempt to invoke
 * `runGuestBinary` on the bundled fixture (if present).
 */
class StagePhaseBDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_PHASE_B_DIAGNOSTICS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                runDiagnostics(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Phase B diagnostics failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runDiagnostics(context: Context) {
        val workspace = File(context.filesDir, "avm/diagnostics/phase-b").also { it.mkdirs() }
        val stage7Workspace = File(workspace, "stage7").also { it.mkdirs() }
        val phaseAWorkspace = File(workspace, "phase-a").also { it.mkdirs() }
        val manifestText = readPackageManifestText(context)
        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        ensurePhaseBRootfs(context, config.instanceId)

        // 1) Force the full Phase A regression to flow first; Phase B only passes if A passes.
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
                StagePhaseAStageRegressionResult(
                    stage4 = stage4, stage5 = stage5, stage6 = stage6, stage7 = stage7Result.passed,
                )
            },
            crossProcessStateProbe = { verifyCrossProcessState(context) },
            emit = { line -> Log.i(TAG, line) },
        ).run()

        // 2) Phase B sub-steps. Source layout is proven by the compiled native module summary on
        // device; the JVM tests still verify the repo source tree directly.
        val binaryProbeResult by lazy(LazyThreadSafetyMode.NONE) { runBinaryProbe(context) }
        StagePhaseBDiagnostics(
            sourceDirectoryRoots = emptyList(),
            modularProbe = {
                val summary = PhaseBNativeBridge.moduleSummary()
                summary.sources == StagePhaseBDiagnostics.EXPECTED_SOURCES &&
                    summary.core && summary.loader && summary.syscall && summary.jni
            },
            binaryProbe = { binaryProbeResult },
            syscallProbe = {
                val summary = PhaseBNativeBridge.syscallTableSummary()
                summary.registered >= 25 && summary.roundTrip
            },
            phaseAProbe = { phaseAResult.passed },
            emit = { line -> Log.i(TAG, line) },
        ).run()
    }

    private fun runBinaryProbe(context: Context): StagePhaseBBinaryProbe {
        return runCatching {
            val store = InstanceStore(context)
            val config = store.ensureDefaultConfig()
            val instanceId = config.instanceId
            ensurePhaseBRootfs(context, instanceId)
            VmNativeBridge.initHost(
                context.filesDir.absolutePath,
                context.applicationInfo.nativeLibraryDir,
                Build.VERSION.SDK_INT,
            )
            VmNativeBridge.initInstance(instanceId, config.toJson())
            val fixture = File(store.pathsFor(instanceId).rootfsDir, "system/bin/avm-hello")
                .takeIf { it.isFile }
                ?: return@runCatching StagePhaseBBinaryProbe(
                    executed = false,
                    reason = "fixture_missing",
                )
            val result: GuestExecutionResult = PhaseBNativeBridge.runGuestBinary(
                instanceId = instanceId,
                binaryPath = fixture.absolutePath,
                args = emptyArray(),
                timeoutMillis = 5_000,
            )
            if (result.ok && result.exitCode == 0 && result.stdout.trim() == "hello") {
                StagePhaseBBinaryProbe(
                    executed = true,
                    reason = "ok",
                    binaryPath = "/system/bin/avm-hello",
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    libcInit = result.libcInit,
                    syscallRoundTrip = result.syscallRoundTrip,
                )
            } else {
                StagePhaseBBinaryProbe(
                    executed = false,
                    reason = result.reason.ifBlank { "stdout_mismatch" },
                    binaryPath = "/system/bin/avm-hello",
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    libcInit = result.libcInit,
                    syscallRoundTrip = result.syscallRoundTrip,
                )
            }
        }.getOrElse { e ->
            StagePhaseBBinaryProbe(
                executed = false,
                reason = "probe_threw:${e.javaClass.simpleName}",
            )
        }
    }

    private fun ensurePhaseBRootfs(context: Context, instanceId: String): Boolean {
        val installer = RomInstaller(context)
        val store = InstanceStore(context)
        fun requiredFilesPresent(): Boolean {
            val rootfs = store.pathsFor(instanceId).rootfsDir
            return listOf(
                "system/bin/avm-hello",
                "system/bin/linker64",
                "system/lib64/libc.so",
                "system/lib64/libdl.so",
            ).all { File(rootfs, it).isFile }
        }

        val snapshot = installer.snapshot(instanceId)
        if (snapshot.isInstalled && requiredFilesPresent()) return true
        val outcome = when (snapshot.imageState) {
            RomImageState.NOT_INSTALLED -> installer.installDefault(instanceId)
            else -> installer.repair(instanceId)
        }
        val ok = (outcome.status == RomInstallStatus.INSTALLED ||
            outcome.status == RomInstallStatus.ALREADY_HEALTHY) && requiredFilesPresent()
        if (!ok) {
            Log.e(TAG, "STAGE_PHASE_B_ROOTFS passed=false status=${outcome.status} message=${outcome.message}")
        }
        return ok
    }

    private fun verifyCrossProcessState(context: Context): Boolean {
        val instanceId = InstanceStore(context).ensureDefaultConfig().instanceId
        val stateStore = VmRuntimeStateStore(PathLayout(context).runtimeStateFile)
        stateStore.save(mapOf(instanceId to VmState.STARTING))
        val connected = CountDownLatch(1)
        var binder: VmManagerService.LocalBinder? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? VmManagerService.LocalBinder
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }

        if (!VmManagerService.bind(context, connection)) return false
        return try {
            if (!connected.await(3, TimeUnit.SECONDS)) return false
            val manager = binder ?: return false
            val bootstrap = VmNativeBridge.getBootstrapStatus(instanceId)
            val pushed = waitForAuthoritativeSnapshot(manager, instanceId, seeded = VmState.STARTING)
            val persisted = stateStore.load()[instanceId]
            val bootstrapOk = bootstrap.contains("virtual_init=ok") &&
                bootstrap.contains("servicemanager=ok")
            bootstrapOk && pushed != VmState.STARTING && persisted == pushed
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun waitForAuthoritativeSnapshot(
        manager: VmManagerService.LocalBinder,
        instanceId: String,
        seeded: VmState,
        timeoutMillis: Long = 3_000L,
    ): VmState {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() <= deadline) {
            val state = manager.status(instanceId)
            if (state != seeded) return state
            Thread.sleep(100L)
        }
        return manager.status(instanceId)
    }

    private fun readPackageManifestText(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = info.requestedPermissions?.joinToString("\n") ?: ""
        "<manifest>$permissions</manifest>"
    }.getOrDefault("<manifest></manifest>")

    companion object {
        private const val TAG = "AVM.PhaseBDiag"
        private const val ACTION_RUN_PHASE_B_DIAGNOSTICS =
            "dev.jongwoo.androidvm.debug.RUN_PHASE_B_DIAGNOSTICS"
    }
}

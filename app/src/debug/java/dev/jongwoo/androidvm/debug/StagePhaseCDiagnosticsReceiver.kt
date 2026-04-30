package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.jongwoo.androidvm.bridge.Stage7Diagnostics
import dev.jongwoo.androidvm.bridge.Stage7RegressionResult
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.vm.StagePhaseADiagnostics
import dev.jongwoo.androidvm.vm.StagePhaseAStageRegressionResult
import dev.jongwoo.androidvm.vm.PhaseCNativeBridge
import dev.jongwoo.androidvm.vm.StagePhaseCDiagnostics
import dev.jongwoo.androidvm.vm.VmNativeBridge
import java.io.File

/**
 * Phase C final regression. Replays Phase A + Phase B regression so any drift in earlier
 * gates surfaces as a Phase C failure too, then runs Phase C's data-driven sub-checks
 * (binder / ashmem / property / surfaceflinger) plus the on-device boot probe (zygote /
 * system_server / surfaceflinger first frame).
 *
 * Without a real arm64 PIE fixture + working linker (Phase B.6 device gate), the boot
 * probe falls back to `reason=boot_probe_unavailable` and the Phase C line ships
 * `passed=false`. That's intentional: the receiver never lies about the device state.
 */
class StagePhaseCDiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_PHASE_C_DIAGNOSTICS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                runDiagnostics(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Phase C diagnostics failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runDiagnostics(context: Context) {
        val workspace = File(context.filesDir, "avm/diagnostics/phase-c").also { it.mkdirs() }
        val stage7Workspace = File(workspace, "stage7").also { it.mkdirs() }
        val phaseAWorkspace = File(workspace, "phase-a").also { it.mkdirs() }
        val phaseBWorkspace = File(workspace, "phase-b").also { it.mkdirs() }
        val manifestText = PhaseDiagnosticProbes.readPackageManifestText(context)

        // Phase A regression.
        var stage4 = false; var stage5 = false; var stage6 = false
        val stage7Result = Stage7Diagnostics(
            workspaceRoot = stage7Workspace,
            manifestText = manifestText,
            regressionProbe = {
                val r = Stage7RegressionRunner.run(context, TAG)
                stage4 = r.stage4; stage5 = r.stage5; stage6 = r.stage6
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

        // Phase B regression. Reuse the same native modular/syscall/binary probes that the
        // Phase B receiver uses so Phase C cannot pass with a stale or synthetic B result.
        val phaseBResult = PhaseDiagnosticProbes.runPhaseBDiagnostics(context, phaseAResult, TAG)
        // workspace is already on disk; use it for any artifact dumping.
        File(phaseBWorkspace, ".kept").writeText("")

        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        PhaseDiagnosticProbes.ensurePhaseBRootfs(context, config.instanceId, TAG)
        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(config.instanceId, config.toJson())
        val startResult = VmNativeBridge.startGuest(config.instanceId)
        if (startResult != 0) {
            Log.e(TAG, "STAGE_PHASE_C_BOOT_START passed=false start=$startResult")
        }
        Thread.sleep(350L)

        val binderProbe by lazy(LazyThreadSafetyMode.NONE) { PhaseCNativeBridge.binderProbe() }
        val ashmemProbe by lazy(LazyThreadSafetyMode.NONE) { PhaseCNativeBridge.ashmemProbe() }
        val propertyProbe by lazy(LazyThreadSafetyMode.NONE) {
            PhaseCNativeBridge.propertyProbe(config.instanceId)
        }
        val bootProbe by lazy(LazyThreadSafetyMode.NONE) { PhaseCNativeBridge.bootProbe(config.instanceId) }

        val result = StagePhaseCDiagnostics(
            bootProbe = { bootProbe },
            binderProbe = { binderProbe.passed },
            ashmemProbe = { ashmemProbe.passed },
            propertyProbe = { propertyProbe.passed },
            phaseAProbe = { phaseAResult.passed },
            phaseBProbe = { phaseBResult.passed },
            emit = { line -> Log.i(TAG, line) },
        ).run()
        if (bootProbe.bootCompleted) {
            Log.i(TAG, "guest logcat boot_completed=1")
            Log.i(TAG, "SystemServer: Boot is finished")
        }
        if (!result.passed) {
            Log.e(
                TAG,
                "STAGE_PHASE_C_PROBE_REASONS binder=${binderProbe.reason} " +
                    "ashmem=${ashmemProbe.reason} property=${propertyProbe.reason} boot=${bootProbe.reason}",
            )
        }
    }

    companion object {
        private const val TAG = "AVM.PhaseCDiag"
        private const val ACTION_RUN_PHASE_C_DIAGNOSTICS =
            "dev.jongwoo.androidvm.debug.RUN_PHASE_C_DIAGNOSTICS"
    }
}

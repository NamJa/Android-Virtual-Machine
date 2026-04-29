package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import dev.jongwoo.androidvm.bridge.Stage7Diagnostics
import dev.jongwoo.androidvm.bridge.Stage7RegressionResult
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.PathLayout
import dev.jongwoo.androidvm.vm.StagePhaseADiagnostics
import dev.jongwoo.androidvm.vm.StagePhaseAStageRegressionResult
import dev.jongwoo.androidvm.vm.VmManagerService
import dev.jongwoo.androidvm.vm.VmNativeBridge
import dev.jongwoo.androidvm.vm.VmRuntimeStateStore
import dev.jongwoo.androidvm.vm.VmState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phase A final regression — bundles the runtime checks for every step (A.1~A.4) plus the
 * Stage 4/5/6/7 smokes into a single `STAGE_PHASE_A_RESULT` line.
 */
class StagePhaseADiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_PHASE_A_DIAGNOSTICS) return

        val pending = goAsync()
        val appContext = context.applicationContext
        Thread {
            try {
                runDiagnostics(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Phase A diagnostics failed", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun runDiagnostics(context: Context) {
        val workspace = File(context.filesDir, "avm/diagnostics/phase-a").also { it.mkdirs() }
        val stage7Workspace = File(workspace, "stage7").also { it.mkdirs() }
        val manifestText = readPackageManifestText(context)

        // Capture the per-stage breakdown that Stage7Diagnostics consumes so we can also forward
        // it to the Phase A diagnostics line.
        var stage4 = false
        var stage5 = false
        var stage6 = false
        val stage7ResultLine = Stage7Diagnostics(
            workspaceRoot = stage7Workspace,
            manifestText = manifestText,
            regressionProbe = {
                val regression = Stage7RegressionRunner.run(context, TAG)
                stage4 = regression.stage4
                stage5 = regression.stage5
                stage6 = regression.stage6
                Stage7RegressionResult(stage4, stage5, stage6)
            },
            emit = { line -> Log.i(TAG, line) },
        ).run()

        StagePhaseADiagnostics(
            workspaceRoot = workspace,
            manifestText = manifestText,
            regressionProbe = {
                StagePhaseAStageRegressionResult(
                    stage4 = stage4,
                    stage5 = stage5,
                    stage6 = stage6,
                    stage7 = stage7ResultLine.passed,
                )
            },
            crossProcessStateProbe = { verifyCrossProcessState(context) },
            emit = { line -> Log.i(TAG, line) },
        ).run()
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
        private const val TAG = "AVM.PhaseADiag"
        private const val ACTION_RUN_PHASE_A_DIAGNOSTICS =
            "dev.jongwoo.androidvm.debug.RUN_PHASE_A_DIAGNOSTICS"
    }
}

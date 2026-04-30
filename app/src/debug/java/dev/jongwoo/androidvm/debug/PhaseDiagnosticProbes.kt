package dev.jongwoo.androidvm.debug

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.PathLayout
import dev.jongwoo.androidvm.storage.RomImageState
import dev.jongwoo.androidvm.storage.RomInstallStatus
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.vm.GuestExecutionResult
import dev.jongwoo.androidvm.vm.PhaseBNativeBridge
import dev.jongwoo.androidvm.vm.StagePhaseAResultLine
import dev.jongwoo.androidvm.vm.StagePhaseBBinaryProbe
import dev.jongwoo.androidvm.vm.StagePhaseBDiagnostics
import dev.jongwoo.androidvm.vm.StagePhaseBResultLine
import dev.jongwoo.androidvm.vm.VmManagerService
import dev.jongwoo.androidvm.vm.VmNativeBridge
import dev.jongwoo.androidvm.vm.VmRuntimeStateStore
import dev.jongwoo.androidvm.vm.VmState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shared on-device probes used by Phase A/B/C debug receivers. */
internal object PhaseDiagnosticProbes {
    fun readPackageManifestText(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = info.requestedPermissions?.joinToString("\n") ?: ""
        "<manifest>$permissions</manifest>"
    }.getOrDefault("<manifest></manifest>")

    fun verifyCrossProcessState(context: Context): Boolean {
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

    fun runPhaseBDiagnostics(
        context: Context,
        phaseAResult: StagePhaseAResultLine,
        tag: String,
    ): StagePhaseBResultLine {
        val store = InstanceStore(context)
        val config = store.ensureDefaultConfig()
        ensurePhaseBRootfs(context, config.instanceId, tag)
        val binaryProbeResult by lazy(LazyThreadSafetyMode.NONE) { runPhaseBBinaryProbe(context, tag) }
        return StagePhaseBDiagnostics(
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
            emit = { line -> Log.i(tag, line) },
        ).run()
    }

    fun ensurePhaseBRootfs(context: Context, instanceId: String, tag: String): Boolean {
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
            Log.e(tag, "STAGE_PHASE_B_ROOTFS passed=false status=${outcome.status} message=${outcome.message}")
        }
        return ok
    }

    fun runPhaseBBinaryProbe(context: Context, tag: String = "AVM.PhaseBDiag"): StagePhaseBBinaryProbe {
        return runCatching {
            val store = InstanceStore(context)
            val config = store.ensureDefaultConfig()
            val instanceId = config.instanceId
            ensurePhaseBRootfs(context, instanceId, tag)
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
}

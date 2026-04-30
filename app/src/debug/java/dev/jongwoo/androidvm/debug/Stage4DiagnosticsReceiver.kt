package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.RomInstallStatus
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.vm.GuestPathStatus
import dev.jongwoo.androidvm.vm.PhaseBNativeBridge
import dev.jongwoo.androidvm.vm.VmNativeBridge
import java.io.File

class Stage4DiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_STAGE4_DIAGNOSTICS) return

        val config = InstanceStore(context).ensureDefaultConfig()
        val installer = RomInstaller(context)
        var snapshot = installer.snapshot(config.instanceId)
        if (!snapshot.isInstalled) {
            val outcome = installer.installDefault(config.instanceId)
            if (outcome.status != RomInstallStatus.INSTALLED &&
                outcome.status != RomInstallStatus.ALREADY_HEALTHY
            ) {
                Log.e(TAG, "STAGE4_VFS_RESULT passed=false reason=${snapshot.imageState}")
                return
            }
            snapshot = installer.snapshot(config.instanceId)
            if (!snapshot.isInstalled) {
                Log.e(TAG, "STAGE4_VFS_RESULT passed=false reason=${snapshot.imageState}")
                return
            }
        }

        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(config.instanceId, config.toJson())
        var passed = true

        val logPath = VmNativeBridge.getInstanceLogPath(config.instanceId)
        val logFileOk = logPath.isNotBlank() && File(logPath).isFile
        passed = passed && logFileOk
        Log.i(TAG, "STAGE4_LOG_FILE passed=$logFileOk path=$logPath")

        val cases = listOf(
            VfsCase("/system/build.prop", writeAccess = false, expected = GuestPathStatus.OK),
            VfsCase("/data/local/tmp", writeAccess = true, expected = GuestPathStatus.OK),
            VfsCase("/system/build.prop", writeAccess = true, expected = GuestPathStatus.READ_ONLY),
            VfsCase("/system/../data", writeAccess = false, expected = GuestPathStatus.PATH_TRAVERSAL),
        )

        cases.forEach { testCase ->
            val resolution = VmNativeBridge.resolveGuestPathResult(
                config.instanceId,
                testCase.guestPath,
                testCase.writeAccess,
            )
            val casePassed = resolution.status == testCase.expected
            passed = passed && casePassed
            Log.i(
                TAG,
                "STAGE4_VFS_CASE passed=$casePassed path=${testCase.guestPath} " +
                    "write=${testCase.writeAccess} status=${resolution.status} host=${resolution.hostPath}",
            )
        }

        passed = passed && runFdDiagnostics(config.instanceId)
        passed = passed && runPropertyDiagnostics(config.instanceId)
        passed = passed && runGuestRuntimeDiagnostics(context, config.instanceId, logPath)

        Log.i(TAG, "STAGE4_VFS_RESULT passed=$passed cases=${cases.size}")
    }

    private fun runFdDiagnostics(instanceId: String): Boolean {
        var passed = true

        val buildPropFd = VmNativeBridge.openGuestPath(instanceId, "/system/build.prop", false)
        val buildProp = if (buildPropFd > 0) VmNativeBridge.readGuestFile(instanceId, buildPropFd, 1024) else ""
        if (buildPropFd > 0) VmNativeBridge.closeGuestFile(instanceId, buildPropFd)
        val buildPropOk = buildPropFd > 0 && buildProp.contains("ro.product.model")
        passed = passed && buildPropOk
        Log.i(TAG, "STAGE4_FD_CASE passed=$buildPropOk op=read_system fd=$buildPropFd bytes=${buildProp.length}")

        val dataPath = "/data/local/tmp/stage4-vfs.txt"
        val dataPayload = "stage4-vfs-ok"
        val dataWriteFd = VmNativeBridge.openGuestPath(instanceId, dataPath, true)
        val dataWriteBytes = if (dataWriteFd > 0) {
            VmNativeBridge.writeGuestFile(instanceId, dataWriteFd, dataPayload)
        } else {
            -1
        }
        if (dataWriteFd > 0) VmNativeBridge.closeGuestFile(instanceId, dataWriteFd)
        val dataReadFd = VmNativeBridge.openGuestPath(instanceId, dataPath, false)
        val dataRead = if (dataReadFd > 0) VmNativeBridge.readGuestFile(instanceId, dataReadFd, 1024) else ""
        if (dataReadFd > 0) VmNativeBridge.closeGuestFile(instanceId, dataReadFd)
        val dataOk = dataWriteFd > 0 && dataWriteBytes == dataPayload.length && dataRead == dataPayload
        passed = passed && dataOk
        Log.i(
            TAG,
            "STAGE4_FD_CASE passed=$dataOk op=write_data writeFd=$dataWriteFd readFd=$dataReadFd bytes=$dataWriteBytes",
        )

        val binderFd = VmNativeBridge.openGuestPath(instanceId, "/dev/binder", true)
        val binderWrite = if (binderFd > 0) VmNativeBridge.writeGuestFile(instanceId, binderFd, "PING") else -1
        if (binderFd > 0) VmNativeBridge.closeGuestFile(instanceId, binderFd)
        val binderOk = binderFd > 0 && binderWrite == 4
        passed = passed && binderOk
        Log.i(TAG, "STAGE4_FD_CASE passed=$binderOk op=virtual_device fd=$binderFd bytes=$binderWrite")

        val cpuFd = VmNativeBridge.openGuestPath(instanceId, "/proc/cpuinfo", false)
        val cpuInfo = if (cpuFd > 0) VmNativeBridge.readGuestFile(instanceId, cpuFd, 1024) else ""
        if (cpuFd > 0) VmNativeBridge.closeGuestFile(instanceId, cpuFd)
        val cpuOk = cpuFd > 0 && cpuInfo.contains("AndroidVirtualMachine")
        passed = passed && cpuOk
        Log.i(TAG, "STAGE4_FD_CASE passed=$cpuOk op=virtual_file fd=$cpuFd bytes=${cpuInfo.length}")

        val fdCount = VmNativeBridge.getOpenFdCount(instanceId)
        val fdCountOk = fdCount == 0
        passed = passed && fdCountOk
        Log.i(TAG, "STAGE4_FD_RESULT passed=$passed openFdCount=$fdCount")
        return passed
    }

    private fun runPropertyDiagnostics(instanceId: String): Boolean {
        val model = VmNativeBridge.getGuestProperty(instanceId, "ro.product.model", "")
        val modelOk = model == "VirtualPhoneDebug"
        val overrideResult = VmNativeBridge.setGuestPropertyOverride(instanceId, "persist.sys.language", "ko")
        val language = VmNativeBridge.getGuestProperty(instanceId, "persist.sys.language", "")
        val fallback = VmNativeBridge.getGuestProperty(instanceId, "does.not.exist", "fallback")
        val passed = modelOk && overrideResult == 0 && language == "ko" && fallback == "fallback"
        Log.i(
            TAG,
            "STAGE4_PROPERTY_RESULT passed=$passed model=$model language=$language fallback=$fallback",
        )
        return passed
    }

    private fun runGuestRuntimeDiagnostics(context: Context, instanceId: String, logPath: String): Boolean {
        val startResult = VmNativeBridge.startGuest(instanceId)
        Thread.sleep(250)
        val logText = runCatching { File(logPath).readText() }.getOrDefault("")
        val packageHandle = VmNativeBridge.getBinderServiceHandle(instanceId, "package")
        val activityHandle = VmNativeBridge.getBinderServiceHandle(instanceId, "activity")
        val bootstrapStatus = VmNativeBridge.getBootstrapStatus(instanceId)
        val binary = File(context.filesDir, "avm/instances/$instanceId/rootfs/system/bin/avm-hello")
        val binaryResult = if (binary.isFile) {
            PhaseBNativeBridge.runGuestBinary(
                instanceId = instanceId,
                binaryPath = binary.absolutePath,
                args = arrayOf("/system/bin/avm-hello"),
                timeoutMillis = 5_000,
            )
        } else {
            null
        }
        VmNativeBridge.stopGuest(instanceId)

        val guestProcessOk = logText.contains("guest runtime entrypoint reached")
        val syscallOk = logText.contains("syscall smoke ok")
        val binderOk = packageHandle > 0 && activityHandle > 0 && logText.contains("binder smoke registered core services")
        val bootstrapOk = bootstrapStatus.contains("zygote=main_loop") &&
            bootstrapStatus.contains("system_server=boot_completed") &&
            bootstrapStatus.contains("boot_completed=1")
        val binaryOk = binaryResult?.ok == true &&
            binaryResult.exitCode == 0 &&
            binaryResult.stdout.trim() == "hello"
        val passed = startResult == 0 && guestProcessOk && syscallOk && binderOk && bootstrapOk && binaryOk
        Log.i(
            TAG,
            "STAGE4_RUNTIME_RESULT passed=$passed start=$startResult guest=$guestProcessOk " +
                "binary=$binaryOk syscall=$syscallOk binder=$binderOk bootstrap=$bootstrapStatus",
        )
        return passed
    }

    private data class VfsCase(
        val guestPath: String,
        val writeAccess: Boolean,
        val expected: GuestPathStatus,
    )

    companion object {
        const val ACTION_RUN_STAGE4_DIAGNOSTICS = "dev.jongwoo.androidvm.debug.RUN_STAGE4_DIAGNOSTICS"
        private const val TAG = "AVM.Stage4Diag"
    }
}

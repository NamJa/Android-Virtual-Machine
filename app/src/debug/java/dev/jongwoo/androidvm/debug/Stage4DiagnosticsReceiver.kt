package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.vm.GuestPathStatus
import dev.jongwoo.androidvm.vm.VmNativeBridge

class Stage4DiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_STAGE4_DIAGNOSTICS) return

        val config = InstanceStore(context).ensureDefaultConfig()
        val snapshot = RomInstaller(context).snapshot(config.instanceId)
        if (!snapshot.isInstalled) {
            Log.e(TAG, "STAGE4_VFS_RESULT passed=false reason=${snapshot.imageState}")
            return
        }

        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(config.instanceId, config.toJson())

        val cases = listOf(
            VfsCase("/system/build.prop", writeAccess = false, expected = GuestPathStatus.OK),
            VfsCase("/data/local/tmp", writeAccess = true, expected = GuestPathStatus.OK),
            VfsCase("/system/build.prop", writeAccess = true, expected = GuestPathStatus.READ_ONLY),
            VfsCase("/system/../data", writeAccess = false, expected = GuestPathStatus.PATH_TRAVERSAL),
        )

        var passed = true
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
        Log.i(TAG, "STAGE4_VFS_RESULT passed=$passed cases=${cases.size}")
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

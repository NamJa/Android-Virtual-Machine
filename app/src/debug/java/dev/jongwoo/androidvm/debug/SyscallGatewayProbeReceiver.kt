package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jongwoo.androidvm.vm.SyscallGatewayProbe
import java.io.File

/**
 * EP2.3/EP2.6 spike — runs the seccomp SIGSYS gateway servicing probe on device.
 *
 *   adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_SYSCALL_GATEWAY_PROBE \
 *       -n dev.jongwoo.androidvm/.debug.SyscallGatewayProbeReceiver
 *   # add --ez vfs true for the EP2.6 openat servicing PoC
 *   adb logcat -s AVM.SyscallGateway
 */
class SyscallGatewayProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        if (intent.getBooleanExtra("vfs", false)) {
            val rootfs = File(context.cacheDir, "vfs-poc-rootfs").absolutePath
            Log.i(TAG, "SYSCALL_GATEWAY_PROBE vfs ${SyscallGatewayProbe.probeVfs(rootfs)}")
        } else {
            Log.i(TAG, "SYSCALL_GATEWAY_PROBE ${SyscallGatewayProbe.probe()}")
        }
    }

    companion object {
        const val TAG = "AVM.SyscallGateway"
        const val ACTION = "dev.jongwoo.androidvm.debug.RUN_SYSCALL_GATEWAY_PROBE"
    }
}

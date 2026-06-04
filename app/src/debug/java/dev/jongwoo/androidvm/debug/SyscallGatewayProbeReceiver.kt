package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jongwoo.androidvm.vm.SyscallGatewayProbe

/**
 * EP2.3 spike — runs the seccomp SIGSYS gateway servicing probe on the device.
 *
 *   adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_SYSCALL_GATEWAY_PROBE \
 *       -n dev.jongwoo.androidvm/.debug.SyscallGatewayProbeReceiver
 *   adb logcat -s AVM.SyscallGateway
 */
class SyscallGatewayProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Log.i(TAG, "SYSCALL_GATEWAY_PROBE ${SyscallGatewayProbe.probe()}")
    }

    companion object {
        const val TAG = "AVM.SyscallGateway"
        const val ACTION = "dev.jongwoo.androidvm.debug.RUN_SYSCALL_GATEWAY_PROBE"
    }
}

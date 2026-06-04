package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jongwoo.androidvm.vm.GuestExecProbe

/**
 * EP1 spike — runs the guest-execution capability probe on the device and logs
 * the measured capabilities. Drives the GUEST_ARCH_DECISION gate.
 *
 *   adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_GUEST_EXEC_PROBE \
 *       -n dev.jongwoo.androidvm/.debug.GuestExecProbeReceiver
 *   adb logcat -s AVM.GuestExecProbe
 */
class GuestExecProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val caps = GuestExecProbe.probe()
        Log.i(TAG, caps.format())
    }

    companion object {
        const val TAG = "AVM.GuestExecProbe"
        const val ACTION = "dev.jongwoo.androidvm.debug.RUN_GUEST_EXEC_PROBE"
    }
}

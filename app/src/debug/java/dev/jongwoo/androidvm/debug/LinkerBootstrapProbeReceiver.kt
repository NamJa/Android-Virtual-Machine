package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jongwoo.androidvm.vm.LinkerBootstrapProbe

/**
 * EP2.2 spike — runs the linker bootstrap mechanism probe on the device.
 *
 *   adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_LINKER_BOOTSTRAP_PROBE \
 *       -n dev.jongwoo.androidvm/.debug.LinkerBootstrapProbeReceiver
 *   # optional: --es exec /system/bin/app_process64 --es linker /system/bin/linker64
 *   adb logcat -s AVM.LinkerBootstrap
 */
class LinkerBootstrapProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val exec = intent.getStringExtra("exec") ?: "/system/bin/app_process64"
        val linker = intent.getStringExtra("linker") ?: "/system/bin/linker64"
        val gateway = intent.getBooleanExtra("gateway", false)
        val json = LinkerBootstrapProbe.probe(exec, linker, gateway)
        Log.i(TAG, "LINKER_BOOTSTRAP_PROBE exec=$exec linker=$linker gateway=$gateway $json")
    }

    companion object {
        const val TAG = "AVM.LinkerBootstrap"
        const val ACTION = "dev.jongwoo.androidvm.debug.RUN_LINKER_BOOTSTRAP_PROBE"
    }
}

package dev.jongwoo.androidvm.product

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jongwoo.androidvm.vm.AndroidProductGateSignalSource
import dev.jongwoo.androidvm.vm.ProductGateRunner

/**
 * EP0.2 — on-device trigger for the product gate, available only in the
 * release-equivalent `product` build variant (this source set is not compiled
 * into `release`). It is the product counterpart to the debug-only
 * Stage/Phase diagnostic receivers.
 *
 *   adb shell am broadcast -a dev.jongwoo.androidvm.product.RUN_PRODUCT_GATE \
 *       -n dev.jongwoo.androidvm/.product.ProductGateReceiver
 *   adb logcat -s AVM.ProductGate
 *
 * Emits the five PRODUCT_*_RESULT lines from real, measured signals
 * ([AndroidProductGateSignalSource]); no canned success path.
 */
class ProductGateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val result = ProductGateRunner(
            signals = AndroidProductGateSignalSource(),
            emit = { line -> Log.i(TAG, line) },
        ).run()
        Log.i(TAG, "PRODUCT_READINESS_RESULT passed=${result.passed}")
    }

    companion object {
        const val TAG = "AVM.ProductGate"
        const val ACTION = "dev.jongwoo.androidvm.product.RUN_PRODUCT_GATE"
    }
}

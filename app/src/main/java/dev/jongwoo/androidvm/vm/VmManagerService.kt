package dev.jongwoo.androidvm.vm

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

class VmManagerService : Service() {
    private val binder = LocalBinder()
    private var state: VmState = VmState.STOPPED

    override fun onBind(intent: Intent?): IBinder = binder

    fun updateState(next: VmState) {
        state = next
    }

    fun currentState(): VmState = state

    inner class LocalBinder : Binder() {
        fun service(): VmManagerService = this@VmManagerService
    }
}

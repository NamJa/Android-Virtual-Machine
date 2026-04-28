package dev.jongwoo.androidvm

import android.app.Application
import dev.jongwoo.androidvm.storage.PathLayout

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val paths = PathLayout(this)
        paths.ensureRoot()
        // Stage 07: per-instance bridge audit logs are owned by VmInstanceService through
        // BridgeAuditLog(instanceRoot). The legacy host-wide audit log was replaced in Step 7.4.
    }
}

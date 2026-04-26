package dev.jongwoo.androidvm

import android.app.Application
import dev.jongwoo.androidvm.bridge.BridgeAuditLog
import dev.jongwoo.androidvm.storage.PathLayout

class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val paths = PathLayout(this)
        paths.ensureRoot()
        BridgeAuditLog.install(paths.auditLogFile)
    }
}

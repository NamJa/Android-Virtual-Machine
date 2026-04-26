package dev.jongwoo.androidvm.bridge

import java.io.File
import java.time.Instant

object BridgeAuditLog {
    @Volatile
    private var logFile: File? = null

    fun install(file: File) {
        file.parentFile?.mkdirs()
        logFile = file
    }

    @Synchronized
    fun append(kind: BridgeKind, message: String) {
        val target = logFile ?: return
        target.appendText("${Instant.now()} ${kind.name} $message\n")
    }
}

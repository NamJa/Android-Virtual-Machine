package dev.jongwoo.androidvm.diag

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

enum class CrashKind {
    NATIVE_SIGSEGV,
    NATIVE_SIGABRT,
    NATIVE_SIGBUS,
    JAVA_EXCEPTION,
    ANR,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWireName(value: String): CrashKind? = entries.firstOrNull { it.wireName == value }
    }
}

data class CrashReport(
    val timestampMillis: Long,
    val kind: CrashKind,
    val process: String,
    val threadName: String,
    val summary: String,
    val stackPreviewLines: List<String>,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("timestamp", timestampMillis)
        .put("kind", kind.wireName)
        .put("process", process)
        .put("thread", threadName)
        .put("summary", summary)
        .put("stack", stackPreviewLines.joinToString("\n"))

    companion object {
        fun fromJson(text: String): CrashReport {
            val o = JSONObject(text)
            return CrashReport(
                timestampMillis = o.getLong("timestamp"),
                kind = CrashKind.fromWireName(o.getString("kind")) ?: CrashKind.JAVA_EXCEPTION,
                process = o.getString("process"),
                threadName = o.getString("thread"),
                summary = o.getString("summary"),
                stackPreviewLines = o.optString("stack", "").split("\n").filter { it.isNotBlank() },
            )
        }
    }
}

/**
 * Phase D.9 crash report persister. Writes one minidump-shaped JSON per crash under
 * `<instance>/logs/crashes/`, rotates to keep the most recent [maxRetained] reports, and never
 * blocks the calling thread on disk.
 */
class CrashReportStore(
    private val instancePaths: InstancePaths,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxRetained: Int = DEFAULT_MAX_RETAINED,
) {
    init {
        require(maxRetained > 0) { "maxRetained must be positive (was $maxRetained)" }
    }

    private val crashDir: File = File(instancePaths.logsDir, "crashes")

    @Synchronized
    fun record(report: CrashReport): File {
        crashDir.mkdirs()
        val name = formatFileName(report)
        val target = File(crashDir, name)
        target.writeText(report.toJson().toString(2))
        rotate()
        return target
    }

    /** Convenience helper for synchronous capture from native signal handlers. */
    @Synchronized
    fun recordNow(
        kind: CrashKind,
        process: String,
        threadName: String,
        summary: String,
        stackPreviewLines: List<String>,
    ): File = record(
        CrashReport(
            timestampMillis = clock(),
            kind = kind,
            process = process,
            threadName = threadName,
            summary = summary,
            stackPreviewLines = stackPreviewLines,
        ),
    )

    @Synchronized
    fun list(limit: Int = Int.MAX_VALUE): List<CrashReport> {
        if (!crashDir.exists()) return emptyList()
        val files = crashDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedBy { it.name }
        val window = if (files.size > limit) files.subList(files.size - limit, files.size) else files
        return window.mapNotNull { runCatching { CrashReport.fromJson(it.readText()) }.getOrNull() }
    }

    @Synchronized
    fun count(): Int = if (crashDir.exists()) {
        crashDir.listFiles().orEmpty().count { it.isFile && it.name.endsWith(".json") }
    } else 0

    private fun rotate() {
        val files = crashDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedBy { it.name }
        val excess = files.size - maxRetained
        if (excess > 0) {
            files.subList(0, excess).forEach { it.delete() }
        }
    }

    private fun formatFileName(report: CrashReport): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            this.timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(report.timestampMillis))
        val kind = report.kind.wireName
        return "$timestamp-$kind.json"
    }

    companion object {
        const val DEFAULT_MAX_RETAINED: Int = 50
    }
}

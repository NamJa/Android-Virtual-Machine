package dev.jongwoo.androidvm.diag

/**
 * EP0.5 / EP10.6 — scrubs obviously-sensitive substrings out of text artifacts
 * (logcat, guest logs, instance state) before they are written into a failure
 * bundle. Conservative and order-independent: it only masks high-signal patterns
 * and never drops whole lines, so the bundle stays useful for triage.
 */
object Redactor {
    private val EMAIL = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    // Host home directory, e.g. /Users/<name>/ or /home/<name>/ -> mask the user component.
    private val HOME_PATH = Regex("""(/Users/|/home/|/data/user/\d+/)[^/\s"]+""")
    // Long token-like runs (hex / base64-ish), >= 24 chars.
    private val TOKEN = Regex("""\b[A-Za-z0-9_\-]{24,}\b""")

    const val MASK = "[REDACTED]"

    fun redact(text: String): String =
        text
            .replace(EMAIL, MASK)
            .replace(HOME_PATH) { m -> m.groupValues[1] + MASK }
            .replace(TOKEN, MASK)
}

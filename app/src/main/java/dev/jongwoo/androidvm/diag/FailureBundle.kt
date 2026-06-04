package dev.jongwoo.androidvm.diag

import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * EP0.5 — assembles a failure-diagnosis bundle (logcat, guest log, instance
 * state JSON, screenshot, …) into a single ZIP with an integrity `manifest.json`.
 *
 * Text artifacts are run through [Redactor] before being written; binary
 * artifacts (e.g. a screenshot) pass through unredacted. The pure ZIP/manifest
 * assembly is host-agnostic so it can be unit-tested without a device; the
 * Android adapter that actually gathers logcat/screenshots feeds entries in.
 */
class FailureBundle {
    private val entries = LinkedHashMap<String, ByteArray>()

    /** Adds a text artifact; its content is redacted before storage. */
    fun addText(name: String, content: String): FailureBundle = apply {
        entries[name] = Redactor.redact(content).toByteArray(Charsets.UTF_8)
    }

    /** Adds a binary artifact verbatim (no redaction). */
    fun addBinary(name: String, bytes: ByteArray): FailureBundle = apply {
        entries[name] = bytes.copyOf()
    }

    fun entryNames(): List<String> = entries.keys.toList()

    fun writeTo(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val manifest = JSONArray()
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                manifest.put(
                    JSONObject()
                        .put("name", name)
                        .put("bytes", bytes.size)
                        .put("sha256", sha256Hex(bytes)),
                )
            }
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                JSONObject().put("entries", manifest).toString(2).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
        }
    }

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

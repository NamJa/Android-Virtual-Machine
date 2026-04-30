package dev.jongwoo.androidvm.storage

import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject

/**
 * Phase D.9 lightweight per-instance backup. Bundles every directory the user can recreate from
 * application state — but NOT the rootfs (recoverable from the ROM image) and not the host
 * package install transactions (those are reproducible by re-running install).
 */
class InstanceBackup(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Top-level dirs / files the backup includes. */
    private val backupTargets: List<String> = listOf(
        "config",
        "runtime",
        "logs",
        "shared",
        "bridge-policy.json",
        "bridge-audit.jsonl",
    )

    fun export(instancePaths: InstancePaths, output: OutputStream): ExportResult {
        val sha = MessageDigest.getInstance("SHA-256")
        val digestStream = DigestingOutputStream(output, sha)
        var entryCount = 0
        var bytesWritten = 0L
        ZipOutputStream(digestStream).use { zip ->
            backupTargets.forEach { name ->
                val source = File(instancePaths.root, name)
                if (!source.exists()) return@forEach
                if (source.isDirectory) {
                    source.walkTopDown().forEach { f ->
                        if (f.isFile && !shouldExclude(f, instancePaths)) {
                            val rel = "${instancePaths.id}/${f.relativeTo(instancePaths.root).path.replace('\\', '/')}"
                            zip.putNextEntry(ZipEntry(rel))
                            val bytes = f.readBytes()
                            zip.write(bytes)
                            zip.closeEntry()
                            bytesWritten += bytes.size
                            entryCount++
                        }
                    }
                } else if (source.isFile) {
                    val rel = "${instancePaths.id}/$name"
                    zip.putNextEntry(ZipEntry(rel))
                    val bytes = source.readBytes()
                    zip.write(bytes)
                    zip.closeEntry()
                    bytesWritten += bytes.size
                    entryCount++
                }
            }
            // Manifest entry as the last record so the consumer can validate.
            val manifest = JSONObject()
                .put("instanceId", instancePaths.id)
                .put("createdAt", formatTimestamp(clock()))
                .put("entryCount", entryCount)
                .put("bytes", bytesWritten)
                .put("targets", backupTargets)
                .toString(2)
            zip.putNextEntry(ZipEntry("${instancePaths.id}/backup-manifest.json"))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
            entryCount++
        }
        return ExportResult(
            entryCount = entryCount,
            bytesWritten = bytesWritten,
            sha256 = sha.digest().toHex(),
        )
    }

    fun import(zipBytes: ByteArray, instancePaths: InstancePaths): ImportResult {
        var restored = 0
        ZipInputStream(zipBytes.inputStream()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val rel = entry.name
                val prefix = "${instancePaths.id}/"
                if (!rel.startsWith(prefix)) {
                    zin.closeEntry()
                    continue
                }
                val tail = rel.removePrefix(prefix)
                if (tail == "backup-manifest.json") {
                    zin.closeEntry()
                    continue
                }
                val target = File(instancePaths.root, tail).canonicalFile
                require(target.path.startsWith(instancePaths.root.canonicalPath + File.separator) ||
                    target.path == instancePaths.root.canonicalPath
                ) {
                    "Backup entry escaped instance root: ${entry.name}"
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { out -> zin.copyTo(out) }
                restored++
                zin.closeEntry()
            }
        }
        return ImportResult(restoredCount = restored)
    }

    private fun shouldExclude(file: File, instancePaths: InstancePaths): Boolean {
        // Exclude rootfs, staging transient files, and any *.tmp residue.
        val rootfsPath = instancePaths.rootfsDir.canonicalPath
        val stagingPath = instancePaths.stagingDir.canonicalPath
        val canonical = file.canonicalPath
        if (canonical.startsWith(rootfsPath)) return true
        if (canonical.startsWith(stagingPath)) return true
        if (file.name.endsWith(".tmp")) return true
        return false
    }

    private fun formatTimestamp(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    data class ExportResult(val entryCount: Int, val bytesWritten: Long, val sha256: String)
    data class ImportResult(val restoredCount: Int)

    private class DigestingOutputStream(
        private val downstream: OutputStream,
        private val digest: MessageDigest,
    ) : OutputStream() {
        override fun write(b: Int) {
            downstream.write(b)
            digest.update(b.toByte())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            downstream.write(b, off, len)
            digest.update(b, off, len)
        }

        override fun flush() = downstream.flush()
        override fun close() = downstream.close()
    }

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            builder.append(HEX[v ushr 4])
            builder.append(HEX[v and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

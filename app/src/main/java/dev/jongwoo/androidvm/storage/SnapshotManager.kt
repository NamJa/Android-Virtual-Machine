package dev.jongwoo.androidvm.storage

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Phase E.2 layered rootfs layout. The instance now has three logical roots:
 *
 *   - `rootfs.base/` — read-only contents from the installed ROM image.
 *   - `rootfs.overlay/` — user-writable layer; everything the guest writes lands here.
 *   - `snapshots/<id>/` — frozen copies of overlay state, plus matching metadata.
 *
 * [PathLayout] still exposes the legacy `rootfs/` directory for callers that have not been
 * migrated; this class layers on top of [InstancePaths] without renaming the existing field.
 */
data class LayeredRootfsPaths(
    val base: File,
    val overlay: File,
    val snapshotsDir: File,
) {
    fun ensure() {
        base.mkdirs(); overlay.mkdirs(); snapshotsDir.mkdirs()
    }

    companion object {
        fun forInstance(instancePaths: InstancePaths): LayeredRootfsPaths = LayeredRootfsPaths(
            base = File(instancePaths.root, "rootfs.base"),
            overlay = File(instancePaths.root, "rootfs.overlay"),
            snapshotsDir = File(instancePaths.root, "snapshots"),
        )
    }
}

/** Pure-JVM oracle for overlay/base lookup: read prefers overlay, then base, with whiteout. */
object LayeredPathResolver {
    enum class Source { OVERLAY, BASE, NOT_FOUND, WHITEOUT }

    fun resolveForRead(paths: LayeredRootfsPaths, relative: String): Source {
        val safeRelative = relative.removePrefix("/")
        val whiteout = File(paths.overlay, "$safeRelative$WHITEOUT_SUFFIX")
        if (whiteout.exists()) return Source.WHITEOUT
        if (File(paths.overlay, safeRelative).exists()) return Source.OVERLAY
        if (File(paths.base, safeRelative).exists()) return Source.BASE
        return Source.NOT_FOUND
    }

    /**
     * CoW: returns the overlay path the writer should target. Copies the base file into the
     * overlay if needed so the original base file is never mutated. Removes any whiteout for
     * this path because we are bringing the file back.
     */
    fun resolveForWrite(paths: LayeredRootfsPaths, relative: String): File {
        val safeRelative = relative.removePrefix("/")
        val overlayFile = File(paths.overlay, safeRelative)
        val whiteout = File(paths.overlay, "$safeRelative$WHITEOUT_SUFFIX")
        if (whiteout.exists()) whiteout.delete()
        if (!overlayFile.exists()) {
            val baseFile = File(paths.base, safeRelative)
            if (baseFile.isFile) {
                overlayFile.parentFile?.mkdirs()
                baseFile.copyTo(overlayFile, overwrite = true)
            } else {
                overlayFile.parentFile?.mkdirs()
            }
        }
        return overlayFile
    }

    /**
     * Marks the file as deleted by writing a whiteout tombstone alongside it. Subsequent reads
     * see [Source.WHITEOUT] until the file is rewritten.
     */
    fun markDeleted(paths: LayeredRootfsPaths, relative: String) {
        val safeRelative = relative.removePrefix("/")
        File(paths.overlay, safeRelative).delete()
        val whiteout = File(paths.overlay, "$safeRelative$WHITEOUT_SUFFIX")
        whiteout.parentFile?.mkdirs()
        whiteout.writeText("")
    }

    const val WHITEOUT_SUFFIX = ".avm-whiteout"
}

/** Frozen overlay state. */
data class SnapshotMetadata(
    val id: String,
    val createdAt: String,
    val byteCount: Long,
    val fileCount: Int,
    val description: String?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("createdAt", createdAt)
        .put("byteCount", byteCount)
        .put("fileCount", fileCount)
        .put("description", description ?: JSONObject.NULL)

    companion object {
        fun fromJson(text: String): SnapshotMetadata {
            val o = JSONObject(text)
            return SnapshotMetadata(
                id = o.getString("id"),
                createdAt = o.getString("createdAt"),
                byteCount = o.optLong("byteCount", 0L),
                fileCount = o.optInt("fileCount", 0),
                description = o.optString("description").takeIf {
                    it.isNotBlank() && !o.isNull("description")
                },
            )
        }
    }
}

/** Snapshot CRUD over the overlay. */
class SnapshotManager(
    private val paths: LayeredRootfsPaths,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun create(id: String, description: String? = null): SnapshotMetadata {
        require(id.matches(VALID_ID)) { "invalid snapshot id: $id" }
        paths.ensure()
        val target = File(paths.snapshotsDir, id)
        require(!target.exists()) { "snapshot already exists: $id" }
        target.mkdirs()
        var bytes = 0L
        var files = 0
        if (paths.overlay.exists()) {
            paths.overlay.walkTopDown().forEach { src ->
                if (src.isFile) {
                    val rel = src.relativeTo(paths.overlay).path
                    val dst = File(target, rel)
                    dst.parentFile?.mkdirs()
                    src.copyTo(dst, overwrite = true)
                    bytes += dst.length()
                    files += 1
                }
            }
        }
        val metadata = SnapshotMetadata(
            id = id,
            createdAt = formatTimestampUtc(clock()),
            byteCount = bytes,
            fileCount = files,
            description = description,
        )
        File(paths.snapshotsDir, "$id$METADATA_SUFFIX").writeText(metadata.toJson().toString(2))
        return metadata
    }

    fun rollback(id: String) {
        val source = File(paths.snapshotsDir, id)
        require(source.isDirectory) { "snapshot not found: $id" }
        // Wipe overlay then copy snapshot contents back.
        paths.overlay.deleteRecursively()
        paths.overlay.mkdirs()
        source.walkTopDown().forEach { src ->
            if (src.isFile) {
                val rel = src.relativeTo(source).path
                val dst = File(paths.overlay, rel)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }

    fun delete(id: String): Boolean {
        val dir = File(paths.snapshotsDir, id)
        val meta = File(paths.snapshotsDir, "$id$METADATA_SUFFIX")
        val deletedDir = !dir.exists() || dir.deleteRecursively()
        val deletedMeta = !meta.exists() || meta.delete()
        return deletedDir && deletedMeta
    }

    fun list(): List<SnapshotMetadata> {
        if (!paths.snapshotsDir.exists()) return emptyList()
        return paths.snapshotsDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(METADATA_SUFFIX) }
            .mapNotNull { runCatching { SnapshotMetadata.fromJson(it.readText()) }.getOrNull() }
            .sortedBy { it.createdAt }
    }

    private fun formatTimestampUtc(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    companion object {
        const val METADATA_SUFFIX = ".metadata.json"
        private val VALID_ID = Regex("[A-Za-z0-9_\\-]{1,64}")
    }
}

package dev.jongwoo.androidvm.vm

/**
 * EP2.6 — guest VFS path resolution. Maps an absolute guest path to a host path
 * inside the instance rootfs, with traversal defense, so the seccomp SIGSYS
 * gateway's `openat`/`faccessat`/`readlinkat` servicing can never escape the
 * rootfs. This is the spec the native handler mirrors when file-content servicing
 * lands (which additionally needs the IP-allow re-issue technique); encoding it
 * as a pure, JVM-tested oracle keeps the rewriting reviewable independently.
 *
 * `..` that would climb above the rootfs is rejected (returns null), as are
 * non-absolute paths. `.`/empty segments collapse. `/proc`, `/sys`, `/dev` map
 * under the rootfs like any other path; their synthetic overlays (EP2.7) live
 * there in a prepared guest image.
 */
object GuestPathRewrite {
    fun resolve(rootfs: String, guestPath: String): String? {
        if (!guestPath.startsWith("/")) return null
        val parts = ArrayDeque<String>()
        for (seg in guestPath.split("/")) {
            when (seg) {
                "", "." -> {}
                ".." -> {
                    if (parts.isEmpty()) return null // escape attempt
                    parts.removeLast()
                }
                else -> parts.addLast(seg)
            }
        }
        val base = rootfs.trimEnd('/')
        val rel = parts.joinToString("/")
        return if (rel.isEmpty()) base else "$base/$rel"
    }
}

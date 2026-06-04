package dev.jongwoo.androidvm.storage

import java.io.File

class RootfsHealthCheck {
    fun check(paths: InstancePaths): RootfsHealthResult = check(paths.rootfsDir, paths.imageManifestFile)

    fun check(rootfsDir: File, imageManifestFile: File? = null): RootfsHealthResult {
        val missing = requiredEntries
            .map { File(rootfsDir, it) }
            .filterNot { it.exists() }
            .map { it.relativeTo(rootfsDir).path }

        val writableFailures = writableEntries
            .map { File(rootfsDir, it) }
            .filterNot { it.exists() && it.isDirectory && it.canWrite() }
            .map { it.relativeTo(rootfsDir).path }

        // EP8.3 / guest-rom strategy: real-boot readiness is a *separate* signal
        // from basic structural health. The placeholder debug fixture is
        // structurally ok but not boot-ready (its binaries are shell scripts, not
        // real arm64 ELFs); a real AOSP rootfs is boot-ready. bootReady does NOT
        // affect `ok`, so existing structural diagnostics are unaffected.
        val bootReadyMissing = bootReadyEntries
            .filterNot { isArm64Elf(File(rootfsDir, it)) }

        val markerMissing = imageManifestFile != null && !imageManifestFile.exists()
        return RootfsHealthResult(
            rootfsPath = rootfsDir.absolutePath,
            missingRequiredEntries = missing,
            unwritableEntries = writableFailures,
            markerMissing = markerMissing,
            bootReadyMissing = bootReadyMissing,
        )
    }

    companion object {
        private val requiredEntries = listOf(
            "system/build.prop",
            "system/bin/app_process64",
            "system/bin/servicemanager",
            "system/bin/sh",
            "system/framework",
            "vendor",
            "data",
            "cache",
        )

        private val writableEntries = listOf("data", "cache")

        /** Real-boot prerequisites: these must be genuine arm64 ELFs, not placeholders. */
        private val bootReadyEntries = listOf(
            "system/bin/linker64",
            "system/lib64/libc.so",
            "system/bin/app_process64",
        )

        /** True iff [file] starts with an ELF64 header for EM_AARCH64 (0xB7). */
        fun isArm64Elf(file: File): Boolean {
            if (!file.isFile) return false
            return runCatching {
                file.inputStream().use { stream ->
                    val header = ByteArray(20)
                    if (stream.read(header) < 20) return false
                    val magicOk = header[0].toInt() and 0xff == 0x7f &&
                        header[1].toInt() == 'E'.code &&
                        header[2].toInt() == 'L'.code &&
                        header[3].toInt() == 'F'.code
                    val is64 = header[4].toInt() == 2 // ELFCLASS64
                    val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
                    magicOk && is64 && machine == 0xB7 // EM_AARCH64
                }
            }.getOrDefault(false)
        }
    }
}

data class RootfsHealthResult(
    val rootfsPath: String,
    val missingRequiredEntries: List<String>,
    val unwritableEntries: List<String>,
    val markerMissing: Boolean,
    /** Boot prerequisites (linker64/libc.so/app_process64) that are missing or not real arm64 ELFs. */
    val bootReadyMissing: List<String> = emptyList(),
) {
    val ok: Boolean
        get() = missingRequiredEntries.isEmpty() && unwritableEntries.isEmpty() && !markerMissing

    /** Structurally ok AND carries real arm64 ELF boot binaries (required for a real guest boot). */
    val bootReady: Boolean
        get() = ok && bootReadyMissing.isEmpty()

    val summary: String
        get() = when {
            ok -> "Rootfs is installed and healthy"
            missingRequiredEntries.isNotEmpty() -> "Missing: ${missingRequiredEntries.joinToString()}"
            unwritableEntries.isNotEmpty() -> "Not writable: ${unwritableEntries.joinToString()}"
            markerMissing -> "Image manifest marker is missing"
            else -> "Rootfs health is unknown"
        }

    val bootReadySummary: String
        get() = if (bootReady) {
            "Rootfs is boot-ready"
        } else {
            "Not boot-ready (missing/placeholder ELF: ${bootReadyMissing.joinToString()})"
        }
}

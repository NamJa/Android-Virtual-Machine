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

        val markerMissing = imageManifestFile != null && !imageManifestFile.exists()
        return RootfsHealthResult(
            rootfsPath = rootfsDir.absolutePath,
            missingRequiredEntries = missing,
            unwritableEntries = writableFailures,
            markerMissing = markerMissing,
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
    }
}

data class RootfsHealthResult(
    val rootfsPath: String,
    val missingRequiredEntries: List<String>,
    val unwritableEntries: List<String>,
    val markerMissing: Boolean,
) {
    val ok: Boolean
        get() = missingRequiredEntries.isEmpty() && unwritableEntries.isEmpty() && !markerMissing

    val summary: String
        get() = when {
            ok -> "Rootfs is installed and healthy"
            missingRequiredEntries.isNotEmpty() -> "Missing: ${missingRequiredEntries.joinToString()}"
            unwritableEntries.isNotEmpty() -> "Not writable: ${unwritableEntries.joinToString()}"
            markerMissing -> "Image manifest marker is missing"
            else -> "Rootfs health is unknown"
        }
}

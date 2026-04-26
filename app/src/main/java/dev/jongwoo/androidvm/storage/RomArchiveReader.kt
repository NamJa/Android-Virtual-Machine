package dev.jongwoo.androidvm.storage

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class RomArchiveReader(private val openAsset: (String) -> InputStream) {
    constructor(context: Context) : this({ assetPath -> context.assets.open(assetPath) })

    fun extract(
        candidate: RomImageCandidate,
        destinationRootfs: File,
        onProgress: (RomInstallProgress) -> Unit,
    ): RomArchiveExtractionResult {
        return when (candidate.manifest.format) {
            "zip" -> extractZip(candidate, destinationRootfs, onProgress)
            "tar.zst" -> RomArchiveExtractionResult.Unsupported("tar.zst extraction will be backed by native zstd/tar in the next slice")
            else -> RomArchiveExtractionResult.Unsupported("Unsupported image format: ${candidate.manifest.format}")
        }
    }

    private fun extractZip(
        candidate: RomImageCandidate,
        destinationRootfs: File,
        onProgress: (RomInstallProgress) -> Unit,
    ): RomArchiveExtractionResult {
        destinationRootfs.mkdirs()
        val canonicalDestination = destinationRootfs.canonicalFile
        openAsset(candidate.archiveAssetPath).use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val relative = normalizeEntryName(entry.name)
                    if (relative.isBlank()) {
                        zip.closeEntry()
                        continue
                    }

                    val target = File(destinationRootfs, relative).canonicalFile
                    if (!target.path.startsWith(canonicalDestination.path + File.separator)) {
                        return RomArchiveExtractionResult.Failed("Archive entry escapes rootfs: ${entry.name}")
                    }

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { output -> zip.copyTo(output) }
                    }
                    onProgress(RomInstallProgress(RomInstallPhase.EXTRACT, relative))
                    zip.closeEntry()
                }
            }
        }
        File(destinationRootfs, "data").mkdirs()
        File(destinationRootfs, "cache").mkdirs()
        return RomArchiveExtractionResult.Extracted
    }

    private fun normalizeEntryName(name: String): String {
        val unixName = name.replace('\\', '/').trimStart('/')
        return unixName.removePrefix("rootfs/").trim('/')
    }
}

sealed class RomArchiveExtractionResult {
    data object Extracted : RomArchiveExtractionResult()
    data class Unsupported(val reason: String) : RomArchiveExtractionResult()
    data class Failed(val reason: String) : RomArchiveExtractionResult()
}

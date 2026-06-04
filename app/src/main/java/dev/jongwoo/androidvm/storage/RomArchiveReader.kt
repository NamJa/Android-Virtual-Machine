package dev.jongwoo.androidvm.storage

import android.content.Context
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

class RomArchiveReader(
    private val openAsset: (String) -> InputStream,
    private val zstd: ZstdDecompressor = defaultZstdDecompressor(),
) {
    constructor(context: Context) : this({ assetPath -> context.assets.open(assetPath) })

    fun extract(
        candidate: RomImageCandidate,
        destinationRootfs: File,
        onProgress: (RomInstallProgress) -> Unit,
    ): RomArchiveExtractionResult {
        return when (candidate.manifest.format) {
            "zip" -> extractZip(candidate, destinationRootfs, onProgress)
            "tar.zst" -> extractTarZst(candidate, destinationRootfs, onProgress)
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

    private fun extractTarZst(
        candidate: RomImageCandidate,
        destinationRootfs: File,
        onProgress: (RomInstallProgress) -> Unit,
    ): RomArchiveExtractionResult {
        destinationRootfs.mkdirs()
        val canonicalDestination = destinationRootfs.canonicalFile
        fun within(target: File): Boolean =
            target.path == canonicalDestination.path ||
                target.path.startsWith(canonicalDestination.path + File.separator)

        // zstd-jni ships no Android-loadable native; if the decompressor cannot
        // initialize on this platform, fail gracefully instead of crashing. The
        // on-device zstd path is to be backed by NDK libzstd (EP8.3 follow-up).
        return try {
            extractTarZstInner(candidate, destinationRootfs, ::within, onProgress)
        } catch (t: IOException) {
            RomArchiveExtractionResult.Failed("tar.zst extraction failed: ${t.message}")
        } catch (t: UnsatisfiedLinkError) {
            RomArchiveExtractionResult.Failed("zstd native unavailable on this platform: ${t.message}")
        } catch (t: NoClassDefFoundError) {
            RomArchiveExtractionResult.Failed("zstd native unavailable on this platform: ${t.message}")
        } catch (t: ExceptionInInitializerError) {
            RomArchiveExtractionResult.Failed("zstd native unavailable on this platform: ${t.message}")
        }
    }

    private fun extractTarZstInner(
        candidate: RomImageCandidate,
        destinationRootfs: File,
        within: (File) -> Boolean,
        onProgress: (RomInstallProgress) -> Unit,
    ): RomArchiveExtractionResult {
        openAsset(candidate.archiveAssetPath).use { input ->
            zstd.decompress(input).use { decompressed ->
                TarArchiveInputStream(decompressed).use { tar ->
                    while (true) {
                        val entry = tar.nextTarEntry ?: break
                        val relative = normalizeEntryName(entry.name)
                        if (relative.isBlank()) continue

                        val target = File(destinationRootfs, relative).canonicalFile
                        if (!within(target)) {
                            return RomArchiveExtractionResult.Failed("Archive entry escapes rootfs: ${entry.name}")
                        }

                        when {
                            entry.isDirectory -> target.mkdirs()
                            entry.isSymbolicLink -> {
                                target.parentFile?.mkdirs()
                                if (target.exists() || Files.isSymbolicLink(target.toPath())) target.delete()
                                // The link target is guest-relative data resolved by the guest VFS,
                                // never followed on the host; store it verbatim.
                                runCatching {
                                    Files.createSymbolicLink(target.toPath(), File(entry.linkName).toPath())
                                }.getOrElse {
                                    return RomArchiveExtractionResult.Failed(
                                        "Symlink failed: ${entry.name} -> ${entry.linkName}",
                                    )
                                }
                            }
                            entry.isLink -> {
                                // Hard link: best-effort copy from an already-extracted source.
                                val source = File(destinationRootfs, normalizeEntryName(entry.linkName)).canonicalFile
                                if (within(source) && source.isFile) {
                                    target.parentFile?.mkdirs()
                                    source.copyTo(target, overwrite = true)
                                    applyMode(target, entry.mode)
                                }
                            }
                            else -> {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { output -> tar.copyTo(output) }
                                applyMode(target, entry.mode)
                            }
                        }
                        onProgress(RomInstallProgress(RomInstallPhase.EXTRACT, relative))
                    }
                }
            }
        }
        File(destinationRootfs, "data").mkdirs()
        File(destinationRootfs, "cache").mkdirs()
        return RomArchiveExtractionResult.Extracted
    }

    /** Applies the unix exec/read/write bits from a tar entry mode (e.g. 0755 for linker64). */
    private fun applyMode(file: File, mode: Int) {
        file.setReadable(true, false)
        if (mode and 0x49 != 0) file.setExecutable(true, false) // any of 0o111
        if (mode and 0x80 != 0) file.setWritable(true, false) // owner 0o200
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

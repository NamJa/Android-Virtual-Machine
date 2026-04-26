package dev.jongwoo.androidvm.storage

import android.content.Context
import java.io.File

class RomInstaller(private val context: Context) {
    fun bundledCandidates(): List<RomImageCandidate> {
        val names = runCatching { context.assets.list(ROM_ASSET_DIR)?.toList().orEmpty() }
            .getOrDefault(emptyList())
        return names
            .filter { it.endsWith(".tar.zst") || it.endsWith(".zip") || it.endsWith(".7z") }
            .map { RomImageCandidate(assetPath = "$ROM_ASSET_DIR/$it", name = it) }
    }

    fun installedImages(): List<File> {
        val romRoot = File(context.filesDir, "avm/roms")
        return romRoot.listFiles { file -> file.isFile && file.extension == "img" }?.toList().orEmpty()
    }

    companion object {
        private const val ROM_ASSET_DIR = "roms"
    }
}

data class RomImageCandidate(
    val assetPath: String,
    val name: String,
)

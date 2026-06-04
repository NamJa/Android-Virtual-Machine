package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jongwoo.androidvm.storage.NativeZstdDecompressor
import dev.jongwoo.androidvm.storage.RomArchiveReader
import dev.jongwoo.androidvm.storage.RomImageCandidate
import dev.jongwoo.androidvm.storage.RomImageManifest
import java.io.File
import java.nio.file.Files

/**
 * EP8.3 — verifies on-device tar.zst extraction backed by NDK libzstd. Extracts
 * the bundled debug fixture `guest/tarzst-probe.tar.zst` with the native zstd
 * decompressor and reports content / exec-bit / symlink fidelity.
 *
 *   adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_ROM_TARZST_PROBE \
 *       -n dev.jongwoo.androidvm/.debug.RomTarZstProbeReceiver
 *   adb logcat -s AVM.RomTarZst
 */
class RomTarZstProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val out = File(context.cacheDir, "tarzst-probe-out")
        out.deleteRecursively()

        val manifest = RomImageManifest(
            name = "tarzst-probe",
            guestVersion = "7.1.2",
            guestArch = "arm64",
            format = "tar.zst",
            compressedSize = 0,
            uncompressedSize = 0,
            sha256 = "",
            createdAt = "",
            minHostSdk = 26,
        )
        val candidate = RomImageCandidate(
            manifest = manifest,
            manifestAssetPath = "guest/tarzst-probe.manifest.json",
            archiveAssetPath = "guest/tarzst-probe.tar.zst",
            checksumAssetPath = "guest/tarzst-probe.sha256",
            archiveExists = true,
            checksumExists = true,
        )
        val reader = RomArchiveReader(
            openAsset = { context.assets.open(it) },
            zstd = NativeZstdDecompressor(context.cacheDir),
        )
        val result = reader.extract(candidate, out) {}
        val prop = File(out, "system/build.prop")
        val content = if (prop.isFile) prop.readText() else "<missing>"
        val linkerExec = File(out, "system/bin/linker64").canExecute()
        val symlink = Files.isSymbolicLink(File(out, "system/proplink").toPath())
        Log.i(
            TAG,
            "ROM_TARZST_PROBE result=$result build_prop=$content linker_exec=$linkerExec symlink=$symlink",
        )
    }

    companion object {
        const val TAG = "AVM.RomTarZst"
        const val ACTION = "dev.jongwoo.androidvm.debug.RUN_ROM_TARZST_PROBE"
    }
}

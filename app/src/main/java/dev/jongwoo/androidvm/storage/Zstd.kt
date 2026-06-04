package dev.jongwoo.androidvm.storage

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream

/** Wraps a compressed zstd stream into a decompressed stream. */
fun interface ZstdDecompressor {
    fun decompress(compressed: InputStream): InputStream
}

/**
 * EP8.3 — on-device zstd via NDK-built libzstd (`jni/zstd_bridge.cpp`), since
 * zstd-jni ships no Android-loadable native.
 */
object NativeZstd {
    @Volatile
    private var loaded = false

    fun available(): Boolean = try {
        ensureLoaded()
        true
    } catch (t: Throwable) {
        false
    }

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("avm_host")
            loaded = true
        }
    }

    /** Returns "" on success, else a libzstd error string. */
    fun decompressFile(srcPath: String, dstPath: String): String {
        ensureLoaded()
        return nativeDecompressFile(srcPath, dstPath)
    }

    @JvmStatic
    external fun nativeDecompressFile(srcPath: String, dstPath: String): String
}

/**
 * Production (Android): decompress to a temp .tar with NDK libzstd, then stream
 * that file to the tar reader. The temp file is deleted when the stream closes.
 */
class NativeZstdDecompressor(private val tempDir: File? = null) : ZstdDecompressor {
    override fun decompress(compressed: InputStream): InputStream {
        val zst = File.createTempFile("avm-rom", ".zst", tempDir)
        val tar = File.createTempFile("avm-rom", ".tar", tempDir)
        try {
            compressed.use { input -> zst.outputStream().use { input.copyTo(it) } }
            val err = NativeZstd.decompressFile(zst.absolutePath, tar.absolutePath)
            if (err.isNotEmpty()) {
                tar.delete()
                throw IOException("zstd decompress failed: $err")
            }
        } finally {
            zst.delete()
        }
        return object : FileInputStream(tar) {
            override fun close() {
                super.close()
                tar.delete()
            }
        }
    }
}

/** JVM / unit tests: zstd-jni (desktop native bundled in the jar). */
class ZstdJniDecompressor : ZstdDecompressor {
    override fun decompress(compressed: InputStream): InputStream =
        ZstdCompressorInputStream(compressed.buffered())
}

/** Native on device, zstd-jni fallback off-device (e.g. JVM unit tests). */
fun defaultZstdDecompressor(): ZstdDecompressor =
    if (NativeZstd.available()) NativeZstdDecompressor() else ZstdJniDecompressor()

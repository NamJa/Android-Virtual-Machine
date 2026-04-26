package dev.jongwoo.androidvm.storage

import android.content.Context
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object AssetVerifier {
    fun sha256(file: File): String {
        return file.inputStream().use { sha256(it) }
    }

    fun sha256Asset(context: Context, assetPath: String): String {
        return context.assets.open(assetPath).use { sha256(it) }
    }

    fun assetText(context: Context, assetPath: String): String {
        return context.assets.open(assetPath).bufferedReader().use { it.readText() }
    }

    fun assetExists(context: Context, assetPath: String): Boolean {
        return runCatching {
            context.assets.open(assetPath).close()
        }.isSuccess
    }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

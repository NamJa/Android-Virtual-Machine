package dev.jongwoo.androidvm.diag

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun masksEmailsHomePathsAndTokens() {
        val input = "user jongwoo.kim@placen.co.kr at /Users/jongwoo/secret/file " +
            "token=ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"
        val out = Redactor.redact(input)

        assertFalse(out.contains("jongwoo.kim@placen.co.kr"))
        assertFalse(out.contains("/Users/jongwoo/"))
        assertFalse(out.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"))
        // Path prefix is preserved so the bundle is still legible.
        assertTrue(out.contains("/Users/${Redactor.MASK}"))
    }
}

class FailureBundleTest {
    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                out[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun bundlesEntriesRedactsTextAndWritesManifest() {
        val baos = ByteArrayOutputStream()
        FailureBundle()
            .addText("logcat.txt", "boot ok for jongwoo.kim@placen.co.kr")
            .addBinary("screenshot.bin", byteArrayOf(1, 2, 3, 4))
            .writeTo(baos)

        val files = readZip(baos.toByteArray())

        // All artifacts plus manifest present.
        assertTrue(files.containsKey("logcat.txt"))
        assertTrue(files.containsKey("screenshot.bin"))
        assertTrue(files.containsKey("manifest.json"))

        // Text artifact was redacted.
        val logcat = String(files["logcat.txt"]!!, Charsets.UTF_8)
        assertFalse(logcat.contains("jongwoo.kim@placen.co.kr"))

        // Binary passed through verbatim.
        assertEquals(listOf<Byte>(1, 2, 3, 4), files["screenshot.bin"]!!.toList())

        // Manifest lists every artifact with a sha256 (manifest itself excluded).
        val manifest = JSONObject(String(files["manifest.json"]!!, Charsets.UTF_8))
        val entries = manifest.getJSONArray("entries")
        assertEquals(2, entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            assertTrue(e.getString("sha256").length == 64)
            assertTrue(e.getInt("bytes") >= 0)
        }
    }
}

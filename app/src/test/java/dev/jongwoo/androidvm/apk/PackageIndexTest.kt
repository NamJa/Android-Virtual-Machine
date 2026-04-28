package dev.jongwoo.androidvm.apk

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageIndexTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun load_returnsEmptyWhenFileMissing() {
        val dir = tempDir("pkg-index-empty")
        val index = PackageIndex(File(dir, "package-index.json"))

        val snapshot = index.load("vm1", "2026-01-01T00:00:00Z")

        assertEquals("vm1", snapshot.instanceId)
        assertEquals(0, snapshot.packages.size)
        assertEquals(PackageIndexSnapshot.SCHEMA_VERSION, snapshot.version)
    }

    @Test
    fun saveLoadRoundTrip_preservesPackages() {
        val dir = tempDir("pkg-index-rt")
        val file = File(dir, "package-index.json")
        val index = PackageIndex(file)

        val snapshot = PackageIndexSnapshot.empty("vm1", "2026-01-01T00:00:00Z")
            .upsert(samplePackage("com.example.alpha"), "2026-01-01T00:00:00Z")
            .upsert(samplePackage("com.example.beta", versionCode = 7), "2026-01-01T00:00:01Z")

        index.save(snapshot)
        assertTrue(file.exists())

        val reloaded = index.load("vm1", "ignored")
        assertEquals(2, reloaded.packages.size)
        assertEquals("com.example.alpha", reloaded.packages[0].packageName)
        assertEquals(7L, reloaded.find("com.example.beta")?.versionCode)
    }

    @Test
    fun upsert_replacesExistingEntry() {
        val snapshot = PackageIndexSnapshot.empty("vm1", "t0")
            .upsert(samplePackage("com.example.alpha", versionCode = 1), "t0")
            .upsert(samplePackage("com.example.alpha", versionCode = 2, label = "Alpha v2"), "t1")

        assertEquals(1, snapshot.packages.size)
        assertEquals(2L, snapshot.find("com.example.alpha")?.versionCode)
        assertEquals("Alpha v2", snapshot.find("com.example.alpha")?.label)
    }

    @Test
    fun remove_dropsEntry() {
        val snapshot = PackageIndexSnapshot.empty("vm1", "t0")
            .upsert(samplePackage("com.example.alpha"), "t0")
            .upsert(samplePackage("com.example.beta"), "t0")
            .remove("com.example.alpha", "t1")

        assertEquals(1, snapshot.packages.size)
        assertNull(snapshot.find("com.example.alpha"))
        assertNotNull(snapshot.find("com.example.beta"))
    }

    @Test
    fun save_writesAtomicallyAndOverwrites() {
        val dir = tempDir("pkg-index-atomic")
        val file = File(dir, "package-index.json")
        val index = PackageIndex(file)

        index.save(PackageIndexSnapshot.empty("vm1", "t0").upsert(samplePackage("com.example.alpha"), "t0"))
        index.save(PackageIndexSnapshot.empty("vm1", "t1").upsert(samplePackage("com.example.beta"), "t1"))

        val text = file.readText()
        val parsed = JSONObject(text)
        assertEquals("t1", parsed.getString("updatedAt"))
        assertEquals(1, parsed.getJSONArray("packages").length())
        assertEquals(
            "com.example.beta",
            parsed.getJSONArray("packages").getJSONObject(0).getString("packageName"),
        )

        val tmp = File(dir, "package-index.json.tmp")
        assertTrue(!tmp.exists())
    }

    private fun samplePackage(
        packageName: String,
        versionCode: Long = 1L,
        label: String = packageName,
    ): GuestPackageInfo = GuestPackageInfo(
        packageName = packageName,
        label = label,
        versionCode = versionCode,
        versionName = "1.0",
        installedPath = "/data/app/$packageName/base.apk",
        dataPath = "/data/data/$packageName",
        sha256 = "deadbeef",
        sourceName = "$packageName.apk",
        installedAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        enabled = true,
        launchable = true,
        launcherActivity = "$packageName.MainActivity",
        nativeAbis = listOf("arm64-v8a"),
    )

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

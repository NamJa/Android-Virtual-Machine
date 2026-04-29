package dev.jongwoo.androidvm.vm

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM regression for the persistence helper that backs [VmManagerService]. The Service class
 * itself is exercised manually on-device because it owns Android lifecycle methods; here we lock
 * down the contract that those methods rely on.
 */
class VmManagerServiceTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun saveAndLoadRoundTripsTheSameStateMap() {
        val store = freshStore("roundtrip")
        val expected = mapOf(
            "vm1" to VmState.RUNNING,
            "vm-test" to VmState.STOPPING,
        )
        store.save(expected)
        assertEquals(expected, store.load())
    }

    @Test
    fun missingFileLoadsAsEmptyMap() {
        val store = freshStore("missing")
        assertEquals(emptyMap<String, VmState>(), store.load())
    }

    @Test
    fun corruptJsonRecoversToEmptyMapAndBacksUpFile() {
        val dir = tempDir("corrupt")
        val file = File(dir, VmRuntimeStateStore.FILE_NAME).apply {
            writeText("{not json")
        }
        val store = VmRuntimeStateStore(file)
        assertEquals(emptyMap<String, VmState>(), store.load())
        // The corrupted source should be moved out of the way so the next save is unambiguous.
        assertTrue("expected backup file beside ${file.parentFile}", dir.listFiles()
            ?.any { it.name.startsWith("runtime-state.bak.") } == true)
        store.save(mapOf("vm1" to VmState.RUNNING))
        assertEquals(mapOf("vm1" to VmState.RUNNING), VmRuntimeStateStore(file).load())
    }

    @Test
    fun unknownStateNamesAreIgnoredButOtherEntriesSurvive() {
        val dir = tempDir("partial")
        val file = File(dir, VmRuntimeStateStore.FILE_NAME)
        file.writeText(
            """
            { "version": 1, "instances": {
                "vm1": { "state": "RUNNING", "lastChangedMillis": 1 },
                "vm-bogus": { "state": "TIME_TRAVEL", "lastChangedMillis": 2 }
            } }
            """.trimIndent(),
        )
        assertEquals(mapOf("vm1" to VmState.RUNNING), VmRuntimeStateStore(file).load())
    }

    @Test
    fun unsupportedVersionIsTreatedAsCorrupt() {
        val dir = tempDir("future-version")
        val file = File(dir, VmRuntimeStateStore.FILE_NAME)
        file.writeText("""{ "version": 99, "instances": { "vm1": { "state": "RUNNING" } } }""")
        assertEquals(emptyMap<String, VmState>(), VmRuntimeStateStore(file).load())
        assertTrue(dir.listFiles()?.any { it.name.startsWith("runtime-state.bak.") } == true)
    }

    @Test
    fun atomicWriteSurvivesPartialFile() {
        val dir = tempDir("atomic")
        val file = File(dir, VmRuntimeStateStore.FILE_NAME)
        // Drop a half-written `.tmp` next to where the store will write — the next save must
        // still produce a fully formed runtime-state.json without merging the stray bytes.
        File(dir, "${VmRuntimeStateStore.FILE_NAME}.tmp").writeText("{ \"truncated\":")
        val store = VmRuntimeStateStore(file)
        store.save(mapOf("vm1" to VmState.RUNNING))
        assertEquals(mapOf("vm1" to VmState.RUNNING), VmRuntimeStateStore(file).load())
    }

    @Test
    fun concurrentSavesNeverProduceCorruptFile() {
        val store = freshStore("concurrent")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 64).map { i ->
                executor.submit {
                    val payload = mapOf(
                        "vm1" to if (i % 2 == 0) VmState.RUNNING else VmState.STOPPED,
                        "vm-test" to VmState.STARTING,
                    )
                    store.save(payload)
                }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
        // Whichever writer won, the file is still parseable and contains both keys.
        val loaded = store.load()
        assertEquals(setOf("vm1", "vm-test"), loaded.keys)
        assertNotEquals(VmState.ERROR, loaded["vm-test"])
    }

    @Test
    fun multipleInstancesAreIsolated() {
        val store = freshStore("isolated")
        store.save(
            mapOf(
                "vm1" to VmState.RUNNING,
                "vm-test" to VmState.ERROR,
            ),
        )
        val loaded = store.load()
        assertEquals(VmState.RUNNING, loaded["vm1"])
        assertEquals(VmState.ERROR, loaded["vm-test"])
        // Saving a different shape must replace cleanly without leaking stale keys.
        store.save(mapOf("vm-test" to VmState.STOPPED))
        val replaced = store.load()
        assertEquals(setOf("vm-test"), replaced.keys)
        assertNull(replaced["vm1"])
    }

    private fun freshStore(prefix: String): VmRuntimeStateStore {
        val dir = tempDir(prefix)
        return VmRuntimeStateStore(File(dir, VmRuntimeStateStore.FILE_NAME))
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory("vm-manager-$prefix").toFile().also { tempDirs += it }
}

package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeAuditLogTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun appendAndReadRoundTripsDecision() {
        val log = BridgeAuditLog(tempDir("audit-roundtrip"), clock = { 1_700_000_000_000 })

        log.appendDecision(
            instanceId = "vm1",
            bridge = BridgeType.LOCATION,
            operation = "request_current_location",
            decision = BridgeDecision.unavailable("bridge_disabled"),
        )

        val entries = log.read()
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("vm1", entry.instanceId)
        assertEquals(BridgeType.LOCATION, entry.bridge)
        assertEquals(BridgeResult.UNAVAILABLE, entry.result)
        assertEquals("bridge_disabled", entry.reason)
        assertEquals(false, entry.allowed)
        assertEquals(1_700_000_000_000, entry.timeMillis)
    }

    @Test
    fun policyChangeIsLogged() {
        val log = BridgeAuditLog(tempDir("audit-policy"))

        log.appendPolicyChange("vm1", BridgeType.CLIPBOARD, BridgeMode.CLIPBOARD_BIDIRECTIONAL, true)

        val entry = log.read().single()
        assertEquals("policy_change", entry.operation)
        assertEquals(BridgeResult.ALLOWED, entry.result)
        assertTrue(entry.reason.contains("clipboard_bidirectional"))
        assertTrue(entry.reason.contains("enabled=true"))
    }

    @Test
    fun auditLogDoesNotPersistSensitivePayload() {
        val root = tempDir("audit-redaction")
        val log = BridgeAuditLog(root)

        log.appendDecision(
            instanceId = "vm1",
            bridge = BridgeType.CLIPBOARD,
            operation = "guest_to_host",
            decision = BridgeDecision.denied("clipboard_too_large"),
        )

        val rawLog = File(root, BridgeAuditLog.LOG_FILE_NAME).readText()
        assertFalse(rawLog.contains("secret clipboard text"))
        assertFalse(rawLog.contains("37.5665"))
        assertTrue(rawLog.contains("clipboard_too_large"))
    }

    @Test
    fun rotationKeepsOnlyMostRecentEntries() {
        val log = BridgeAuditLog(tempDir("audit-rotation"), maxEntries = 3)

        repeat(7) { i ->
            log.appendDecision(
                "vm1",
                BridgeType.AUDIO_OUTPUT,
                "write_pcm_$i",
                BridgeDecision.allowed("written_$i"),
            )
        }

        val entries = log.read()
        assertEquals(3, entries.size)
        assertEquals("write_pcm_4", entries[0].operation)
        assertEquals("write_pcm_5", entries[1].operation)
        assertEquals("write_pcm_6", entries[2].operation)
        assertEquals(3, log.count())
    }

    @Test
    fun clearEmptiesLogButPreservesFile() {
        val root = tempDir("audit-clear")
        val log = BridgeAuditLog(root)
        log.appendDecision("vm1", BridgeType.AUDIO_OUTPUT, "write", BridgeDecision.allowed("ok"))

        log.clear()

        assertEquals(0, log.count())
        assertEquals(emptyList<BridgeAuditEntry>(), log.read())
        assertTrue(File(root, BridgeAuditLog.LOG_FILE_NAME).exists())
    }

    @Test
    fun differentInstancesUseSeparateLogFiles() {
        val a = BridgeAuditLog(tempDir("audit-a"))
        val b = BridgeAuditLog(tempDir("audit-b"))

        a.appendDecision("vmA", BridgeType.NETWORK, "open", BridgeDecision.allowed("ok"))

        assertEquals(1, a.count())
        assertEquals(0, b.count())
        assertNotEquals(a.logFile.canonicalPath, b.logFile.canonicalPath)
    }

    @Test
    fun readLimitReturnsTail() {
        val log = BridgeAuditLog(tempDir("audit-limit"))

        repeat(5) { i ->
            log.appendDecision("vm1", BridgeType.AUDIO_OUTPUT, "op_$i", BridgeDecision.allowed("ok"))
        }

        val tail = log.read(limit = 2)
        assertEquals(listOf("op_3", "op_4"), tail.map { it.operation })
    }

    @Test
    fun readEmptyLogReturnsEmptyList() {
        val log = BridgeAuditLog(tempDir("audit-empty"))
        assertEquals(emptyList<BridgeAuditEntry>(), log.read())
        assertEquals(0, log.count())
    }

    @Test
    fun corruptedLineIsSkippedDuringRead() {
        val root = tempDir("audit-corrupt-line")
        val log = BridgeAuditLog(root)
        log.appendDecision("vm1", BridgeType.AUDIO_OUTPUT, "ok", BridgeDecision.allowed("ok"))
        File(root, BridgeAuditLog.LOG_FILE_NAME).appendText("not-json\n")
        log.appendDecision("vm1", BridgeType.AUDIO_OUTPUT, "ok2", BridgeDecision.allowed("ok"))

        val entries = log.read()
        assertEquals(2, entries.size)
        assertEquals(listOf("ok", "ok2"), entries.map { it.operation })
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

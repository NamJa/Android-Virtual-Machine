package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the JSON wire contract that `phase_b_bridge.cpp` produces and that
 * [GuestExecutionResult.fromJson] consumes. The C++ side and Kotlin side both have to be
 * updated together when a new field is added.
 */
class GuestExecutionResultTest {
    @Test
    fun parsesSuccessfulParseElf64Result() {
        val json = """
            { "ok": true, "type": 3, "machine": 183, "entry": 4660,
              "phoff": 64, "phnum": 2, "interp": "/system/bin/linker64" }
        """.trimIndent()
        val r = GuestExecutionResult.fromJson(json)
        assertTrue(r.ok)
        assertEquals("", r.reason)
        assertEquals(3, r.type)
        assertEquals(183, r.machine)
        assertEquals(4660L, r.entry)
        assertEquals("/system/bin/linker64", r.interp)
    }

    @Test
    fun parsesFailureWithPhaseAndReason() {
        val json = """{ "ok": false, "phase": 1, "reason": "execmem_denied" }"""
        val r = GuestExecutionResult.fromJson(json)
        assertFalse(r.ok)
        assertEquals(1, r.phase)
        assertEquals("execmem_denied", r.reason)
    }

    @Test
    fun corruptJsonFallsBackToStructuredFailure() {
        val r = GuestExecutionResult.fromJson("{not json")
        assertFalse(r.ok)
        assertEquals("result_json_unparseable", r.reason)
        assertEquals(-1, r.phase)
    }

    @Test
    fun missingFieldsFallToZeroDefaults() {
        val r = GuestExecutionResult.fromJson("""{"ok": false}""")
        assertFalse(r.ok)
        assertEquals(-1, r.phase)
        assertEquals(0, r.type)
        assertEquals("", r.interp)
    }

    @Test
    fun phaseBGuestRunResultRoundTripsExactly() {
        // The exact shape produced by `phase_b_bridge.cpp::nativeRunGuestBinary` when the
        // Phase B avm-hello PIE maps, executes, and reports captured stdout.
        val json = """
            { "ok": true, "reason": "ok", "phase": 3,
              "interp": "/system/bin/linker64", "phnum": 8, "instance": "vm1",
              "binary": "/system/bin/avm-hello", "exit_code": 0, "stdout": "hello\n",
              "libc_init": true, "syscall_round_trip": true }
        """.trimIndent()
        val r = GuestExecutionResult.fromJson(json)
        assertTrue(r.ok)
        assertEquals("ok", r.reason)
        assertEquals(3, r.phase)
        assertEquals(8, r.phnum)
        assertEquals("vm1", r.instance)
        assertEquals("/system/bin/avm-hello", r.binary)
        assertEquals(0, r.exitCode)
        assertEquals("hello\n", r.stdout)
        assertTrue(r.libcInit)
        assertTrue(r.syscallRoundTrip)
    }
}

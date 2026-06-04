package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestExecCapabilitiesTest {
    @Test
    fun parsesAllFields() {
        val caps = GuestExecCapabilities.fromJson(
            """{"arch":"arm64-v8a","prot_exec_mmap":true,"memfd_exec":false,
               "seccomp_trap":true,"ptrace_child":false,"clone_thread":true}""",
        )
        assertEquals("arm64-v8a", caps.arch)
        assertTrue(caps.protExecMmap)
        assertFalse(caps.memfdExec)
        assertTrue(caps.seccompTrap)
        assertFalse(caps.ptraceChild)
        assertTrue(caps.cloneThread)
    }

    @Test
    fun optionBViableWhenSeccompPlusAnyExecMapping() {
        val viaProt = GuestExecCapabilities("x86_64", protExecMmap = true, memfdExec = false, seccompTrap = true, ptraceChild = false, cloneThread = true)
        val viaMemfd = viaProt.copy(protExecMmap = false, memfdExec = true)
        assertTrue(viaProt.optionBViable)
        assertTrue(viaMemfd.optionBViable)
    }

    @Test
    fun optionBNotViableWithoutSeccompOrExecMapping() {
        val noSeccomp = GuestExecCapabilities("arm64-v8a", protExecMmap = true, memfdExec = true, seccompTrap = false, ptraceChild = true, cloneThread = true)
        val noExec = GuestExecCapabilities("arm64-v8a", protExecMmap = false, memfdExec = false, seccompTrap = true, ptraceChild = true, cloneThread = true)
        assertFalse(noSeccomp.optionBViable)
        assertFalse(noExec.optionBViable)
        // No exec mapping kills B' too.
        assertFalse(noExec.optionBPrimeViable)
    }

    @Test
    fun unparseableFailsClosed() {
        val caps = GuestExecCapabilities.fromJson("not json")
        assertEquals("unparseable", caps.arch)
        assertFalse(caps.optionBViable)
        assertFalse(caps.optionBPrimeViable)
    }

    @Test
    fun formatCarriesDerivedVerdicts() {
        val caps = GuestExecCapabilities("arm64-v8a", protExecMmap = false, memfdExec = true, seccompTrap = true, ptraceChild = false, cloneThread = true)
        val line = caps.format()
        assertTrue(line.contains("GUEST_EXEC_PROBE"))
        assertTrue(line.contains("option_b_viable=true"))
        assertTrue(line.contains("option_b_prime_viable=false"))
    }
}

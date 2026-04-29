package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyscallTrapDecisionTest {
    @Test
    fun seccompPreferredWhenAvailable() {
        val r = SyscallTrapDecision.decide(
            SyscallTrapInputs(seccompSigsysAvailable = true, libcPatchPossible = true),
        )
        assertEquals(TrapMechanism.SECCOMP_SIGSYS, r)
    }

    @Test
    fun fallsBackToLibcPatchWhenSeccompUnavailable() {
        val r = SyscallTrapDecision.decide(
            SyscallTrapInputs(seccompSigsysAvailable = false, libcPatchPossible = true),
        )
        assertEquals(TrapMechanism.LIBC_PATCH, r)
    }

    @Test
    fun reportsUnavailableWhenNeitherMechanismCanRun() {
        val r = SyscallTrapDecision.decide(
            SyscallTrapInputs(seccompSigsysAvailable = false, libcPatchPossible = false),
        )
        assertEquals(TrapMechanism.UNAVAILABLE, r)
    }

    @Test
    fun mvpTableIncludesAtLeast25Syscalls() {
        // The Phase B doc requires "약 25 개"; the Kotlin twin lists the exact set the
        // native dispatcher registers. Anything below 25 is a contract regression.
        assertTrue(
            "MVP_TABLE must list at least 25 syscalls (had ${SyscallNumbers.MVP_TABLE.size})",
            SyscallNumbers.MVP_TABLE.size >= 25,
        )
        assertTrue(
            "MVP_TABLE entries must be unique",
            SyscallNumbers.MVP_TABLE.toSet().size == SyscallNumbers.MVP_TABLE.size,
        )
    }

    @Test
    fun keySyscallNumbersMatchAsmGenericUnistd() {
        // These are the numbers in `<asm-generic/unistd.h>`. Lock them down — the C++ side
        // mirrors the same constants in `app/src/main/cpp/syscall/syscall_dispatch.h`.
        assertEquals(56, SyscallNumbers.OPENAT)
        assertEquals(63, SyscallNumbers.READ)
        assertEquals(64, SyscallNumbers.WRITE)
        assertEquals(94, SyscallNumbers.EXIT_GROUP)
        assertEquals(98, SyscallNumbers.FUTEX)
        assertEquals(113, SyscallNumbers.CLOCK_GETTIME)
        assertEquals(131, SyscallNumbers.TGKILL)
        assertEquals(172, SyscallNumbers.GETPID)
        assertEquals(178, SyscallNumbers.GETTID)
        assertEquals(214, SyscallNumbers.BRK)
        assertEquals(222, SyscallNumbers.MMAP)
        assertEquals(226, SyscallNumbers.MPROTECT)
    }

    @Test
    fun trapMechanismWireValuesMatchNativeEnum() {
        // The native `enum class TrapMechanism` uses these exact integers.
        assertEquals(0, TrapMechanism.UNAVAILABLE.wireValue)
        assertEquals(1, TrapMechanism.LIBC_PATCH.wireValue)
        assertEquals(2, TrapMechanism.SECCOMP_SIGSYS.wireValue)
    }
}

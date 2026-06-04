package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.vm.GuestSyscallDisposition.ALLOW
import dev.jongwoo.androidvm.vm.GuestSyscallDisposition.DENY
import dev.jongwoo.androidvm.vm.GuestSyscallDisposition.HOST_SERVICED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSyscallPolicyTest {
    @Test
    fun memoryAndThreadSyscallsRunInGuest() {
        listOf("mmap", "mprotect", "munmap", "futex", "clone", "rt_sigaction", "clock_gettime")
            .forEach { assertEquals(it, ALLOW, GuestSyscallPolicy.dispositionOf(it)) }
    }

    @Test
    fun pathAndIpcSyscallsAreHostServiced() {
        listOf("openat", "readlinkat", "newfstatat", "ioctl", "connect", "uname")
            .forEach { assertEquals(it, HOST_SERVICED, GuestSyscallPolicy.dispositionOf(it)) }
    }

    @Test
    fun dangerousSyscallsAreDenied() {
        listOf("ptrace", "mount", "reboot", "setuid", "init_module", "clock_settime")
            .forEach { assertEquals(it, DENY, GuestSyscallPolicy.dispositionOf(it)) }
    }

    @Test
    fun unknownSyscallFailsClosed() {
        assertEquals(DENY, GuestSyscallPolicy.dispositionOf("totally_made_up_syscall"))
    }

    @Test
    fun allowAndTrapListsPartitionWithoutOverlap() {
        val allow = GuestSyscallPolicy.allowList()
        val trap = GuestSyscallPolicy.trapList()
        assertTrue(allow.isNotEmpty())
        assertTrue(trap.isNotEmpty())
        // A syscall is either kernel-direct (ALLOW) or trapped, never both.
        assertTrue(allow.intersect(trap).isEmpty())
        // The BPF filter only lets the ALLOW set through.
        assertFalse(allow.contains("openat"))
        assertTrue(trap.contains("openat"))
    }
}

package dev.jongwoo.androidvm.vm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZygoteLayoutTest {
    @Test
    fun primarySocketIsUnderInstanceRuntime() {
        val runtime = File("/tmp/instance/runtime")
        val sock = ZygoteLayout.primarySocketPath(runtime)
        assertEquals(File(runtime, "sockets/zygote"), sock)
    }

    @Test
    fun secondarySocketIsDistinct() {
        val runtime = File("/tmp/instance/runtime")
        val a = ZygoteLayout.primarySocketPath(runtime).path
        val b = ZygoteLayout.secondarySocketPath(runtime).path
        assertNotEquals(a, b)
        assertTrue(b.endsWith("zygote_secondary"))
    }

    @Test
    fun guestPathsUseDevSocketPrefix() {
        assertEquals("/dev/socket/zygote", ZygoteLayout.guestSocketAddress("zygote"))
        assertEquals(
            "/dev/socket/zygote_secondary",
            ZygoteLayout.guestSocketAddress("zygote_secondary"),
        )
    }
}

class ArtRuntimeChainTest {
    @Test
    fun expectedCountIsAtLeastEleven() {
        // Phase C.4's gate: libs_loaded >= 11.
        assertTrue(ArtRuntimeChain.EXPECTED_COUNT >= 11)
    }

    @Test
    fun chainIncludesAllDocumentedLibs() {
        val expected = setOf(
            "libart.so", "libnativehelper.so", "libnativebridge.so",
            "libbase.so", "libcutils.so", "libutils.so",
            "libbinder.so", "libui.so", "libgui.so", "libsigchain.so",
        )
        assertTrue(
            "PRIMARY_LIBS missing entries from doc § C.4.b",
            ArtRuntimeChain.PRIMARY_LIBS.toSet().containsAll(expected),
        )
    }

    @Test
    fun zygoteDlopenChainOmitsAlreadyLoaded() {
        // libdl and libc are loaded by the linker itself — they aren't in the zygote chain.
        assertEquals(false, ArtRuntimeChain.ZYGOTE_DLOPEN_CHAIN.contains("libdl.so"))
    }
}

class CloneFlagsTest {
    @Test
    fun simpleThreadIsAccepted() {
        val r = CloneFlags.decide(CloneFlags.CLONE_VM or CloneFlags.CLONE_THREAD)
        assertEquals(CloneFlags.Decision.ALLOWED_THREAD, r)
    }

    @Test
    fun fullPosixThreadFlagsAreAccepted() {
        val flags = CloneFlags.CLONE_VM or CloneFlags.CLONE_THREAD or
            CloneFlags.CLONE_SIGHAND or CloneFlags.CLONE_FILES or CloneFlags.CLONE_FS or
            CloneFlags.CLONE_SYSVSEM or CloneFlags.CLONE_SETTLS or
            CloneFlags.CLONE_PARENT_SETTID or CloneFlags.CLONE_CHILD_CLEARTID
        assertEquals(CloneFlags.Decision.ALLOWED_THREAD, CloneFlags.decide(flags))
    }

    @Test
    fun pidNamespaceIsRejected() {
        val r = CloneFlags.decide(CloneFlags.CLONE_NEWPID)
        assertEquals(CloneFlags.Decision.REJECTED_PROCESS_NAMESPACE, r)
    }

    @Test
    fun unknownFlagsAreRejected() {
        val r = CloneFlags.decide(0x80000000L or CloneFlags.CLONE_VM)
        assertEquals(CloneFlags.Decision.REJECTED_UNKNOWN, r)
    }

    @Test
    fun cloneFlagConstantsMatchKernelHeaders() {
        // Lock the kernel `<linux/sched.h>` numeric constants.
        assertEquals(0x00000100L, CloneFlags.CLONE_VM)
        assertEquals(0x00000800L, CloneFlags.CLONE_SIGHAND)
        assertEquals(0x00010000L, CloneFlags.CLONE_THREAD)
        assertEquals(0x20000000L, CloneFlags.CLONE_NEWPID)
    }
}

class SocketSyscallNumbersTest {
    @Test
    fun afUnixIsTheOnlyFamilyPhaseCAccepts() {
        // The C++ side rejects everything else with -EAFNOSUPPORT.
        assertEquals(1, SocketSyscallNumbers.AF_UNIX)
        assertEquals(1, SocketSyscallNumbers.SOCK_STREAM)
    }

    @Test
    fun syscallNumbersMatchAsmGenericUnistd() {
        // ARM64 generic table values.
        assertEquals(198, SocketSyscallNumbers.SOCKET)
        assertEquals(200, SocketSyscallNumbers.BIND)
        assertEquals(201, SocketSyscallNumbers.LISTEN)
        assertEquals(202, SocketSyscallNumbers.ACCEPT4)
        assertEquals(203, SocketSyscallNumbers.CONNECT)
        assertEquals(206, SocketSyscallNumbers.SENDTO)
        assertEquals(207, SocketSyscallNumbers.RECVFROM)
    }
}

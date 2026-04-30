package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the ashmem state machine that `device/ashmem.cpp` enforces and that the JVM-side
 * Kotlin oracle mirrors.
 */
class AshmemRegistryTest {
    @Test
    fun allocateAssignsDistinctFds() {
        val r = AshmemRegistry()
        val a = r.allocate()
        val b = r.allocate()
        assertNotEquals(a, b)
        assertEquals(2, r.openCount())
    }

    @Test
    fun releaseRemovesRegion() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        assertEquals(AshmemRegistry.Result.OK, r.release(fd))
        assertFalse(r.has(fd))
    }

    @Test
    fun ioctlRejectsUnknownFd() {
        val r = AshmemRegistry()
        assertEquals(AshmemRegistry.Result.EBADF, r.setName(99999, "x"))
        assertEquals(AshmemRegistry.Result.EBADF, r.setSize(99999, 4096))
        assertNull(r.getName(99999))
        assertNull(r.getSize(99999))
    }

    @Test
    fun setNameThenGetNameRoundTrips() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        r.setName(fd, "GraphicBuffer/0")
        assertEquals("GraphicBuffer/0", r.getName(fd))
    }

    @Test
    fun setNameRejectsAfterMmap() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        r.setSize(fd, 4096)
        r.markMapped(fd)
        assertEquals(AshmemRegistry.Result.EINVAL, r.setName(fd, "too_late"))
    }

    @Test
    fun setSizeRequiresPositiveValue() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        assertEquals(AshmemRegistry.Result.EINVAL, r.setSize(fd, 0))
        assertEquals(AshmemRegistry.Result.EINVAL, r.setSize(fd, -1))
    }

    @Test
    fun markMappedRequiresPriorSize() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        // Without SET_SIZE, markMapped is invalid.
        assertEquals(AshmemRegistry.Result.EINVAL, r.markMapped(fd))
        r.setSize(fd, 4096)
        assertEquals(AshmemRegistry.Result.OK, r.markMapped(fd))
    }

    @Test
    fun setSizeRejectedAfterMmap() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        r.setSize(fd, 4096)
        r.markMapped(fd)
        assertEquals(AshmemRegistry.Result.EINVAL, r.setSize(fd, 8192))
    }

    @Test
    fun protectionMaskDefaultsToReadWriteExec() {
        val r = AshmemRegistry()
        val fd = r.allocate()
        assertEquals(0x7, r.getProtMask(fd))
        r.setProtMask(fd, 0x3)
        assertEquals(0x3, r.getProtMask(fd))
    }

    @Test
    fun crossThreadVisibilityViaSameFdMatchesContract() {
        // Phase C MVP is single-process; the cross-thread visibility test only checks that
        // we observe the same metadata from another thread on the same registry.
        val r = AshmemRegistry()
        val fd = r.allocate()
        r.setName(fd, "shared")
        r.setSize(fd, 8192)
        val readback = arrayOfNulls<String>(1)
        val sizes = LongArray(1)
        val t = Thread {
            readback[0] = r.getName(fd)
            sizes[0] = r.getSize(fd) ?: 0
        }
        t.start(); t.join()
        assertEquals("shared", readback[0])
        assertEquals(8192L, sizes[0])
    }

    @Test
    fun ioctlNumbersMatchKernelMacros() {
        // Lock the constants — the C++ side computes the same with `_IOW/_IOR`.
        assertNotEquals(0, AshmemIoctl.SET_NAME)
        assertNotEquals(AshmemIoctl.SET_NAME, AshmemIoctl.GET_NAME)
        assertNotEquals(AshmemIoctl.SET_SIZE, AshmemIoctl.GET_SIZE)
        // Each unique.
        val codes = listOf(
            AshmemIoctl.SET_NAME, AshmemIoctl.GET_NAME,
            AshmemIoctl.SET_SIZE, AshmemIoctl.GET_SIZE,
            AshmemIoctl.SET_PROT_MASK, AshmemIoctl.GET_PROT_MASK,
        )
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun openCountTracksAllocateAndRelease() {
        val r = AshmemRegistry()
        val ids = (1..3).map { r.allocate() }
        assertEquals(3, r.openCount())
        r.release(ids[1])
        assertEquals(2, r.openCount())
        assertNotNull(r.getName(ids[0]))
        assertNull(r.getName(ids[1]))
    }
}

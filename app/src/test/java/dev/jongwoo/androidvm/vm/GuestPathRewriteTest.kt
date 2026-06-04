package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GuestPathRewriteTest {
    private val rootfs = "/data/data/dev.jongwoo.androidvm/files/avm/instances/vm1/rootfs"

    @Test
    fun mapsAbsoluteGuestPathUnderRootfs() {
        assertEquals(
            "$rootfs/system/lib64/libc.so",
            GuestPathRewrite.resolve(rootfs, "/system/lib64/libc.so"),
        )
    }

    @Test
    fun collapsesDotAndInternalDotDot() {
        assertEquals("$rootfs/system/build.prop", GuestPathRewrite.resolve(rootfs, "/system/./build.prop"))
        assertEquals("$rootfs/system/build.prop", GuestPathRewrite.resolve(rootfs, "/system/x/../build.prop"))
    }

    @Test
    fun rejectsTraversalAboveRoot() {
        assertNull(GuestPathRewrite.resolve(rootfs, "/../etc/passwd"))
        assertNull(GuestPathRewrite.resolve(rootfs, "/system/../../escape"))
    }

    @Test
    fun rejectsNonAbsolute() {
        assertNull(GuestPathRewrite.resolve(rootfs, "system/build.prop"))
        assertNull(GuestPathRewrite.resolve(rootfs, ""))
    }

    @Test
    fun rootMapsToRootfsBase() {
        assertEquals(rootfs, GuestPathRewrite.resolve(rootfs, "/"))
        assertEquals(rootfs, GuestPathRewrite.resolve("$rootfs/", "/"))
    }

    @Test
    fun procPathsMapUnderRootfsToo() {
        assertEquals("$rootfs/proc/cpuinfo", GuestPathRewrite.resolve(rootfs, "/proc/cpuinfo"))
    }
}

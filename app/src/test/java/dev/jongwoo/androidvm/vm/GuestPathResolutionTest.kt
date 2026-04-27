package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestPathResolutionTest {
    @Test
    fun fromJsonParsesSuccessfulHostPath() {
        val resolution = GuestPathResolution.fromJson(
            """
            {
              "status": "OK",
              "guestPath": "/data/local/tmp",
              "hostPath": "/tmp/rootfs/data/local/tmp",
              "writable": true,
              "virtualNode": false
            }
            """.trimIndent(),
        )

        assertTrue(resolution.ok)
        assertEquals(GuestPathStatus.OK, resolution.status)
        assertEquals("/data/local/tmp", resolution.guestPath)
        assertEquals("/tmp/rootfs/data/local/tmp", resolution.hostPath)
        assertTrue(resolution.writable)
        assertFalse(resolution.virtualNode)
    }

    @Test
    fun fromJsonParsesReadonlyFailure() {
        val resolution = GuestPathResolution.fromJson(
            """
            {
              "status": "READ_ONLY",
              "guestPath": "/system/build.prop",
              "hostPath": "/tmp/rootfs/system/build.prop",
              "writable": false,
              "virtualNode": false
            }
            """.trimIndent(),
        )

        assertFalse(resolution.ok)
        assertEquals(GuestPathStatus.READ_ONLY, resolution.status)
    }
}

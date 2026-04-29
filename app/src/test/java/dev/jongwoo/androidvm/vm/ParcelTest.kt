package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the byte-level contract that [Parcel] and `binder/parcel.cpp` share. Both produce
 * the same byte sequence for the same call sequence — required so cross-language tests
 * (Kotlin reads what C++ wrote) work on-device.
 */
class ParcelTest {
    @Test
    fun int32WritesFourLittleEndianBytes() {
        val p = Parcel()
        p.writeInt32(0x12345678)
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), p.bytes)
    }

    @Test
    fun int64IsAlignedTo4ButTakes8Bytes() {
        val p = Parcel()
        p.writeInt32(1)
        p.writeInt64(0x0102030405060708L)
        // [0..3] = int32(1); [4..11] = int64 raw LE.
        assertEquals(12, p.size)
        val raw = p.bytes
        assertEquals(0x08, raw[4].toInt() and 0xFF)
        assertEquals(0x07, raw[5].toInt() and 0xFF)
        assertEquals(0x01, raw[11].toInt() and 0xFF)
    }

    @Test
    fun string16WritesLengthCharsAndNulPadded() {
        val p = Parcel()
        p.writeString16("hi")
        // length(int32) = 2; chars = 'h''i' UTF16LE = 68 00 69 00; NUL = 00 00; pad to 4 → already 8.
        assertArrayEquals(
            byteArrayOf(2, 0, 0, 0, 0x68, 0x00, 0x69, 0x00, 0x00, 0x00, 0x00, 0x00),
            p.bytes,
        )
    }

    @Test
    fun nullStringWritesNegativeOne() {
        val p = Parcel()
        p.writeString16(null)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), p.bytes)
    }

    @Test
    fun roundTripsAllPrimitives() {
        val p = Parcel()
        p.writeInt32(42)
        p.writeInt64(0x1122334455667788L)
        p.writeFloat(3.5f)
        p.writeDouble(-2.25)
        p.writeBool(true)
        p.writeString16("alpha")
        p.writeString16(null)

        p.readPosition = 0
        assertEquals(42, p.readInt32())
        assertEquals(0x1122334455667788L, p.readInt64())
        assertEquals(3.5f, p.readFloat(), 0.0f)
        assertEquals(-2.25, p.readDouble(), 0.0)
        assertEquals(true, p.readBool())
        assertEquals("alpha", p.readString16())
        assertNull(p.readString16())
    }

    @Test
    fun strongBinderHandleProducesFlatBinderObject() {
        val p = Parcel()
        p.writeStrongBinderHandle(0x42)
        // 4 (type) + 4 (flags) + 8 (handle64) + 8 (cookie) = 24 bytes.
        assertEquals(24, p.size)
        val raw = p.bytes
        // type = BINDER_TYPE_HANDLE in LE.
        assertEquals(0x85.toByte(), raw[0])
        assertEquals(0x2a.toByte(), raw[1])
        assertEquals(0x68.toByte(), raw[2])
        assertEquals(0x73.toByte(), raw[3])
        // handle64 starts at offset 8.
        assertEquals(0x42.toByte(), raw[8])
        assertEquals(0x00.toByte(), raw[9])
    }

    @Test
    fun fileDescriptorTypeIsFD() {
        val p = Parcel()
        p.writeFileDescriptor(7)
        val raw = p.bytes
        // BINDER_TYPE_FD magic.
        assertEquals(0x85.toByte(), raw[0])
        assertEquals(0x2a.toByte(), raw[1])
        assertEquals(0x64.toByte(), raw[2])
        assertEquals(0x66.toByte(), raw[3])
    }

    @Test
    fun stringRoundTripsAllAsciiCharacters() {
        val p = Parcel()
        val sample = "The quick brown fox 1234"
        p.writeString16(sample)
        p.readPosition = 0
        assertEquals(sample, p.readString16())
    }

    @Test
    fun serviceManagerCodesMatchAndroid() {
        // Lock the values — they correspond to AIDL `IServiceManager` transaction ordinals.
        assertEquals(0x00000001, ServiceManagerCodes.GET_SERVICE)
        assertEquals(0x00000002, ServiceManagerCodes.CHECK_SERVICE)
        assertEquals(0x00000003, ServiceManagerCodes.ADD_SERVICE)
        assertEquals(0x00000004, ServiceManagerCodes.LIST_SERVICES)
    }

    @Test
    fun binderIoctlNumbersMatchKernelMacros() {
        // BINDER_WRITE_READ = _IOWR('b', 1, 48). Distinct, non-zero.
        assertNotEquals(0, BinderIoctl.WRITE_READ)
        assertNotEquals(BinderIoctl.WRITE_READ, BinderIoctl.SET_MAX_THREADS)
        assertNotEquals(BinderIoctl.WRITE_READ, BinderIoctl.VERSION)
        // BC_TRANSACTION / BR_TRANSACTION constants pinned.
        assertEquals(0x40406300.toInt(), BinderIoctl.Cmd.BC_TRANSACTION)
        assertEquals(0x40406301.toInt(), BinderIoctl.Cmd.BC_REPLY)
        assertEquals(0x80307202.toInt(), BinderIoctl.Cmd.BR_TRANSACTION)
        assertEquals(0x80307203.toInt(), BinderIoctl.Cmd.BR_REPLY)
        // No collision between BC and BR spaces.
        val bc = setOf(
            BinderIoctl.Cmd.BC_TRANSACTION, BinderIoctl.Cmd.BC_REPLY,
            BinderIoctl.Cmd.BC_INCREFS, BinderIoctl.Cmd.BC_DECREFS,
            BinderIoctl.Cmd.BC_FREE_BUFFER, BinderIoctl.Cmd.BC_DEAD_BINDER_DONE,
        )
        val br = setOf(
            BinderIoctl.Cmd.BR_TRANSACTION, BinderIoctl.Cmd.BR_REPLY,
            BinderIoctl.Cmd.BR_NOOP, BinderIoctl.Cmd.BR_TRANSACTION_COMPLETE,
        )
        assertTrue("BC and BR codes must not collide", bc.intersect(br).isEmpty())
    }

    @Test
    fun parcelCanBeReused() {
        val p = Parcel()
        p.writeInt32(99)
        p.clear()
        assertEquals(0, p.size)
        p.writeInt32(1)
        assertEquals(4, p.size)
    }
}

package dev.jongwoo.androidvm.vm

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-Kotlin twin of `app/src/main/cpp/binder/parcel.cpp`. Both implementations produce
 * byte-equal output for the same call sequence — Phase C.1's contract is that anything we
 * marshal in Kotlin land could be read back by the on-device native parser, and vice versa.
 *
 * Wire format mirrors `<frameworks/native/include/binder/Parcel.h>`:
 *  - all primitives align write position to 4 bytes before writing;
 *  - int64/double get 4-byte alignment then 8 bytes raw little-endian;
 *  - string16: int32 length-in-chars (-1 for null) + UTF-16LE chars + 2-byte NUL + pad to 4;
 *  - flat_binder_object handle: 24 bytes (type + flags + handle64 + cookie64);
 *  - flat_binder_object fd: 24 bytes (type + flags + fd64 + cookie64).
 */
class Parcel {
    private val out = java.io.ByteArrayOutputStream()
    private var readCursor = 0

    val bytes: ByteArray get() = out.toByteArray()
    val size: Int get() = out.size()
    var readPosition: Int
        get() = readCursor
        set(value) { readCursor = value }

    fun clear() { out.reset(); readCursor = 0 }

    // ---- writers ----
    fun writeInt32(v: Int): Parcel {
        alignWrite(4)
        out.write(intLeBytes(v))
        return this
    }

    fun writeUInt32(v: Int): Parcel = writeInt32(v)

    fun writeInt64(v: Long): Parcel {
        alignWrite(4)
        out.write(longLeBytes(v))
        return this
    }

    fun writeFloat(v: Float): Parcel = writeInt32(java.lang.Float.floatToRawIntBits(v))

    fun writeDouble(v: Double): Parcel = writeInt64(java.lang.Double.doubleToRawLongBits(v))

    fun writeBool(v: Boolean): Parcel = writeInt32(if (v) 1 else 0)

    fun writeString16(s: String?): Parcel {
        if (s == null) {
            writeInt32(-1)
            return this
        }
        writeInt32(s.length)
        if (s.isNotEmpty()) {
            val buf = ByteBuffer.allocate(s.length * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (c in s) buf.putChar(c)
            out.write(buf.array())
        }
        // NUL terminator (2 bytes) + pad.
        out.write(0); out.write(0)
        alignWrite(4)
        return this
    }

    fun writeStrongBinderHandle(handle: Int): Parcel {
        alignWrite(4)
        // type (BINDER_TYPE_HANDLE), flags, handle64, cookie64.
        out.write(intLeBytes(0x73682a85.toInt()))
        out.write(intLeBytes(0x17F))  // flags = 0x7F | (1<<8)
        out.write(longLeBytes(handle.toLong()))
        out.write(longLeBytes(0))
        return this
    }

    fun writeFileDescriptor(fd: Int): Parcel {
        alignWrite(4)
        out.write(intLeBytes(0x66642a85.toInt()))
        out.write(intLeBytes(0))
        out.write(longLeBytes(fd.toLong()))
        out.write(longLeBytes(0))
        return this
    }

    // ---- readers ----
    fun readInt32(): Int {
        alignRead(4)
        return readIntLe()
    }

    fun readInt64(): Long {
        alignRead(4)
        return readLongLe()
    }

    fun readFloat(): Float = java.lang.Float.intBitsToFloat(readInt32())
    fun readDouble(): Double = java.lang.Double.longBitsToDouble(readInt64())
    fun readBool(): Boolean = readInt32() != 0

    fun readString16(): String? {
        val len = readInt32()
        if (len < 0) return null
        val raw = bytes
        if (readCursor + len * 2 + 2 > raw.size) error("Parcel underflow reading String16")
        val sb = StringBuilder(len)
        repeat(len) { i ->
            val lo = raw[readCursor + i * 2].toInt() and 0xFF
            val hi = raw[readCursor + i * 2 + 1].toInt() and 0xFF
            sb.append(((hi shl 8) or lo).toChar())
        }
        readCursor += len * 2 + 2  // chars + NUL terminator
        alignRead(4)
        return sb.toString()
    }

    fun appendRaw(data: ByteArray) {
        out.write(data)
    }

    private fun alignWrite(alignment: Int) {
        while (out.size() % alignment != 0) out.write(0)
    }

    private fun alignRead(alignment: Int) {
        val raw = bytes
        while (readCursor % alignment != 0 && readCursor < raw.size) readCursor++
    }

    private fun readIntLe(): Int {
        val raw = bytes
        if (readCursor + 4 > raw.size) error("Parcel underflow reading int32")
        val v = (raw[readCursor].toInt() and 0xFF) or
            ((raw[readCursor + 1].toInt() and 0xFF) shl 8) or
            ((raw[readCursor + 2].toInt() and 0xFF) shl 16) or
            ((raw[readCursor + 3].toInt() and 0xFF) shl 24)
        readCursor += 4
        return v
    }

    private fun readLongLe(): Long {
        val raw = bytes
        if (readCursor + 8 > raw.size) error("Parcel underflow reading int64")
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((raw[readCursor + i].toLong() and 0xFFL) shl (i * 8))
        }
        readCursor += 8
        return v
    }

    private fun intLeBytes(v: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = ((v ushr (i * 8)) and 0xFF).toByte()
        return b
    }

    private fun longLeBytes(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v ushr (i * 8)) and 0xFFL).toByte()
        return b
    }

    companion object {
        const val SERVICE_MANAGER_HANDLE: Int = 0
    }
}

/**
 * Service manager transaction codes — must match `binder/service_manager.h`.
 */
object ServiceManagerCodes {
    const val GET_SERVICE: Int = 0x00000001
    const val CHECK_SERVICE: Int = 0x00000002
    const val ADD_SERVICE: Int = 0x00000003
    const val LIST_SERVICES: Int = 0x00000004
}

/**
 * Binder ioctl numbers — calculated from `_IOR/_IOW/_IOWR` macros to match Linux kernel
 * UAPI. Tests pin these so a refactor of the C++ macro site cannot silently drift.
 */
object BinderIoctl {
    private const val IOC_NRBITS: Int = 8
    private const val IOC_TYPEBITS: Int = 8
    private const val IOC_SIZEBITS: Int = 14
    private const val IOC_DIRBITS: Int = 2
    private const val IOC_NRSHIFT: Int = 0
    private const val IOC_TYPESHIFT: Int = IOC_NRSHIFT + IOC_NRBITS
    private const val IOC_SIZESHIFT: Int = IOC_TYPESHIFT + IOC_TYPEBITS
    private const val IOC_DIRSHIFT: Int = IOC_SIZESHIFT + IOC_SIZEBITS

    private const val IOC_NONE: Int = 0
    private const val IOC_WRITE: Int = 1
    private const val IOC_READ: Int = 2

    private fun ioc(dir: Int, type: Int, nr: Int, size: Int): Int =
        (dir shl IOC_DIRSHIFT) or
            (type shl IOC_TYPESHIFT) or
            (nr shl IOC_NRSHIFT) or
            (size shl IOC_SIZESHIFT)
    private fun iow(type: Int, nr: Int, size: Int) = ioc(IOC_WRITE, type, nr, size)
    private fun ior(type: Int, nr: Int, size: Int) = ioc(IOC_READ, type, nr, size)
    private fun iowr(type: Int, nr: Int, size: Int) = ioc(IOC_WRITE or IOC_READ, type, nr, size)

    val WRITE_READ: Int = iowr('b'.code, 1, 48)
    val SET_MAX_THREADS: Int = iow('b'.code, 5, 4)
    val VERSION: Int = iowr('b'.code, 9, 4)

    object Cmd {
        const val BC_TRANSACTION: Int = 0x40406300.toInt()
        const val BC_REPLY: Int = 0x40406301.toInt()
        const val BC_INCREFS: Int = 0x40046305.toInt()
        const val BC_DECREFS: Int = 0x40046306.toInt()
        const val BC_FREE_BUFFER: Int = 0x40046309.toInt()
        const val BC_DEAD_BINDER_DONE: Int = 0x40406311.toInt()
        const val BR_TRANSACTION: Int = 0x80307202.toInt()
        const val BR_REPLY: Int = 0x80307203.toInt()
        const val BR_NOOP: Int = 0x80007207.toInt()
        const val BR_TRANSACTION_COMPLETE: Int = 0x80007208.toInt()
    }
}

package dev.jongwoo.androidvm.vm

/**
 * Pure-Kotlin twin of `app/src/main/cpp/device/ashmem.cpp`. Mirrors the per-fd state
 * machine — name + size are mutable until the first `mmap`, then frozen. Real fd /
 * `memfd_create` work happens in C++; this Kotlin oracle only locks the rules we want
 * the on-device runtime to enforce.
 */
class AshmemRegistry {
    enum class Result { OK, EBADF, EINVAL, EFAULT }

    data class Region(
        var name: String = "",
        var size: Long = 0,
        var protMask: Int = 0x7,    // PROT_READ|WRITE|EXEC
        var mapped: Boolean = false,
        var sized: Boolean = false,
    )

    private var nextFd = 100
    private val regions = mutableMapOf<Int, Region>()

    fun allocate(): Int {
        val fd = nextFd++
        regions[fd] = Region()
        return fd
    }

    fun release(fd: Int): Result {
        return if (regions.remove(fd) == null) Result.EBADF else Result.OK
    }

    fun setName(fd: Int, name: String): Result {
        val r = regions[fd] ?: return Result.EBADF
        if (r.mapped) return Result.EINVAL
        if (name.length > MAX_NAME_LEN) return Result.EINVAL
        r.name = name
        return Result.OK
    }

    fun getName(fd: Int): String? = regions[fd]?.name

    fun setSize(fd: Int, size: Long): Result {
        val r = regions[fd] ?: return Result.EBADF
        if (r.mapped) return Result.EINVAL
        if (size <= 0) return Result.EINVAL
        r.size = size
        r.sized = true
        return Result.OK
    }

    fun getSize(fd: Int): Long? = regions[fd]?.size

    fun setProtMask(fd: Int, mask: Int): Result {
        val r = regions[fd] ?: return Result.EBADF
        r.protMask = mask
        return Result.OK
    }

    fun getProtMask(fd: Int): Int? = regions[fd]?.protMask

    fun markMapped(fd: Int): Result {
        val r = regions[fd] ?: return Result.EBADF
        if (!r.sized) return Result.EINVAL
        r.mapped = true
        return Result.OK
    }

    fun openCount(): Int = regions.size

    fun has(fd: Int): Boolean = regions.containsKey(fd)

    companion object {
        const val MAX_NAME_LEN: Int = 63
    }
}

/**
 * Ashmem ioctl numbers — calculated to match `<cutils/ashmem.h>` (type 'w' = 0x77). Mirrors
 * the macros in `app/src/main/cpp/device/ashmem.h`.
 */
object AshmemIoctl {
    private const val IOC_NRBITS = 8
    private const val IOC_TYPEBITS = 8
    private const val IOC_SIZEBITS = 14

    private const val IOC_NRSHIFT = 0
    private const val IOC_TYPESHIFT = IOC_NRSHIFT + IOC_NRBITS
    private const val IOC_SIZESHIFT = IOC_TYPESHIFT + IOC_TYPEBITS
    private const val IOC_DIRSHIFT = IOC_SIZESHIFT + IOC_SIZEBITS

    private const val IOC_WRITE = 1
    private const val IOC_READ = 2

    private fun ioc(dir: Int, type: Int, nr: Int, size: Int): Int =
        (dir shl IOC_DIRSHIFT) or (type shl IOC_TYPESHIFT) or
            (nr shl IOC_NRSHIFT) or (size shl IOC_SIZESHIFT)
    private fun iow(type: Int, nr: Int, size: Int) = ioc(IOC_WRITE, type, nr, size)
    private fun ior(type: Int, nr: Int, size: Int) = ioc(IOC_READ, type, nr, size)

    private const val ASHMEM_TYPE = 0x77

    val SET_NAME: Int = iow(ASHMEM_TYPE, 1, 256)
    val GET_NAME: Int = ior(ASHMEM_TYPE, 2, 256)
    val SET_SIZE: Int = iow(ASHMEM_TYPE, 3, 8)
    val GET_SIZE: Int = ior(ASHMEM_TYPE, 4, 8)
    val SET_PROT_MASK: Int = iow(ASHMEM_TYPE, 5, 4)
    val GET_PROT_MASK: Int = ior(ASHMEM_TYPE, 6, 4)
}

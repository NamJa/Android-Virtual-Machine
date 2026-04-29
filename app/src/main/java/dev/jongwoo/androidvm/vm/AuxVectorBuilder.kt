package dev.jongwoo.androidvm.vm

/**
 * Pure-Kotlin twin of `app/src/main/cpp/loader/aux_vector.{h,cpp}`. JVM unit tests pin the
 * `AT_*` constants and the flatten order; the native code mirrors this exactly so a future
 * cross-validation test (when a device is wired up) only needs to compare two byte vectors.
 */
object AuxType {
    const val AT_NULL: Long     = 0
    const val AT_PHDR: Long     = 3
    const val AT_PHENT: Long    = 4
    const val AT_PHNUM: Long    = 5
    const val AT_PAGESZ: Long   = 6
    const val AT_BASE: Long     = 7
    const val AT_FLAGS: Long    = 8
    const val AT_ENTRY: Long    = 9
    const val AT_UID: Long      = 11
    const val AT_EUID: Long     = 12
    const val AT_GID: Long      = 13
    const val AT_EGID: Long     = 14
    const val AT_PLATFORM: Long = 15
    const val AT_HWCAP: Long    = 16
    const val AT_CLKTCK: Long   = 17
    const val AT_RANDOM: Long   = 25
    const val AT_HWCAP2: Long   = 26
}

class AuxVectorBuilder {
    private val entries = mutableListOf<Pair<Long, Long>>()

    fun push(type: Long, value: Long): AuxVectorBuilder {
        require(type != AuxType.AT_NULL) { "AT_NULL is appended automatically" }
        entries += type to value
        return this
    }

    /** Returns `[type0, val0, type1, val1, ..., AT_NULL, 0]`. */
    fun build(): LongArray {
        val out = LongArray(entries.size * 2 + 2)
        var i = 0
        for ((t, v) in entries) {
            out[i++] = t
            out[i++] = v
        }
        out[i++] = AuxType.AT_NULL
        out[i] = 0
        return out
    }

    val size: Int
        get() = entries.size
}

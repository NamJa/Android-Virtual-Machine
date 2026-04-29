package dev.jongwoo.androidvm.vm

/**
 * ARM64 syscall numbers from `<asm-generic/unistd.h>`. The Phase B native dispatcher in
 * `app/src/main/cpp/syscall/syscall_dispatch.h` mirrors these one-for-one. JVM unit tests
 * pin both layers — see [SyscallTrapDecisionTest], [FutexEmulatorTest].
 */
object SyscallNumbers {
    const val IOCTL          = 29
    const val FALLOCATE      = 47
    const val OPENAT         = 56
    const val CLOSE          = 57
    const val READ           = 63
    const val WRITE          = 64
    const val NEWFSTATAT     = 79
    const val FSTAT          = 80
    const val EXIT_GROUP     = 94
    const val SET_TID_ADDRESS = 96
    const val FUTEX          = 98
    const val NANOSLEEP      = 101
    const val CLOCK_GETTIME  = 113
    const val TGKILL         = 131
    const val RT_SIGACTION   = 134
    const val RT_SIGPROCMASK = 135
    const val GETPID         = 172
    const val GETUID         = 174
    const val GETEUID        = 175
    const val GETTID         = 178
    const val BRK            = 214
    const val MUNMAP         = 215
    const val MMAP           = 222
    const val MPROTECT       = 226
    const val PRLIMIT64      = 261

    /** Phase B MVP table — the doc lists ~25; we wire 25. */
    val MVP_TABLE: List<Int> = listOf(
        IOCTL, FALLOCATE, OPENAT, CLOSE, READ, WRITE, NEWFSTATAT, FSTAT,
        EXIT_GROUP, SET_TID_ADDRESS, FUTEX, NANOSLEEP, CLOCK_GETTIME, TGKILL,
        RT_SIGACTION, RT_SIGPROCMASK, GETPID, GETUID, GETEUID, GETTID,
        BRK, MUNMAP, MMAP, MPROTECT, PRLIMIT64,
    )
}

enum class TrapMechanism(val wireValue: Int) {
    UNAVAILABLE(0),
    LIBC_PATCH(1),
    SECCOMP_SIGSYS(2),
}

/**
 * Decision tree from `phase-b-guest-runtime-poc.md` § B.4.f. Made once per instance during
 * LOADING and recorded in the bridge audit log.
 */
data class SyscallTrapInputs(
    val seccompSigsysAvailable: Boolean,
    val libcPatchPossible: Boolean,
)

object SyscallTrapDecision {
    fun decide(input: SyscallTrapInputs): TrapMechanism {
        if (input.seccompSigsysAvailable) return TrapMechanism.SECCOMP_SIGSYS
        if (input.libcPatchPossible) return TrapMechanism.LIBC_PATCH
        return TrapMechanism.UNAVAILABLE
    }
}

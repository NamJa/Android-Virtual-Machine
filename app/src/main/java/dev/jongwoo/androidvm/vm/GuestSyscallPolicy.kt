package dev.jongwoo.androidvm.vm

/**
 * EP2.3 — the syscall servicing policy for the Option B guest-execution gateway
 * (forked child + seccomp `SECCOMP_RET_TRAP`/SIGSYS). This is the *spec* the
 * native seccomp BPF filter is generated from and that the SIGSYS handler routes
 * by; encoding it as a pure, JVM-testable oracle keeps the classification
 * reviewable and regression-tested independent of on-device iteration.
 *
 * Dispositions:
 *  - [ALLOW]: let the kernel service it directly in the guest process. Safe
 *    because process separation gives the guest its own address space, TLS,
 *    threads and signals (these are exactly why Option B was chosen over A).
 *  - [HOST_SERVICED]: trap to the host (SIGSYS) so the VFS / property / binder /
 *    network bridge can mediate it (path rewriting into the instance rootfs,
 *    /dev/binder, device-profile spoofing, network policy).
 *  - [DENY]: never permitted to the guest; the host returns EPERM.
 *
 * Unknown syscalls default to [DENY] (fail-closed). The ALLOW/HOST_SERVICED sets
 * grow as the real APK corpus reveals what the guest actually issues (EP2.3 / P2:
 * "expand syscall dispatch table by syscalls observed in actual app corpus").
 */
enum class GuestSyscallDisposition { ALLOW, HOST_SERVICED, DENY }

object GuestSyscallPolicy {
    // Kernel-direct: arithmetic on already-open fds, memory, threads, signals,
    // time, randomness. Safe in the isolated guest process.
    private val ALLOW = setOf(
        // already-open fd I/O (the openat that produced the fd is host-serviced)
        "read", "write", "close", "lseek", "pread64", "pwrite64", "readv", "writev",
        "getdents64", "fcntl", "dup", "dup3", "pipe2", "ppoll", "pselect6", "epoll_create1",
        "epoll_ctl", "epoll_pwait", "eventfd2",
        // memory
        "mmap", "munmap", "mprotect", "mremap", "madvise", "brk", "mlock", "munlock",
        // threads / sync / scheduling
        "clone", "futex", "set_tid_address", "set_robust_list", "get_robust_list",
        "sched_yield", "sched_getaffinity", "gettid", "getpid", "exit", "exit_group",
        // signals
        "rt_sigaction", "rt_sigprocmask", "rt_sigreturn", "sigaltstack", "rt_sigtimedwait",
        // time / random / identity (read-only)
        "clock_gettime", "clock_getres", "gettimeofday", "nanosleep", "clock_nanosleep",
        "getrandom", "getuid", "getgid", "geteuid", "getegid",
    )

    // Must be mediated by a host bridge.
    private val HOST_SERVICED = setOf(
        // path-bearing: rewrite guest path -> instance rootfs
        "openat", "openat2", "faccessat", "faccessat2", "readlinkat", "newfstatat", "statx",
        "mkdirat", "unlinkat", "renameat2", "symlinkat", "linkat", "fchmodat", "fchownat",
        "statfs", "fstatfs", "truncate", "getcwd", "chdir",
        // device / IPC
        "ioctl", // /dev/binder, /dev/ashmem, framebuffer
        // networking -> network bridge / policy
        "socket", "connect", "bind", "sendto", "recvfrom", "sendmsg", "recvmsg", "getsockopt",
        // identity / environment spoofing -> device profile
        "uname", "sysinfo",
    )

    // Forbidden to the guest.
    private val DENY = setOf(
        "ptrace", "mount", "umount2", "pivot_root", "chroot", "reboot",
        "init_module", "finit_module", "delete_module", "kexec_load",
        "setuid", "setgid", "setreuid", "setregid", "setns", "unshare",
        "swapon", "swapoff", "syslog", "acct", "settimeofday", "clock_settime",
    )

    val table: Map<String, GuestSyscallDisposition> = buildMap {
        ALLOW.forEach { put(it, GuestSyscallDisposition.ALLOW) }
        HOST_SERVICED.forEach { put(it, GuestSyscallDisposition.HOST_SERVICED) }
        DENY.forEach { put(it, GuestSyscallDisposition.DENY) }
    }

    /** Unknown syscalls fail closed to DENY until explicitly classified. */
    fun dispositionOf(syscall: String): GuestSyscallDisposition =
        table[syscall] ?: GuestSyscallDisposition.DENY

    /** Syscalls the BPF filter lets through with SECCOMP_RET_ALLOW. */
    fun allowList(): Set<String> = ALLOW

    /** Syscalls the BPF filter traps with SECCOMP_RET_TRAP for host servicing. */
    fun trapList(): Set<String> = HOST_SERVICED + DENY
}

package dev.jongwoo.androidvm.vm

import java.io.File

/**
 * Phase C zygote socket spec. The doc maps the kernel-style `/dev/socket/zygote` paths
 * onto the host filesystem under `<instance>/runtime/sockets/`. The C++ socket dispatcher
 * (`syscall/socket.cpp`) consults this mapping when the guest binds an AF_UNIX address.
 */
object ZygoteLayout {
    fun primarySocketPath(instanceRuntimeDir: File): File =
        File(instanceRuntimeDir, "sockets/zygote")

    fun secondarySocketPath(instanceRuntimeDir: File): File =
        File(instanceRuntimeDir, "sockets/zygote_secondary")

    fun guestSocketAddress(name: String): String = "/dev/socket/$name"
}

/**
 * The 11 Android 7.1.2 ART runtime libraries that zygote dlopens before reaching the main
 * loop. Phase C's `STAGE_PHASE_C_ZYGOTE` regression checks `libs_loaded >= 11`.
 */
object ArtRuntimeChain {
    val PRIMARY_LIBS: List<String> = listOf(
        "libart.so",
        "libnativehelper.so",
        "libnativebridge.so",
        "libbase.so",
        "libcutils.so",
        "libutils.so",
        "libbinder.so",
        "libui.so",
        "libgui.so",
        "libsigchain.so",
        "libdl.so",
    )

    /** Phase B already maps libdl + libc; the zygote chain is the additional set. */
    val ZYGOTE_DLOPEN_CHAIN: List<String> = PRIMARY_LIBS - listOf("libdl.so")

    val EXPECTED_COUNT: Int = PRIMARY_LIBS.size
}

/**
 * Phase C clone-flag handling. The MVP only supports `CLONE_VM | CLONE_THREAD` (= host
 * pthread) — anything else (esp. `CLONE_NEWPID`) needs Phase D.7 / Phase E namespace work.
 *
 * Constants come from `<linux/sched.h>`.
 */
object CloneFlags {
    const val CLONE_VM: Long           = 0x00000100
    const val CLONE_FS: Long           = 0x00000200
    const val CLONE_FILES: Long        = 0x00000400
    const val CLONE_SIGHAND: Long      = 0x00000800
    const val CLONE_THREAD: Long       = 0x00010000
    const val CLONE_SYSVSEM: Long      = 0x00040000
    const val CLONE_SETTLS: Long       = 0x00080000
    const val CLONE_PARENT_SETTID: Long = 0x00100000
    const val CLONE_CHILD_CLEARTID: Long = 0x00200000
    const val CLONE_NEWPID: Long       = 0x20000000

    private val PHASE_C_ALLOWED: Long = CLONE_VM or CLONE_THREAD or CLONE_SIGHAND or
        CLONE_FILES or CLONE_FS or CLONE_SYSVSEM or CLONE_SETTLS or
        CLONE_PARENT_SETTID or CLONE_CHILD_CLEARTID

    enum class Decision { ALLOWED_THREAD, REJECTED_PROCESS_NAMESPACE, REJECTED_UNKNOWN }

    fun decide(flags: Long): Decision {
        if ((flags and CLONE_NEWPID) != 0L) return Decision.REJECTED_PROCESS_NAMESPACE
        val unknown = flags and PHASE_C_ALLOWED.inv()
        if (unknown != 0L) return Decision.REJECTED_UNKNOWN
        return Decision.ALLOWED_THREAD
    }
}

/** Numbers of the AF_UNIX socket syscalls — mirror `syscall/socket.h`. */
object SocketSyscallNumbers {
    const val SOCKET: Int = 198
    const val BIND: Int = 200
    const val LISTEN: Int = 201
    const val ACCEPT4: Int = 202
    const val CONNECT: Int = 203
    const val SENDTO: Int = 206
    const val RECVFROM: Int = 207

    const val AF_UNIX: Int = 1
    const val SOCK_STREAM: Int = 1
}

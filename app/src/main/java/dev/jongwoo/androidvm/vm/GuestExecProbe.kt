package dev.jongwoo.androidvm.vm

import org.json.JSONObject

/**
 * EP1 spike — JNI surface for the guest-execution capability probe
 * (`app/src/main/cpp/spike/guest_exec_probe.cpp`). Must be run on target
 * hardware; its result drives the GUEST_ARCH_DECISION gate.
 *
 * JVM unit tests exercise only [GuestExecCapabilities.fromJson]; the native call
 * is invoked on-device by the debug `GuestExecProbeReceiver`.
 */
object GuestExecProbe {
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("avm_host")
            loaded = true
        }
    }

    fun probe(): GuestExecCapabilities {
        ensureLoaded()
        return GuestExecCapabilities.fromJson(nativeProbe())
    }

    @JvmStatic
    external fun nativeProbe(): String
}

/**
 * Measured capabilities of the untrusted_app domain on the running device.
 *
 *  - [protExecMmap] / [memfdExec]: can freshly-written code be executed at all
 *    (W^X)? At least one must be true for any clean-room guest execution.
 *  - [seccompTrap]: can the app field its own SECCOMP_RET_TRAP — the basis of
 *    Option B's unmodified-syscall servicing.
 *  - [ptraceChild]: basis of Option B' (ptrace syscall emulation).
 *  - [cloneThread]: thread creation (ART is multi-threaded).
 */
data class GuestExecCapabilities(
    val arch: String,
    val protExecMmap: Boolean,
    val memfdExec: Boolean,
    val seccompTrap: Boolean,
    val ptraceChild: Boolean,
    val cloneThread: Boolean,
) {
    /** Option B (forked child + seccomp SIGSYS servicing) is viable on this device. */
    val optionBViable: Boolean
        get() = seccompTrap && (memfdExec || protExecMmap)

    /** Option B' (separate process + ptrace) is viable on this device. */
    val optionBPrimeViable: Boolean
        get() = ptraceChild && (memfdExec || protExecMmap)

    fun format(): String =
        "GUEST_EXEC_PROBE arch=$arch prot_exec_mmap=$protExecMmap memfd_exec=$memfdExec " +
            "seccomp_trap=$seccompTrap ptrace_child=$ptraceChild clone_thread=$cloneThread " +
            "option_b_viable=$optionBViable option_b_prime_viable=$optionBPrimeViable"

    companion object {
        fun fromJson(json: String): GuestExecCapabilities {
            val o = runCatching { JSONObject(json) }.getOrNull()
                ?: return GuestExecCapabilities("unparseable", false, false, false, false, false)
            return GuestExecCapabilities(
                arch = o.optString("arch", "unknown"),
                protExecMmap = o.optBoolean("prot_exec_mmap", false),
                memfdExec = o.optBoolean("memfd_exec", false),
                seccompTrap = o.optBoolean("seccomp_trap", false),
                ptraceChild = o.optBoolean("ptrace_child", false),
                cloneThread = o.optBoolean("clone_thread", false),
            )
        }
    }
}

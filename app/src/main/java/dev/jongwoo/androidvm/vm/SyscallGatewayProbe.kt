package dev.jongwoo.androidvm.vm

/**
 * EP2.3 spike — JNI surface for the seccomp SIGSYS gateway servicing probe
 * (`app/src/main/cpp/spike/syscall_gateway_probe.cpp`). In a forked child it
 * installs the Option B gateway and issues a real `uname` syscall; the SIGSYS
 * handler services it synthetically. Returns JSON describing what the child saw.
 *
 * Must run on target hardware; invoked on-device by `SyscallGatewayProbeReceiver`.
 */
object SyscallGatewayProbe {
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("avm_host")
            loaded = true
        }
    }

    fun probe(): String {
        ensureLoaded()
        return nativeProbe()
    }

    @JvmStatic
    external fun nativeProbe(): String
}

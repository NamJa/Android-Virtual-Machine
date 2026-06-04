package dev.jongwoo.androidvm.vm

/**
 * EP2.2 spike — JNI surface for the real linker64 bootstrap mechanism probe
 * (`app/src/main/cpp/spike/linker_bootstrap_probe.cpp`). Maps a real PIE +
 * its PT_INTERP linker, builds the initial stack/auxv, and jumps to the linker
 * entry in a forked child, returning JSON describing how far the bootstrap got.
 *
 * Must run on target hardware; invoked on-device by the debug
 * `LinkerBootstrapProbeReceiver`.
 */
object LinkerBootstrapProbe {
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("avm_host")
            loaded = true
        }
    }

    /**
     * Returns the raw JSON result; the caller logs/parses it.
     * @param gateway when true, installs the EP2.3 seccomp SIGSYS gateway in the
     *   child before jumping, proving the linker bootstraps under the filter.
     */
    fun probe(execPath: String, linkerPath: String, gateway: Boolean = false): String {
        ensureLoaded()
        return nativeProbe(execPath, linkerPath, gateway)
    }

    @JvmStatic
    external fun nativeProbe(execPath: String, linkerPath: String, gateway: Boolean): String
}

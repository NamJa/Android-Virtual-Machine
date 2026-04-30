package dev.jongwoo.androidvm.vm

/**
 * Phase E.3 / E.4 per-API-level profile. Each profile encodes the small set of capabilities the
 * native runtime needs to switch when the guest is a different Android version: binderfs path,
 * memfd_create stability, scoped storage, ART ABI, seccomp filter strictness, and RRO scan.
 *
 * Pure-JVM oracle so the diagnostics harness can verify the host has wired the right profile
 * before the on-device receiver runs the actual boot probes.
 */
enum class GuestApiLevelProfile(
    val androidLabel: String,
    val apiLevel: Int,
    val binderfsPath: String?,
    val memfdCreateStable: Boolean,
    val scopedStorageEnforced: Boolean,
    val artAbi: ArtAbi,
    val seccompFilter: SeccompStrictness,
    val rroScanRequired: Boolean,
) {
    ANDROID_7_1_2(
        androidLabel = "7.1.2",
        apiLevel = 25,
        binderfsPath = null,
        memfdCreateStable = false,
        scopedStorageEnforced = false,
        artAbi = ArtAbi.ART_25,
        seccompFilter = SeccompStrictness.LENIENT,
        rroScanRequired = false,
    ),
    ANDROID_10(
        androidLabel = "10",
        apiLevel = 29,
        binderfsPath = "/dev/binderfs/binder",
        memfdCreateStable = true,
        scopedStorageEnforced = true,
        artAbi = ArtAbi.ART_29,
        seccompFilter = SeccompStrictness.MODERATE,
        rroScanRequired = true,
    ),
    ANDROID_12(
        androidLabel = "12",
        apiLevel = 31,
        binderfsPath = "/dev/binderfs/binder",
        memfdCreateStable = true,
        scopedStorageEnforced = true,
        artAbi = ArtAbi.ART_31,
        seccompFilter = SeccompStrictness.STRICT,
        rroScanRequired = true,
    ),
    ;

    fun requiresBinderfs(): Boolean = binderfsPath != null

    enum class ArtAbi(val displayName: String) {
        ART_25("art-7.1.2"),
        ART_29("art-10"),
        ART_31("art-12"),
    }

    enum class SeccompStrictness(val description: String) {
        LENIENT("only `__NR_clone` namespace bits trapped"),
        MODERATE("Q-style filter; `mount`/`pivot_root` rejected"),
        STRICT("S-style filter; `mount` traps to SIGSYS, syscall ranges narrowed"),
    }

    companion object {
        fun forApiLevel(api: Int): GuestApiLevelProfile = when {
            api >= 31 -> ANDROID_12
            api >= 29 -> ANDROID_10
            else -> ANDROID_7_1_2
        }

        fun forVersionLabel(label: String): GuestApiLevelProfile? =
            entries.firstOrNull { it.androidLabel == label }
    }
}

/** Composite verdict for one API-level profile readiness gate. */
data class GuestProfileReadiness(
    val profile: GuestApiLevelProfile,
    val zygoteBootable: Boolean,
    val systemServerReachable: Boolean,
    val launcherReachable: Boolean,
) {
    val passed: Boolean = zygoteBootable && systemServerReachable && launcherReachable

    fun line(prefix: String): String =
        "$prefix passed=$passed zygote=${ok(zygoteBootable)} " +
            "system_server=${ok(systemServerReachable)} launcher=${ok(launcherReachable)}"

    private fun ok(value: Boolean): String = if (value) "ok" else "fail"
}

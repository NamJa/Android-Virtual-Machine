package dev.jongwoo.androidvm.vm

/**
 * Phase D.3 ART runtime bootstrap policy. The Phase C `ArtRuntimeChain` already lists the 11
 * libraries the zygote dlopens; D.3 introduces the additional libraries the dex executor needs
 * before the first `Activity.onCreate` can run, plus the dex2oat policy and the TLS swap
 * trampoline contract that prevents host bionic and guest libart from clobbering each other.
 */
object ArtRuntimeBootstrap {

    /** Libraries that must be present in addition to [ArtRuntimeChain.PRIMARY_LIBS]. */
    val DEX_EXECUTION_LIBS: List<String> = listOf(
        "libdexfile.so",
        "libprofile.so",
        "libartbase.so",
        "libopenjdkjvm.so",
        "libopenjdk.so",
    )

    /** Sum of zygote chain (Phase C) + dex execution libs (Phase D.3). */
    val FULL_RUNTIME_LIBS: List<String> = ArtRuntimeChain.PRIMARY_LIBS + DEX_EXECUTION_LIBS

    val EXPECTED_LIB_COUNT: Int get() = FULL_RUNTIME_LIBS.size

    /** Android 7.1.2 hard-pin per the Phase D.3 risk note. */
    const val PINNED_API_LEVEL: Int = 25

    /** dex2oat compiler-filter options, ordered weakest to strongest. */
    enum class Dex2OatPolicy(val cliFlag: String, val description: String) {
        DISABLED("--compiler-filter=skip", "Pure interpreter (Phase D.3 default)"),
        VERIFY("--compiler-filter=verify", "Verify only, no compile"),
        QUICKEN("--compiler-filter=quicken", "Verify + quickening (Phase D stable target)"),
        SPEED("--compiler-filter=speed", "Full AOT (Phase E candidate)"),
        ;

        companion object {
            /** Phase D.3 default per the doc — disable AOT until host CPU detection lands. */
            val PHASE_D_DEFAULT: Dex2OatPolicy = DISABLED

            fun fromCliFlag(value: String): Dex2OatPolicy? = entries.firstOrNull { it.cliFlag == value }
        }
    }

    fun toDex2OatArgs(policy: Dex2OatPolicy, dalvikCacheDir: String): List<String> = listOf(
        "dex2oat",
        policy.cliFlag,
        "--instruction-set=arm64",
        "--instruction-set-features=default",
        "--dalvik-cache=$dalvikCacheDir",
    )
}

/**
 * The TPIDR_EL0 / TLS swap trampoline contract. ART threads must enter the guest TLS block on
 * every native call so host bionic's pthread_setspecific table cannot be polluted with guest
 * indices. The trampoline must:
 *   - save TPIDR_EL0 on entry,
 *   - install the guest TLS block,
 *   - restore the host TLS on exit even on exception unwind.
 *
 * The Kotlin model serves as the test oracle the C++ trampoline must match.
 */
data class TlsSwapTrampoline(
    val installsGuestTls: Boolean,
    val restoresHostTlsOnReturn: Boolean,
    val restoresHostTlsOnException: Boolean,
    val protectsAllArtEntrypoints: Boolean,
) {
    val safe: Boolean
        get() = installsGuestTls && restoresHostTlsOnReturn &&
            restoresHostTlsOnException && protectsAllArtEntrypoints

    companion object {
        val PHASE_D_REQUIRED: TlsSwapTrampoline = TlsSwapTrampoline(
            installsGuestTls = true,
            restoresHostTlsOnReturn = true,
            restoresHostTlsOnException = true,
            protectsAllArtEntrypoints = true,
        )
    }
}

/**
 * Phase D.3 launch transaction. The host hands ActivityManager an `am start` style request and
 * the guest framework runs `ActivityThread.handleLaunchActivity` → `Activity.onCreate`. The
 * launch is considered passed when the activity reports `onCreate` and at least one frame
 * is delivered before the watchdog window fires.
 */
data class ActivityLaunchTransaction(
    val packageName: String,
    val activity: String,
    val onCreateInvoked: Boolean,
    val frameCount: Int,
    val crashCount: Int,
    val watchdogMillis: Long,
) {
    val passed: Boolean
        get() = onCreateInvoked && frameCount >= 1 && crashCount == 0
}

/**
 * Pure-JVM Phase D.3 oracle. The on-device receiver pipes real probe data into [observe]; the
 * unit tests pipe synthesized data and assert the gate semantics.
 */
class ArtRuntimeGate(
    private val tls: TlsSwapTrampoline = TlsSwapTrampoline.PHASE_D_REQUIRED,
    private val policy: ArtRuntimeBootstrap.Dex2OatPolicy = ArtRuntimeBootstrap.Dex2OatPolicy.PHASE_D_DEFAULT,
) {
    fun observe(libsLoaded: Int, transaction: ActivityLaunchTransaction): Verdict = Verdict(
        librariesPresent = libsLoaded >= ArtRuntimeBootstrap.EXPECTED_LIB_COUNT,
        tlsSwapSafe = tls.safe,
        dex2oatPolicySafe = policy != ArtRuntimeBootstrap.Dex2OatPolicy.SPEED,
        activityLaunched = transaction.passed,
        transaction = transaction,
    )

    data class Verdict(
        val librariesPresent: Boolean,
        val tlsSwapSafe: Boolean,
        val dex2oatPolicySafe: Boolean,
        val activityLaunched: Boolean,
        val transaction: ActivityLaunchTransaction,
    ) {
        val passed: Boolean
            get() = librariesPresent && tlsSwapSafe && dex2oatPolicySafe && activityLaunched
    }
}

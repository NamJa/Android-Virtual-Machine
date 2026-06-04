package dev.jongwoo.androidvm.vm

/**
 * Boot integration (guest-rom strategy §7) — selects how `VmInstanceService` boots
 * the guest:
 *  - [REAL]: map the ROM's real linker64 + drive the Option B bootstrap/gateway
 *    (graduated from the EP2 spikes). Requires a boot-ready ROM AND the feature
 *    flag, since the real-boot native path + a clean-room ROM are still landing.
 *  - [SIMULATED]: the labeled `runtime_mode=simulated` path (EP2.1) — the only
 *    working path today; never counted as a real boot by the product gate.
 *
 * Keeping the decision a pure function lets it be unit-tested independently of the
 * Android service / native runtime.
 */
enum class GuestBootMode { SIMULATED, REAL }

object GuestBootPolicy {
    /**
     * Master switch for the real Option B boot path. Stays false until the native
     * real-boot integration and a boot-ready clean-room ROM are both in place; once
     * true, a boot-ready ROM boots for real and an unready one still falls back to
     * simulated. Flipping this is the final step that lets G1 reach passed=true.
     */
    const val REAL_GUEST_BOOT_ENABLED = false

    fun select(
        bootReady: Boolean,
        realBootEnabled: Boolean = REAL_GUEST_BOOT_ENABLED,
    ): GuestBootMode =
        if (bootReady && realBootEnabled) GuestBootMode.REAL else GuestBootMode.SIMULATED
}

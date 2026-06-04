package dev.jongwoo.androidvm.vm

/**
 * EP2.1 — interprets the native `bootstrapStatus` string and decides whether it
 * represents a *real* guest-originated boot, as required by the product
 * `PRODUCT_RUNTIME_RESULT boot=` field.
 *
 * The current native boot path is simulated: it fabricates the boot markers and
 * tags itself `runtime_mode=simulated` (vm_native_bridge.cpp `phaseBGuestRuntimeEntrypoint`).
 * A real boot — delivered by EP2.2+ (real linker64 + zygote process) — carries
 * the same completion markers but WITHOUT the simulated tag.
 *
 * This is the canonical logic the product gate's `bootRealGuestOrigin()` will use
 * once a real boot exists; today every available boot is simulated, so it returns
 * false, keeping the gate honest (no canned pass).
 */
object GuestBootStatus {
    const val SIMULATED_MARKER = "runtime_mode=simulated"

    private val REAL_BOOT_MARKERS = listOf(
        "virtual_init=ok",
        "servicemanager=ok",
        "zygote_socket=accepting",
        "system_server=boot_completed",
        "surfaceflinger=first_frame",
        "boot_completed=1",
    )

    fun isSimulated(bootstrapStatus: String): Boolean =
        bootstrapStatus.contains(SIMULATED_MARKER)

    /** Boot completion markers present, regardless of whether real or simulated. */
    fun hasBootMarkers(bootstrapStatus: String): Boolean =
        REAL_BOOT_MARKERS.all { bootstrapStatus.contains(it) }

    /**
     * True only for a real guest-originated boot: all completion markers present
     * AND not tagged simulated. EP2.2+ flips this to true on real hardware.
     */
    fun isRealGuestBoot(bootstrapStatus: String): Boolean =
        hasBootMarkers(bootstrapStatus) && !isSimulated(bootstrapStatus)
}

package dev.jongwoo.androidvm.vm

/**
 * The Android 7.1.2 services that `system_server` publishes during boot. The Phase C.5 gate
 * checks that all of these are registered with the in-process service manager (see
 * `binder/service_manager.cpp`).
 *
 * Each entry's `name` matches the binder lookup key used by `IServiceManager::getService`.
 */
data class SystemService(
    val name: String,
    /** True when the service is part of the Phase C MVP critical path. */
    val critical: Boolean,
    /** True when the service depends on a Phase D bridge (so Phase C can stub it). */
    val requiresPhaseDBridge: Boolean,
)

object SystemServerServices {
    val ALL: List<SystemService> = listOf(
        SystemService("activity",            critical = true,  requiresPhaseDBridge = false),
        SystemService("package",             critical = true,  requiresPhaseDBridge = false),
        SystemService("window",              critical = true,  requiresPhaseDBridge = false),
        SystemService("input",               critical = true,  requiresPhaseDBridge = false),
        SystemService("power",               critical = true,  requiresPhaseDBridge = false),
        SystemService("display",             critical = true,  requiresPhaseDBridge = false),
        SystemService("surfaceflinger",      critical = true,  requiresPhaseDBridge = false),
        SystemService("audio",               critical = false, requiresPhaseDBridge = true),
        SystemService("media.audio_policy",  critical = false, requiresPhaseDBridge = true),
        SystemService("clipboard",           critical = false, requiresPhaseDBridge = true),
        SystemService("vibrator",            critical = false, requiresPhaseDBridge = true),
    )

    val CRITICAL_NAMES: List<String> = ALL.filter { it.critical }.map { it.name }
    val ALL_NAMES: List<String> = ALL.map { it.name }

    /**
     * Returns true when [registered] contains every critical service. The Phase C gate
     * accepts the receiver line as `passed=true` only if this returns true.
     */
    fun criticalsPresent(registered: Collection<String>): Boolean {
        val set = registered.toSet()
        return CRITICAL_NAMES.all { it in set }
    }

    /** Returns the missing service names, in declaration order. */
    fun missing(registered: Collection<String>): List<String> {
        val set = registered.toSet()
        return ALL_NAMES.filterNot { it in set }
    }
}

/**
 * Phase C.5's `STAGE_PHASE_C_SYSTEM_SERVER` line summary — the receiver fills it in from
 * the on-device service manager + property service.
 */
data class SystemServerBootSummary(
    val registeredServices: List<String>,
    val bootCompleted: Boolean,
) {
    val criticalsPresent: Boolean = SystemServerServices.criticalsPresent(registeredServices)
    val missing: List<String> = SystemServerServices.missing(registeredServices)
    val passed: Boolean = criticalsPresent && bootCompleted
}

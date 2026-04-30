package dev.jongwoo.androidvm.vm

/**
 * Phase E.8 32-bit / x86 guest translation. The doc keeps this optional and skipped by default;
 * the gate produces either a `skipped=true` line (build flag off) or a per-arch `passed=...`
 * line (build flag on).
 */
enum class TranslationArch(val wireName: String) {
    ARM32("arm32"),
    X86("x86"),
}

data class TranslationFeatureGate(
    val enabled: Boolean,
    val supportedArches: Set<TranslationArch>,
    val verifiedArches: Set<TranslationArch>,
    val skipReason: String,
) {
    val passed: Boolean
        get() = !enabled || verifiedArches == supportedArches

    fun line(): String {
        if (!enabled) {
            return "STAGE_PHASE_E_TRANSLATION skipped=true reason=$skipReason"
        }
        val arches = supportedArches.joinToString(",") { it.wireName }
        val passed = verifiedArches == supportedArches
        return "STAGE_PHASE_E_TRANSLATION passed=$passed arch=$arches binary_run=$passed"
    }

    /** Phase E.10 reports `translation=skipped` or `translation=true|false`. */
    fun gateValue(): String = when {
        !enabled -> "skipped"
        passed -> "true"
        else -> "false"
    }

    companion object {
        val PHASE_E_DEFAULT: TranslationFeatureGate = TranslationFeatureGate(
            enabled = false,
            supportedArches = emptySet(),
            verifiedArches = emptySet(),
            skipReason = "optional_disabled",
        )

        fun enabledFor(supported: Set<TranslationArch>): TranslationFeatureGate =
            TranslationFeatureGate(
                enabled = true,
                supportedArches = supported,
                verifiedArches = emptySet(),
                skipReason = "",
            )
    }
}

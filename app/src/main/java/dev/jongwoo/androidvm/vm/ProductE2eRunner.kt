package dev.jongwoo.androidvm.vm

/**
 * EP0.4 — release-equivalent end-to-end runner skeleton: ROM import -> VM boot ->
 * APK install -> APK launch, executed in dependency order. Each step is supplied
 * as an injectable executor so the orchestration is unit-testable on the JVM; the
 * Android adapter wires the real RomInstaller / VmManagerService / ApkInstallPipeline
 * executors and a [dev.jongwoo.androidvm.diag.FailureBundle] collector.
 *
 * Steps are strictly ordered: a step only runs if every prior step passed.
 * Once a step fails, the remainder are reported as failed (detail = "skipped")
 * rather than silently dropped — no synthetic success.
 */
enum class E2eStep(val wire: String) {
    ROM_IMPORT("rom_import"),
    VM_BOOT("vm_boot"),
    APK_INSTALL("apk_install"),
    APK_LAUNCH("apk_launch"),
}

data class E2eStepResult(
    val step: E2eStep,
    val passed: Boolean,
    val detail: String = "",
)

data class E2eReport(val results: List<E2eStepResult>) {
    val passed: Boolean = results.isNotEmpty() && results.all { it.passed }

    fun format(): String =
        "PRODUCT_E2E_RESULT passed=$passed " +
            results.joinToString(" ") { "${it.step.wire}=${it.passed}" }
}

class ProductE2eRunner(
    /** Ordered executors, one per step. Missing steps default to a failing executor. */
    private val executors: Map<E2eStep, () -> E2eStepResult>,
    private val emit: (String) -> Unit = {},
) {
    fun run(): E2eReport {
        val results = mutableListOf<E2eStepResult>()
        var aborted = false
        for (step in E2eStep.entries) {
            val result = when {
                aborted -> E2eStepResult(step, passed = false, detail = "skipped: prior step failed")
                else -> runCatching {
                    (executors[step] ?: { E2eStepResult(step, passed = false, detail = "no executor") })()
                }.getOrElse { t ->
                    E2eStepResult(step, passed = false, detail = "exception: ${t.message}")
                }
            }
            if (!result.passed) aborted = true
            results += result
        }
        val report = E2eReport(results)
        emit(report.format())
        return report
    }
}

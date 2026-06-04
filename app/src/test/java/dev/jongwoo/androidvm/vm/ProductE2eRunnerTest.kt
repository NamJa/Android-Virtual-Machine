package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductE2eRunnerTest {
    private fun pass(step: E2eStep) = step to { E2eStepResult(step, passed = true) }

    @Test
    fun allStepsPassYieldsPassingReport() {
        val report = ProductE2eRunner(
            executors = E2eStep.entries.associate { pass(it) },
        ).run()

        assertTrue(report.passed)
        assertEquals(4, report.results.size)
        assertTrue(report.format().startsWith("PRODUCT_E2E_RESULT passed=true"))
    }

    @Test
    fun failureShortCircuitsLaterSteps() {
        val report = ProductE2eRunner(
            executors = mapOf(
                pass(E2eStep.ROM_IMPORT),
                E2eStep.VM_BOOT to { E2eStepResult(E2eStep.VM_BOOT, passed = false, detail = "guest simulated") },
                pass(E2eStep.APK_INSTALL),
                pass(E2eStep.APK_LAUNCH),
            ),
        ).run()

        assertFalse(report.passed)
        assertTrue(report.results[0].passed) // rom_import ran
        assertFalse(report.results[1].passed) // vm_boot failed
        // install/launch were not executed despite passing executors.
        assertTrue(report.results[2].detail.contains("skipped"))
        assertTrue(report.results[3].detail.contains("skipped"))
    }

    @Test
    fun missingExecutorFailsClosed() {
        val report = ProductE2eRunner(executors = emptyMap()).run()
        assertFalse(report.passed)
        assertTrue(report.results.first().detail.contains("no executor"))
    }

    @Test
    fun throwingExecutorIsCapturedNotPropagated() {
        val report = ProductE2eRunner(
            executors = mapOf(
                E2eStep.ROM_IMPORT to { throw IllegalStateException("boom") },
            ),
        ).run()
        assertFalse(report.passed)
        assertTrue(report.results.first().detail.contains("exception"))
    }
}

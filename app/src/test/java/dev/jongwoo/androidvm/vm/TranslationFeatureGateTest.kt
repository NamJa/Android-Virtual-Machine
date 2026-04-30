package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationFeatureGateTest {
    @Test
    fun defaultIsSkippedAndPassesCoreGate() {
        val gate = TranslationFeatureGate.PHASE_E_DEFAULT
        assertFalse(gate.enabled)
        assertTrue("disabled translation must satisfy core gate", gate.passed)
        val line = gate.line()
        assertTrue(line.contains("STAGE_PHASE_E_TRANSLATION"))
        assertTrue(line.contains("skipped=true"))
        assertTrue(line.contains("reason=optional_disabled"))
        assertEquals("skipped", gate.gateValue())
    }

    @Test
    fun enabledRequiresAllSupportedArchesToPass() {
        val gate = TranslationFeatureGate.enabledFor(
            setOf(TranslationArch.ARM32, TranslationArch.X86),
        )
        assertFalse("nothing verified yet", gate.passed)
        assertEquals("false", gate.gateValue())
        val partial = gate.copy(verifiedArches = setOf(TranslationArch.ARM32))
        assertFalse(partial.passed)
        val full = gate.copy(verifiedArches = setOf(TranslationArch.ARM32, TranslationArch.X86))
        assertTrue(full.passed)
        assertEquals("true", full.gateValue())
        val line = full.line()
        assertTrue(line.contains("passed=true"))
        assertTrue(line.contains("arch=arm32,x86") || line.contains("arch=x86,arm32"))
        assertTrue(line.contains("binary_run=true"))
    }

    @Test
    fun gateValueDistinguishesAllThreeStates() {
        val skipped = TranslationFeatureGate.PHASE_E_DEFAULT
        val enabledIncomplete = TranslationFeatureGate.enabledFor(setOf(TranslationArch.ARM32))
        val enabledComplete = enabledIncomplete.copy(verifiedArches = setOf(TranslationArch.ARM32))
        assertEquals(setOf("skipped", "false", "true"), setOf(
            skipped.gateValue(), enabledIncomplete.gateValue(), enabledComplete.gateValue(),
        ))
    }
}

package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemServerServicesTest {
    @Test
    fun allDocumentedServicesPresent() {
        // Lock the doc § C.5.a service table.
        val expected = listOf(
            "activity", "package", "window", "input", "power", "display",
            "surfaceflinger", "audio", "media.audio_policy", "clipboard", "vibrator",
        )
        assertEquals(expected, SystemServerServices.ALL_NAMES)
    }

    @Test
    fun criticalsPresentRequiresAll7CriticalNames() {
        val critical = SystemServerServices.CRITICAL_NAMES
        assertEquals(7, critical.size)
        assertTrue(SystemServerServices.criticalsPresent(critical))
        assertFalse(SystemServerServices.criticalsPresent(critical - "activity"))
        assertFalse(SystemServerServices.criticalsPresent(emptyList()))
    }

    @Test
    fun missingReportsServicesNotYetRegistered() {
        val registered = listOf("activity", "package", "window", "input", "power", "display")
        val missing = SystemServerServices.missing(registered)
        assertTrue(missing.contains("surfaceflinger"))
        assertTrue(missing.contains("audio"))
        assertFalse(missing.contains("activity"))
    }

    @Test
    fun phaseDBridgeDependentServicesAreNonCritical() {
        // audio / clipboard / vibrator depend on Phase D bridges. Phase C must not block
        // on them.
        val deps = SystemServerServices.ALL.filter { it.requiresPhaseDBridge }
        assertTrue(deps.isNotEmpty())
        assertTrue(deps.all { !it.critical })
    }

    @Test
    fun bootSummaryPassesOnlyWhenCriticalsAndBootCompleted() {
        val ok = SystemServerBootSummary(
            registeredServices = SystemServerServices.ALL_NAMES,
            bootCompleted = true,
        )
        assertTrue(ok.passed)

        val missingService = SystemServerBootSummary(
            registeredServices = SystemServerServices.ALL_NAMES - "activity",
            bootCompleted = true,
        )
        assertFalse(missingService.passed)
        assertTrue("missing must surface activity", missingService.missing.contains("activity"))

        val notBooted = SystemServerBootSummary(
            registeredServices = SystemServerServices.ALL_NAMES,
            bootCompleted = false,
        )
        assertFalse(notBooted.passed)
    }
}

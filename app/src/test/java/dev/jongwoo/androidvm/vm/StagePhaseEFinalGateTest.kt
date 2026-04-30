package dev.jongwoo.androidvm.vm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePhaseEFinalGateTest {
    @Test
    fun resultLineCarriesAllPhaseEFields() {
        val line = StagePhaseEResultLine(
            multiInstance = true, snapshot = true, android10 = true, android12 = true,
            gles = true, virgl = true, venus = true, translation = "skipped",
            securityUpdate = true,
            stagePhaseA = true, stagePhaseB = true, stagePhaseC = true, stagePhaseD = true,
        ).format()
        listOf(
            "STAGE_PHASE_E_RESULT", "passed=true",
            "multi_instance=true", "snapshot=true",
            "android10=true", "android12=true",
            "gles=true", "virgl=true", "venus=true",
            "translation=skipped",
            "security_update=true",
            "stage_phase_a=true", "stage_phase_b=true",
            "stage_phase_c=true", "stage_phase_d=true",
        ).forEach { fragment ->
            assertTrue("missing fragment '$fragment' in $line", line.contains(fragment))
        }
    }

    @Test
    fun translationTrueAlsoPasses() {
        val line = StagePhaseEResultLine(
            multiInstance = true, snapshot = true, android10 = true, android12 = true,
            gles = true, virgl = true, venus = true, translation = "true",
            securityUpdate = true,
            stagePhaseA = true, stagePhaseB = true, stagePhaseC = true, stagePhaseD = true,
        )
        assertTrue(line.passed)
        assertTrue(line.format().contains("translation=true"))
    }

    @Test
    fun translationFalseFailsCoreGate() {
        val line = StagePhaseEResultLine(
            multiInstance = true, snapshot = true, android10 = true, android12 = true,
            gles = true, virgl = true, venus = true, translation = "false",
            securityUpdate = true,
            stagePhaseA = true, stagePhaseB = true, stagePhaseC = true, stagePhaseD = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("translation=false"))
    }

    @Test
    fun anyFalseFieldFlipsPassedFalse() {
        val line = StagePhaseEResultLine(
            multiInstance = true, snapshot = false, android10 = true, android12 = true,
            gles = true, virgl = true, venus = true, translation = "skipped",
            securityUpdate = true,
            stagePhaseA = true, stagePhaseB = true, stagePhaseC = true, stagePhaseD = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("snapshot=false"))
        assertTrue(line.format().contains("passed=false"))
    }

    @Test
    fun harnessProducesPassingResultWhenAllProbesGreen() {
        val emitted = mutableListOf<String>()
        val r = StagePhaseEDiagnostics(
            multiInstanceProbe = { true }, snapshotProbe = { true },
            android10Probe = { true }, android12Probe = { true },
            glesProbe = { true }, virglProbe = { true }, venusProbe = { true },
            translationProbe = { "skipped" },
            securityUpdateProbe = { true },
            phaseAProbe = { true }, phaseBProbe = { true },
            phaseCProbe = { true }, phaseDProbe = { true },
            emit = { emitted += it },
        ).run()
        assertTrue("expected passing harness: ${r.format()}", r.passed)
        val expectedLabels = listOf(
            "STAGE_PHASE_E_MULTI_INSTANCE",
            "STAGE_PHASE_E_SNAPSHOT",
            "STAGE_PHASE_E_ANDROID10",
            "STAGE_PHASE_E_ANDROID12",
            "STAGE_PHASE_E_GLES",
            "STAGE_PHASE_E_VIRGL",
            "STAGE_PHASE_E_VENUS",
            "STAGE_PHASE_E_TRANSLATION",
            "STAGE_PHASE_E_SECURITY_UPDATE",
            "STAGE_PHASE_E_STAGE_PHASE_A",
            "STAGE_PHASE_E_STAGE_PHASE_B",
            "STAGE_PHASE_E_STAGE_PHASE_C",
            "STAGE_PHASE_E_STAGE_PHASE_D",
            "STAGE_PHASE_E_RESULT",
        )
        assertEquals(expectedLabels, emitted.map { it.substringBefore(' ') })
        // The translation line must announce skipped+optional_disabled when default.
        val translationLine = emitted.first { it.startsWith("STAGE_PHASE_E_TRANSLATION") }
        assertTrue(translationLine.contains("skipped=true"))
        assertTrue(translationLine.contains("reason=optional_disabled"))
    }

    @Test
    fun harnessFailsWhenSecurityUpdateRegresses() {
        val r = StagePhaseEDiagnostics(
            multiInstanceProbe = { true }, snapshotProbe = { true },
            android10Probe = { true }, android12Probe = { true },
            glesProbe = { true }, virglProbe = { true }, venusProbe = { true },
            translationProbe = { "skipped" },
            securityUpdateProbe = { false },
            phaseAProbe = { true }, phaseBProbe = { true },
            phaseCProbe = { true }, phaseDProbe = { true },
        ).run()
        assertFalse(r.passed)
        assertFalse(r.securityUpdate)
    }

    @Test
    fun harnessFailsWhenPriorPhaseRegresses() {
        listOf(
            StagePhaseEDiagnostics(
                multiInstanceProbe = { true }, snapshotProbe = { true },
                android10Probe = { true }, android12Probe = { true },
                glesProbe = { true }, virglProbe = { true }, venusProbe = { true },
                translationProbe = { "skipped" }, securityUpdateProbe = { true },
                phaseAProbe = { false }, phaseBProbe = { true },
                phaseCProbe = { true }, phaseDProbe = { true },
            ),
            StagePhaseEDiagnostics(
                multiInstanceProbe = { true }, snapshotProbe = { true },
                android10Probe = { true }, android12Probe = { true },
                glesProbe = { true }, virglProbe = { true }, venusProbe = { true },
                translationProbe = { "skipped" }, securityUpdateProbe = { true },
                phaseAProbe = { true }, phaseBProbe = { false },
                phaseCProbe = { true }, phaseDProbe = { true },
            ),
            StagePhaseEDiagnostics(
                multiInstanceProbe = { true }, snapshotProbe = { true },
                android10Probe = { true }, android12Probe = { true },
                glesProbe = { true }, virglProbe = { true }, venusProbe = { true },
                translationProbe = { "skipped" }, securityUpdateProbe = { true },
                phaseAProbe = { true }, phaseBProbe = { true },
                phaseCProbe = { true }, phaseDProbe = { false },
            ),
        ).forEach { harness ->
            val r = harness.run()
            assertFalse("expected fail: ${r.format()}", r.passed)
        }
    }

    @Test
    fun phaseEReceiverIsDeclaredInDebugManifest() {
        val manifest = projectFile("app/src/debug/AndroidManifest.xml")
        assertNotNull("debug manifest missing", manifest)
        val text = manifest!!.readText()
        assertTrue(text.contains("StagePhaseEDiagnosticsReceiver"))
        assertTrue(text.contains("dev.jongwoo.androidvm.debug.RUN_PHASE_E_DIAGNOSTICS"))
    }

    @Test
    fun phaseEReceiverReplaysPhaseDAndDoesNotHardcodeRegressionsFalse() {
        val receiver = projectFile("app/src/debug/java/dev/jongwoo/androidvm/debug/StagePhaseEDiagnosticsReceiver.kt")
        assertNotNull("Phase E receiver missing", receiver)
        val text = receiver!!.readText()
        assertTrue(text.contains("StagePhaseDDiagnosticsReceiver().runDiagnostics(context)"))
        assertFalse(text.contains("phaseAProbe = { false }"))
        assertFalse(text.contains("phaseBProbe = { false }"))
        assertFalse(text.contains("phaseCProbe = { false }"))
        assertFalse(text.contains("phaseDProbe = { false }"))
        assertFalse(text.contains("phaseESnapshotContractProbe(): Boolean = false"))
        assertFalse(text.contains("&& false"))
    }

    private fun projectFile(relativePath: String): File? {
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        )
        return candidates.firstOrNull { it.exists() }
    }
}

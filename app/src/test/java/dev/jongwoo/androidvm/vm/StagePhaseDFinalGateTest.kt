package dev.jongwoo.androidvm.vm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePhaseDFinalGateTest {
    @Test
    fun resultLineCarriesAllPhaseDFields() {
        val line = StagePhaseDResultLine(
            pms = true, launcher = true, appRun = true, bridges = true,
            camera = true, mic = true, network = true, file = true, ops = true,
            stagePhaseA = true, stagePhaseB = true, stagePhaseC = true,
        ).format()
        listOf(
            "STAGE_PHASE_D_RESULT", "passed=true",
            "pms=true", "launcher=true", "app_run=true", "bridges=true",
            "camera=true", "mic=true", "network=true", "file=true", "ops=true",
            "stage_phase_a=true", "stage_phase_b=true", "stage_phase_c=true",
        ).forEach { fragment ->
            assertTrue("missing fragment '$fragment' in $line", line.contains(fragment))
        }
    }

    @Test
    fun anyFalseFieldFlipsPassedFalse() {
        val line = StagePhaseDResultLine(
            pms = true, launcher = true, appRun = false, bridges = true,
            camera = true, mic = true, network = true, file = true, ops = true,
            stagePhaseA = true, stagePhaseB = true, stagePhaseC = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("app_run=false"))
        assertTrue(line.format().contains("passed=false"))
    }

    @Test
    fun harnessProducesPassingResultWhenAllProbesGreen() {
        val emitted = mutableListOf<String>()
        val r = StagePhaseDDiagnostics(
            pmsProbe = { true }, launcherProbe = { true }, appRunProbe = { true },
            bridgeProbe = { true }, cameraProbe = { true }, micProbe = { true },
            networkProbe = { true }, fileProbe = { true }, opsProbe = { true },
            phaseAProbe = { true }, phaseBProbe = { true }, phaseCProbe = { true },
            emit = { emitted += it },
        ).run()
        assertTrue("expected passing harness: ${r.format()}", r.passed)
        val expectedLabels = listOf(
            "STAGE_PHASE_D_PMS",
            "STAGE_PHASE_D_LAUNCHER",
            "STAGE_PHASE_D_APP_RUN",
            "STAGE_PHASE_D_BRIDGE",
            "STAGE_PHASE_D_CAMERA",
            "STAGE_PHASE_D_MIC",
            "STAGE_PHASE_D_NETWORK_ISOLATION",
            "STAGE_PHASE_D_FILE",
            "STAGE_PHASE_D_OPS",
            "STAGE_PHASE_D_STAGE_PHASE_A",
            "STAGE_PHASE_D_STAGE_PHASE_B",
            "STAGE_PHASE_D_STAGE_PHASE_C",
            "STAGE_PHASE_D_RESULT",
        )
        assertEquals(expectedLabels, emitted.map { it.substringBefore(' ') })
    }

    @Test
    fun harnessReportsPmsFailureWhenProbeReturnsFalse() {
        val r = StagePhaseDDiagnostics(
            pmsProbe = { false }, launcherProbe = { true }, appRunProbe = { true },
            bridgeProbe = { true }, cameraProbe = { true }, micProbe = { true },
            networkProbe = { true }, fileProbe = { true }, opsProbe = { true },
            phaseAProbe = { true }, phaseBProbe = { true }, phaseCProbe = { true },
        ).run()
        assertFalse(r.pms)
        assertFalse(r.passed)
    }

    @Test
    fun harnessReportsCameraAndMicFailureSeparately() {
        val r = StagePhaseDDiagnostics(
            pmsProbe = { true }, launcherProbe = { true }, appRunProbe = { true },
            bridgeProbe = { true }, cameraProbe = { false }, micProbe = { false },
            networkProbe = { true }, fileProbe = { true }, opsProbe = { true },
            phaseAProbe = { true }, phaseBProbe = { true }, phaseCProbe = { true },
        ).run()
        assertFalse(r.camera)
        assertFalse(r.mic)
        assertFalse(r.passed)
    }

    @Test
    fun harnessFailsWhenPriorPhaseRegresses() {
        StagePhaseDDiagnostics(
            pmsProbe = { true }, launcherProbe = { true }, appRunProbe = { true },
            bridgeProbe = { true }, cameraProbe = { true }, micProbe = { true },
            networkProbe = { true }, fileProbe = { true }, opsProbe = { true },
            phaseAProbe = { false }, phaseBProbe = { true }, phaseCProbe = { true },
        ).run().also {
            assertFalse(it.passed)
            assertFalse(it.stagePhaseA)
        }
        StagePhaseDDiagnostics(
            pmsProbe = { true }, launcherProbe = { true }, appRunProbe = { true },
            bridgeProbe = { true }, cameraProbe = { true }, micProbe = { true },
            networkProbe = { true }, fileProbe = { true }, opsProbe = { true },
            phaseAProbe = { true }, phaseBProbe = { false }, phaseCProbe = { true },
        ).run().also {
            assertFalse(it.passed)
            assertFalse(it.stagePhaseB)
        }
        StagePhaseDDiagnostics(
            pmsProbe = { true }, launcherProbe = { true }, appRunProbe = { true },
            bridgeProbe = { true }, cameraProbe = { true }, micProbe = { true },
            networkProbe = { true }, fileProbe = { true }, opsProbe = { true },
            phaseAProbe = { true }, phaseBProbe = { true }, phaseCProbe = { false },
        ).run().also {
            assertFalse(it.passed)
            assertFalse(it.stagePhaseC)
        }
    }

    @Test
    fun phaseDReceiverIsDeclaredInDebugManifest() {
        val manifest = projectFile("app/src/debug/AndroidManifest.xml")
        assertNotNull("debug manifest missing", manifest)
        val text = manifest!!.readText()
        assertTrue(text.contains("StagePhaseDDiagnosticsReceiver"))
        assertTrue(text.contains("dev.jongwoo.androidvm.debug.RUN_PHASE_D_DIAGNOSTICS"))
    }

    @Test
    fun manifestDeclaresPhaseDPermissions() {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        assertNotNull("AndroidManifest.xml missing", manifest)
        val text = manifest!!.readText()
        listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
            "android.permission.INTERNET",
        ).forEach { permission ->
            assertTrue("$permission missing from manifest", text.contains(permission))
        }
        assertTrue("Phase D VPN service missing", text.contains("android.permission.BIND_VPN_SERVICE"))
        assertTrue("Phase D VPN service action missing", text.contains("android.net.VpnService"))
    }

    @Test
    fun phaseDReceiverUsesDeviceBackedProbes() {
        val receiver = projectFile(
            "app/src/debug/java/dev/jongwoo/androidvm/debug/StagePhaseDDiagnosticsReceiver.kt",
        )
        assertNotNull("Phase D receiver missing", receiver)
        val text = receiver!!.readText()
        assertTrue(text.contains("PhaseCNativeBridge.bootProbe"))
        assertTrue(text.contains("PhaseCNativeBridge.binderProbe"))
        assertTrue(text.contains("NativePhaseDPmsClient"))
        assertTrue(text.contains("phaseDPerfProbe"))
        assertFalse(text.contains("FakeGuestPmsClient"))
        assertFalse(text.contains("phaseDLauncherProbe(): Boolean = false"))
        assertFalse(text.contains("phaseDBridgesProbe(): Boolean = false"))
        assertFalse(text.contains("phaseDCameraProbe(): Boolean = false"))
        assertFalse(text.contains("phaseDMicProbe(): Boolean = false"))
        assertFalse(text.contains("phaseDFileProbe(): Boolean = false"))
        assertFalse(text.contains("onCreateInvoked = false"))
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

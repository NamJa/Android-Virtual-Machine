package dev.jongwoo.androidvm.vm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePhaseCFinalGateTest {
    @Test
    fun resultLineCarriesAllPhaseCFields() {
        val line = StagePhaseCResultLine(
            binder = true, ashmem = true, property = true,
            zygote = true, systemServer = true, surfaceflinger = true,
            stagePhaseA = true, stagePhaseB = true,
        ).format()
        listOf(
            "STAGE_PHASE_C_RESULT", "passed=true",
            "binder=true", "ashmem=true", "property=true",
            "zygote=true", "system_server=true", "surfaceflinger=true",
            "stage_phase_a=true", "stage_phase_b=true",
        ).forEach { fragment ->
            assertTrue("missing fragment '$fragment' in $line", line.contains(fragment))
        }
    }

    @Test
    fun anyFalseFieldFlipsPassedFalse() {
        val line = StagePhaseCResultLine(
            binder = true, ashmem = true, property = true,
            zygote = false, systemServer = true, surfaceflinger = true,
            stagePhaseA = true, stagePhaseB = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("passed=false"))
        assertTrue(line.format().contains("zygote=false"))
    }

    @Test
    fun harnessProducesPassingResultWhenBootProbeIsReady() {
        val emitted = mutableListOf<String>()
        val r = StagePhaseCDiagnostics(
            bootProbe = {
                StagePhaseCBootProbe(
                    zygoteAccepting = true,
                    libsLoaded = ArtRuntimeChain.EXPECTED_COUNT,
                    bootCompleted = true,
                    registeredServices = SystemServerServices.ALL_NAMES,
                    firstFrameDelivered = true,
                    firstFrameMillis = 4321,
                    reason = "ok",
                )
            },
            phaseAProbe = { true },
            phaseBProbe = { true },
            emit = { emitted += it },
        ).run()
        assertTrue("phase C harness should pass with ready inputs: ${r.format()}", r.passed)
        val expectedLabels = listOf(
            "STAGE_PHASE_C_BINDER",
            "STAGE_PHASE_C_ASHMEM",
            "STAGE_PHASE_C_PROPERTY",
            "STAGE_PHASE_C_ZYGOTE",
            "STAGE_PHASE_C_SYSTEM_SERVER",
            "STAGE_PHASE_C_SURFACEFLINGER",
            "STAGE_PHASE_C_STAGE_PHASE_A",
            "STAGE_PHASE_C_STAGE_PHASE_B",
            "STAGE_PHASE_C_RESULT",
        )
        assertEquals(expectedLabels, emitted.map { it.substringBefore(' ') })
    }

    @Test
    fun harnessReportsZygoteFailureWhenSocketNotAccepting() {
        val r = StagePhaseCDiagnostics(
            bootProbe = {
                StagePhaseCBootProbe(
                    zygoteAccepting = false,
                    libsLoaded = ArtRuntimeChain.EXPECTED_COUNT,
                    bootCompleted = true,
                    registeredServices = SystemServerServices.ALL_NAMES,
                    firstFrameDelivered = true,
                    firstFrameMillis = 100,
                )
            },
            phaseAProbe = { true },
            phaseBProbe = { true },
        ).run()
        assertFalse(r.zygote)
        assertFalse(r.passed)
        assertTrue(r.binder); assertTrue(r.ashmem); assertTrue(r.property)
    }

    @Test
    fun harnessReportsSystemServerFailureWhenCriticalServiceMissing() {
        val r = StagePhaseCDiagnostics(
            bootProbe = {
                StagePhaseCBootProbe(
                    zygoteAccepting = true,
                    libsLoaded = ArtRuntimeChain.EXPECTED_COUNT,
                    bootCompleted = true,
                    registeredServices = SystemServerServices.ALL_NAMES - "activity",
                    firstFrameDelivered = true,
                    firstFrameMillis = 100,
                )
            },
            phaseAProbe = { true },
            phaseBProbe = { true },
        ).run()
        assertFalse(r.systemServer)
        assertFalse(r.passed)
    }

    @Test
    fun harnessReportsSurfaceFlingerFailureWhenFirstFrameMissing() {
        val r = StagePhaseCDiagnostics(
            bootProbe = {
                StagePhaseCBootProbe(
                    zygoteAccepting = true,
                    libsLoaded = ArtRuntimeChain.EXPECTED_COUNT,
                    bootCompleted = true,
                    registeredServices = SystemServerServices.ALL_NAMES,
                    firstFrameDelivered = false,
                    firstFrameMillis = -1,
                )
            },
            phaseAProbe = { true },
            phaseBProbe = { true },
        ).run()
        assertFalse(r.surfaceflinger)
        assertFalse(r.passed)
    }

    @Test
    fun harnessFailsWhenPhaseARegresses() {
        val r = StagePhaseCDiagnostics(
            bootProbe = {
                StagePhaseCBootProbe(
                    zygoteAccepting = true,
                    libsLoaded = ArtRuntimeChain.EXPECTED_COUNT,
                    bootCompleted = true,
                    registeredServices = SystemServerServices.ALL_NAMES,
                    firstFrameDelivered = true,
                    firstFrameMillis = 100,
                )
            },
            phaseAProbe = { false },
            phaseBProbe = { true },
        ).run()
        assertFalse(r.passed)
        assertFalse(r.stagePhaseA)
    }

    @Test
    fun harnessFailsWhenPhaseBRegresses() {
        val r = StagePhaseCDiagnostics(
            bootProbe = {
                StagePhaseCBootProbe(
                    zygoteAccepting = true,
                    libsLoaded = ArtRuntimeChain.EXPECTED_COUNT,
                    bootCompleted = true,
                    registeredServices = SystemServerServices.ALL_NAMES,
                    firstFrameDelivered = true,
                    firstFrameMillis = 100,
                )
            },
            phaseAProbe = { true },
            phaseBProbe = { false },
        ).run()
        assertFalse(r.passed)
        assertFalse(r.stagePhaseB)
    }

    @Test
    fun cmakeSourceListIncludesPhaseCSources() {
        val cmake = projectFile("app/src/main/cpp/CMakeLists.txt")
        assertNotNull("CMakeLists.txt missing", cmake)
        val text = cmake!!.readText()
        listOf(
            "binder/parcel.cpp", "binder/binder_device.cpp",
            "binder/transaction.cpp", "binder/thread_pool.cpp",
            "binder/service_manager.cpp",
            "device/ashmem.cpp", "device/gralloc.cpp", "device/composer.cpp",
            "property/property_area.cpp", "property/build_props.cpp",
            "syscall/socket.cpp",
        ).forEach { fragment ->
            assertTrue("CMakeLists.txt missing $fragment", text.contains(fragment))
        }
    }

    @Test
    fun phaseCReceiverIsDeclaredInDebugManifest() {
        val manifest = projectFile("app/src/debug/AndroidManifest.xml")
        assertNotNull("debug manifest missing", manifest)
        val text = manifest!!.readText()
        assertTrue(text.contains("StagePhaseCDiagnosticsReceiver"))
        assertTrue(text.contains("dev.jongwoo.androidvm.debug.RUN_PHASE_C_DIAGNOSTICS"))
    }

    @Test
    fun phaseCReceiverUsesRealRegressionAndBootProbes() {
        val receiver = projectFile(
            "app/src/debug/java/dev/jongwoo/androidvm/debug/StagePhaseCDiagnosticsReceiver.kt",
        )
        assertNotNull("Phase C receiver missing", receiver)
        val text = receiver!!.readText()
        assertTrue(text.contains("PhaseDiagnosticProbes.runPhaseBDiagnostics"))
        assertTrue(text.contains("PhaseDiagnosticProbes.verifyCrossProcessState"))
        assertTrue(text.contains("PhaseCNativeBridge.bootProbe"))
        assertFalse(text.contains("crossProcessStateProbe = { true }"))
        assertFalse(text.contains("boot_probe_pending_phase_b3_on_device"))
        assertFalse(text.contains("executed = false"))
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

package dev.jongwoo.androidvm.vm

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pin the Phase B final gate. The line format and the per-step ordering are part of the
 * regression contract — change them only by updating both this test and the receiver.
 */
class StagePhaseBFinalGateTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun resultLineCarriesAllPhaseBFields() {
        val line = StagePhaseBResultLine(
            modular = true, elf = true, linker = true, syscall = true,
            lifecycle = true, binary = true, stagePhaseA = true,
        ).format()
        listOf(
            "STAGE_PHASE_B_RESULT", "passed=true",
            "modular=true", "elf=true", "linker=true",
            "syscall=true", "lifecycle=true", "binary=true",
            "stage_phase_a=true",
        ).forEach { fragment ->
            assertTrue("missing fragment '$fragment' in $line", line.contains(fragment))
        }
    }

    @Test
    fun anyFalseFieldFlipsPassedFalse() {
        val line = StagePhaseBResultLine(
            modular = true, elf = true, linker = false, syscall = true,
            lifecycle = true, binary = true, stagePhaseA = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("passed=false"))
        assertTrue(line.format().contains("linker=false"))
    }

    @Test
    fun harnessProducesPassingResultWhenSourceTreeAndBinaryProbeAreReady() {
        val cppRoot = locateCppRoot()
        assertNotNull("cpp source tree missing", cppRoot)
        val emitted = mutableListOf<String>()
        val diag = StagePhaseBDiagnostics(
            sourceDirectoryRoots = listOf(cppRoot!!),
            binaryProbe = {
                StagePhaseBBinaryProbe(
                    executed = true,
                    reason = "ok",
                    binaryPath = "/system/bin/avm-hello",
                    exitCode = 0,
                    stdout = "hello\n",
                    libcInit = true,
                    syscallRoundTrip = true,
                )
            },
            phaseAProbe = { true },
            emit = { emitted += it },
        )
        val r = diag.run()
        assertTrue("phase B harness should pass with ready inputs: ${r.format()}", r.passed)
        val expectedLabels = listOf(
            "STAGE_PHASE_B_MODULARIZED",
            "STAGE_PHASE_B_ELF",
            "STAGE_PHASE_B_LINKER",
            "STAGE_PHASE_B_SYSCALL",
            "STAGE_PHASE_B_LIFECYCLE",
            "STAGE_PHASE_B_BINARY",
            "STAGE_PHASE_B_STAGE_PHASE_A",
            "STAGE_PHASE_B_RESULT",
        )
        assertEquals(expectedLabels, emitted.map { it.substringBefore(' ') })
    }

    @Test
    fun harnessReportsBinaryFailureHonestlyWhenFixtureMissing() {
        val cppRoot = locateCppRoot()!!
        val r = StagePhaseBDiagnostics(
            sourceDirectoryRoots = listOf(cppRoot),
            binaryProbe = { StagePhaseBBinaryProbe(executed = false, reason = "fixture_missing") },
            phaseAProbe = { true },
        ).run()
        assertFalse(r.binary)
        assertFalse(r.passed)
        // Linker is intentionally tied to the real binary probe because the Phase B doc's
        // linker gate is "libc_init=ok stdout=ok".
        assertTrue(r.modular); assertTrue(r.elf); assertFalse(r.linker)
        assertTrue(r.syscall); assertTrue(r.lifecycle); assertTrue(r.stagePhaseA)
    }

    @Test
    fun harnessReportsPhaseAFailureHonestly() {
        val cppRoot = locateCppRoot()!!
        val r = StagePhaseBDiagnostics(
            sourceDirectoryRoots = listOf(cppRoot),
            binaryProbe = {
                StagePhaseBBinaryProbe(
                    executed = true,
                    reason = "ok",
                    binaryPath = "/system/bin/avm-hello",
                    exitCode = 0,
                    stdout = "hello\n",
                    libcInit = true,
                    syscallRoundTrip = true,
                )
            },
            phaseAProbe = { false },
        ).run()
        assertFalse(r.stagePhaseA)
        assertFalse(r.passed)
    }

    @Test
    fun cmakeSourceListMatchesPhaseBExpectedLayout() {
        val cmake = projectFile("app/src/main/cpp/CMakeLists.txt")
        assertNotNull("CMakeLists.txt missing", cmake)
        val text = cmake!!.readText()
        listOf(
            "core/logging.cpp", "core/instance.cpp", "core/event_loop.cpp",
            "vfs/path_resolver.cpp", "vfs/fd_table.cpp",
            "property/property_service.cpp",
            "binder/service_manager.cpp",
            "device/graphics_device.cpp", "device/audio_device.cpp", "device/input_device.cpp",
            "loader/elf_loader.cpp", "loader/aux_vector.cpp",
            "loader/linker_bridge.cpp", "loader/guest_process.cpp",
            "syscall/syscall_dispatch.cpp", "syscall/futex.cpp", "syscall/signal.cpp",
            "syscall/process.cpp", "syscall/io.cpp", "syscall/mem.cpp", "syscall/time.cpp",
            "jni/vm_native_bridge.cpp", "jni/phase_b_bridge.cpp",
        ).forEach { fragment ->
            assertTrue("CMakeLists.txt missing $fragment", text.contains(fragment))
        }
    }

    @Test
    fun phaseBReceiverIsDeclaredInDebugManifest() {
        val manifest = projectFile("app/src/debug/AndroidManifest.xml")
        assertNotNull("debug manifest missing", manifest)
        val text = manifest!!.readText()
        assertTrue(text.contains("StagePhaseBDiagnosticsReceiver"))
        assertTrue(text.contains("dev.jongwoo.androidvm.debug.RUN_PHASE_B_DIAGNOSTICS"))
    }

    private fun locateCppRoot(): File? {
        val candidates = listOf(
            File("app/src/main/cpp"),
            File("../app/src/main/cpp"),
            File("../../app/src/main/cpp"),
            File("src/main/cpp"),
        )
        return candidates.firstOrNull { it.isDirectory }
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

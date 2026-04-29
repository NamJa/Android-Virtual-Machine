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
 * Locks down the Phase A final gate output. Adding a new Phase A step means extending
 * [StagePhaseAResultLine] *and* this test in the same change.
 */
class StagePhaseAFinalGateTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun resultLineContainsAllPhaseAFinalGateFields() {
        val line = StagePhaseAResultLine(
            vmManager = true,
            multiInstanceReady = true,
            ipc = true,
            ci = true,
            stage4 = true,
            stage5 = true,
            stage6 = true,
            stage7 = true,
        ).format()

        listOf(
            "STAGE_PHASE_A_RESULT",
            "passed=true",
            "vm_manager=true",
            "multi_instance_ready=true",
            "ipc=true",
            "ci=true",
            "stage4=true",
            "stage5=true",
            "stage6=true",
            "stage7=true",
        ).forEach { fragment ->
            assertTrue("missing fragment: $fragment in $line", line.contains(fragment))
        }
    }

    @Test
    fun anyFalseFieldFlipsPassedFalse() {
        val line = StagePhaseAResultLine(
            vmManager = true,
            multiInstanceReady = true,
            ipc = false,
            ci = true,
            stage4 = true,
            stage5 = true,
            stage6 = true,
            stage7 = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("passed=false"))
        assertTrue(line.format().contains("ipc=false"))
    }

    @Test
    fun harnessProducesPassingResultOnFreshWorkspace() {
        val workspace = tempDir("phase-a")
        val emitted = mutableListOf<String>()
        val diagnostics = StagePhaseADiagnostics(
            workspaceRoot = workspace,
            manifestText = readSourceManifest(),
            regressionProbe = {
                StagePhaseAStageRegressionResult(
                    stage4 = true, stage5 = true, stage6 = true, stage7 = true,
                )
            },
            crossProcessStateProbe = { true },
            emit = { emitted += it },
        )
        val result = diagnostics.run()
        assertTrue("Phase A final gate failed: ${result.format()}", result.passed)
        val expectedLabels = listOf(
            "STAGE_PHASE_A_VM_MANAGER",
            "STAGE_PHASE_A_MULTI_INSTANCE_READY",
            "STAGE_PHASE_A_IPC",
            "STAGE_PHASE_A_CI",
            "STAGE_PHASE_A_STAGE4",
            "STAGE_PHASE_A_STAGE5",
            "STAGE_PHASE_A_STAGE6",
            "STAGE_PHASE_A_STAGE7",
            "STAGE_PHASE_A_RESULT",
        )
        assertEquals(expectedLabels, emitted.map { it.substringBefore(' ') })
    }

    @Test
    fun harnessFailsWhenForbiddenPermissionPresent() {
        val diagnostics = StagePhaseADiagnostics(
            workspaceRoot = tempDir("phase-a-forbidden"),
            manifestText = "<manifest>android.permission.READ_PHONE_STATE</manifest>",
            regressionProbe = {
                StagePhaseAStageRegressionResult(
                    stage4 = true, stage5 = true, stage6 = true, stage7 = true,
                )
            },
            crossProcessStateProbe = { true },
        )
        val result = diagnostics.run()
        assertFalse(result.ci)
        assertFalse(result.passed)
    }

    @Test
    fun harnessFailsWhenAnyStageRegresses() {
        val diagnostics = StagePhaseADiagnostics(
            workspaceRoot = tempDir("phase-a-stage-regress"),
            manifestText = readSourceManifest(),
            regressionProbe = {
                StagePhaseAStageRegressionResult(
                    stage4 = true, stage5 = false, stage6 = true, stage7 = true,
                )
            },
            crossProcessStateProbe = { true },
        )
        val result = diagnostics.run()
        assertFalse(result.passed)
        assertFalse(result.format().contains("stage5=true"))
    }

    @Test
    fun harnessFailsWhenCrossProcessIpcProbeFails() {
        val diagnostics = StagePhaseADiagnostics(
            workspaceRoot = tempDir("phase-a-ipc-regress"),
            manifestText = readSourceManifest(),
            regressionProbe = {
                StagePhaseAStageRegressionResult(
                    stage4 = true, stage5 = true, stage6 = true, stage7 = true,
                )
            },
            crossProcessStateProbe = { false },
        )

        val result = diagnostics.run()

        assertFalse(result.ipc)
        assertFalse(result.passed)
    }

    @Test
    fun ciWorkflowFilePinsCanonicalGate() {
        val workflow = projectFile(".github/workflows/ci.yml")
        assertNotNull("CI workflow missing — Phase A.4 gate broken", workflow)
        val text = workflow!!.readText()
        // Every gradle task that the canonical gate runs must be wired into CI.
        listOf(
            ":app:testDebugUnitTest",
            ":app:assembleDebug",
            ":app:lintDebug",
            ":app:assembleRelease",
            "ManifestPermissionGuardTest",
        ).forEach { fragment ->
            assertTrue("CI workflow missing $fragment", text.contains(fragment))
        }
        // Workflow must trigger on push to main and on PRs.
        assertTrue(text.contains("branches: [main]") || text.contains("branches:\n      - main"))
        assertTrue(text.contains("pull_request"))
    }

    @Test
    fun phaseADiagnosticsReceiverIsDeclaredInDebugManifest() {
        val manifest = projectFile("app/src/debug/AndroidManifest.xml")
        assertNotNull("debug manifest missing", manifest)
        val text = manifest!!.readText()
        assertTrue(text.contains("StagePhaseADiagnosticsReceiver"))
        assertTrue(text.contains("dev.jongwoo.androidvm.debug.RUN_PHASE_A_DIAGNOSTICS"))
    }

    private fun readSourceManifest(): String {
        val manifest = projectFile("app/src/main/AndroidManifest.xml")
        assertNotNull("AndroidManifest.xml not found", manifest)
        return manifest!!.readText()
    }

    private fun projectFile(relativePath: String): File? {
        // Tests run with cwd set to either project root or `app/`. Try both.
        val candidates = listOf(
            File(relativePath),
            File("../$relativePath"),
            File("../../$relativePath"),
        )
        return candidates.firstOrNull { it.exists() }
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

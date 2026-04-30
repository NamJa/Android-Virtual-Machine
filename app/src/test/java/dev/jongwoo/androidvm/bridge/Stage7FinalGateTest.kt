package dev.jongwoo.androidvm.bridge

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage7FinalGateTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun stage7ResultLineContainsAllFinalGateFields() {
        val line = Stage7ResultLine(
            manifest = true,
            policy = true,
            broker = true,
            audit = true,
            dispatcher = true,
            ui = true,
            deviceProfile = true,
            output = true,
            clipboard = true,
            location = true,
            unsupportedMedia = true,
            regressions = true,
        ).format()

        listOf(
            "STAGE7_RESULT",
            "passed=true",
            "manifest=true",
            "policy=true",
            "broker=true",
            "audit=true",
            "dispatcher=true",
            "ui=true",
            "deviceProfile=true",
            "output=true",
            "clipboard=true",
            "location=true",
            "unsupportedMedia=true",
            "regressions=true",
        ).forEach { fragment ->
            assertTrue("missing fragment: $fragment in $line", line.contains(fragment))
        }
    }

    @Test
    fun anyFalseFieldFlipsPassedFalse() {
        val line = Stage7ResultLine(
            manifest = true,
            policy = true,
            broker = false,
            audit = true,
            dispatcher = true,
            ui = true,
            deviceProfile = true,
            output = true,
            clipboard = true,
            location = true,
            unsupportedMedia = true,
            regressions = true,
        )
        assertFalse(line.passed)
        assertTrue(line.format().contains("passed=false"))
    }

    @Test
    fun stage7DiagnosticsHarnessProducesPassingResultOnFreshWorkspace() {
        val workspace = tempDir("stage7")
        val emitted = mutableListOf<String>()
        val diagnostics = Stage7Diagnostics(
            workspaceRoot = workspace,
            manifestText = readSourceManifest(),
            regressionProbe = { Stage7RegressionResult(stage4 = true, stage5 = true, stage6 = true) },
            emit = { emitted += it },
        )

        val result = diagnostics.run()

        assertTrue("Final gate failed: ${result.format()}", result.passed)
        // Make sure each stage subreport line was emitted in the documented order.
        val expectedLabels = listOf(
            "STAGE7_MANIFEST_RESULT",
            "STAGE7_POLICY_RESULT",
            "STAGE7_BROKER_RESULT",
            "STAGE7_AUDIT_RESULT",
            "STAGE7_DISPATCHER_RESULT",
            "STAGE7_UI_RESULT",
            "STAGE7_DEVICE_PROFILE_RESULT",
            "STAGE7_OUTPUT_RESULT",
            "STAGE7_CLIPBOARD_RESULT",
            "STAGE7_LOCATION_RESULT",
            "STAGE7_UNSUPPORTED_MEDIA_RESULT",
            "STAGE7_REGRESSION_RESULT",
            "STAGE7_RESULT",
        )
        val emittedLabels = emitted.map { it.substringBefore(' ') }
        assertEquals(expectedLabels, emittedLabels)
        // Final regression line includes per-stage tags.
        val regressionLine = emitted.first { it.startsWith("STAGE7_REGRESSION_RESULT") }
        assertTrue(regressionLine.contains("stage4=true"))
        assertTrue(regressionLine.contains("stage5=true"))
        assertTrue(regressionLine.contains("stage6=true"))
    }

    @Test
    fun stage7DiagnosticsManifestCheckFailsWhenForbiddenPermissionPresent() {
        val workspace = tempDir("stage7-forbidden")
        val diagnostics = Stage7Diagnostics(
            workspaceRoot = workspace,
            manifestText = "<manifest>android.permission.READ_PHONE_STATE</manifest>",
            regressionProbe = { Stage7RegressionResult(stage4 = true, stage5 = true, stage6 = true) },
        )

        val result = diagnostics.run()

        assertFalse(result.manifest)
        assertFalse(result.passed)
    }

    @Test
    fun stage7DiagnosticsManifestCheckAcceptsCameraPermissionAfterPhaseD() {
        // Phase D ships the camera bridge so Stage 7 no longer rejects CAMERA in the manifest.
        val diagnostics = Stage7Diagnostics(
            workspaceRoot = tempDir("stage7-camera"),
            manifestText = "<manifest>android.permission.CAMERA</manifest>",
            regressionProbe = { Stage7RegressionResult(stage4 = true, stage5 = true, stage6 = true) },
        )
        assertTrue(diagnostics.run().manifest)
    }

    @Test
    fun stage7DiagnosticsFailsWhenRegressionProbeFails() {
        val diagnostics = Stage7Diagnostics(
            workspaceRoot = tempDir("stage7-regression-fail"),
            manifestText = readSourceManifest(),
            regressionProbe = { Stage7RegressionResult(stage4 = true, stage5 = false, stage6 = true) },
        )

        val result = diagnostics.run()

        assertFalse(result.regressions)
        assertFalse(result.passed)
    }

    private fun readSourceManifest(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
            File("../app/src/main/AndroidManifest.xml"),
        )
        val manifest = candidates.firstOrNull { it.exists() }
        assertNotNull(
            "AndroidManifest.xml not found from working directory ${File(".").absolutePath}",
            manifest,
        )
        return manifest!!.readText()
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}

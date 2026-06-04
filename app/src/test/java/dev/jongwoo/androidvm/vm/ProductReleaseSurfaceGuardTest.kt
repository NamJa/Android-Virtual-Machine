package dev.jongwoo.androidvm.vm

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductReleaseSurfaceGuardTest {
    @Test
    fun mainManifestDoesNotExposeDebugDiagnosticReceivers() {
        val mainManifest = projectFile("app/src/main/AndroidManifest.xml")
        assertNotNull("main manifest missing", mainManifest)
        val text = mainManifest!!.readText()

        listOf(
            "Stage4DiagnosticsReceiver",
            "Stage5DiagnosticsReceiver",
            "Stage6DiagnosticsReceiver",
            "Stage7DiagnosticsReceiver",
            "StagePhaseADiagnosticsReceiver",
            "StagePhaseBDiagnosticsReceiver",
            "StagePhaseCDiagnosticsReceiver",
            "StagePhaseDDiagnosticsReceiver",
            "StagePhaseEDiagnosticsReceiver",
        ).forEach { receiver ->
            assertFalse("debug receiver leaked into main manifest: $receiver", text.contains(receiver))
        }
    }

    @Test
    fun fixedMediaSourcesStayOutOfMainSourceSet() {
        val mainBridgeDir = projectFile("app/src/main/java/dev/jongwoo/androidvm/bridge")
        assertNotNull("main bridge source directory missing", mainBridgeDir)
        val mainText = mainBridgeDir!!.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }

        assertFalse(mainText.contains("class FixedCameraSource"))
        assertFalse(mainText.contains("class FixedPcmSource"))

        val debugFixture = projectFile("app/src/debug/java/dev/jongwoo/androidvm/bridge/BridgeDebugFixtures.kt")
        assertNotNull("debug bridge fixtures missing", debugFixture)
        val debugText = debugFixture!!.readText()
        assertTrue(debugText.contains("class FixedCameraSource"))
        assertTrue(debugText.contains("class FixedPcmSource"))
    }

    @Test
    fun productManifestCarriesGateReceiverButNoDebugReceivers() {
        val productManifest = projectFile("app/src/product/AndroidManifest.xml")
        assertNotNull("product manifest missing", productManifest)
        val text = productManifest!!.readText()

        // The product (release-equivalent) variant carries the product gate trigger...
        assertTrue(
            "product gate receiver missing from product manifest",
            text.contains("ProductGateReceiver"),
        )
        // ...but never the debug-only Stage/Phase diagnostic receivers.
        listOf(
            "Stage4DiagnosticsReceiver",
            "Stage5DiagnosticsReceiver",
            "Stage6DiagnosticsReceiver",
            "Stage7DiagnosticsReceiver",
            "StagePhaseADiagnosticsReceiver",
            "StagePhaseBDiagnosticsReceiver",
            "StagePhaseCDiagnosticsReceiver",
            "StagePhaseDDiagnosticsReceiver",
            "StagePhaseEDiagnosticsReceiver",
        ).forEach { receiver ->
            assertFalse("debug receiver leaked into product manifest: $receiver", text.contains(receiver))
        }
    }

    @Test
    fun fixedMediaSourcesStayOutOfProductSourceSet() {
        val productDir = projectFile("app/src/product/java")
        // Product source set may be small; only assert when present.
        if (productDir != null && productDir.exists()) {
            val productText = productDir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .joinToString("\n") { it.readText() }
            assertFalse(productText.contains("class FixedCameraSource"))
            assertFalse(productText.contains("class FixedPcmSource"))
        }
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

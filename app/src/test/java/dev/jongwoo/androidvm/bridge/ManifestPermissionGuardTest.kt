package dev.jongwoo.androidvm.bridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class ManifestPermissionGuardTest {
    @Test
    fun manifestDoesNotDeclareForbiddenPermissions() {
        val manifest = readManifest()
        Stage7BridgeScope.forbiddenManifestPermissions.forEach { permission ->
            assertFalse(
                "Forbidden permission must not be declared: $permission",
                manifest.contains(permission),
            )
        }
    }

    @Test
    fun cameraAndMicrophonePermissionsAreNotDeclaredForStage7Mvp() {
        val manifest = readManifest()
        listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
        ).forEach { permission ->
            assertFalse(
                "Stage 07 MVP must not declare $permission until media bridge ships",
                manifest.contains(permission),
            )
        }
    }

    @Test
    fun stage7BridgeScopeFlagsCameraAndMicrophoneAsUnsupportedMvp() {
        assertFalse(Stage7BridgeScope.isSupported(BridgeType.CAMERA))
        assertFalse(Stage7BridgeScope.isSupported(BridgeType.MICROPHONE))
    }

    private fun readManifest(): String {
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
}

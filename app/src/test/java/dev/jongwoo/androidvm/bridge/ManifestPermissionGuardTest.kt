package dev.jongwoo.androidvm.bridge

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
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
    fun phaseDDeclaresCameraAndMicrophonePermissions() {
        val manifest = readManifest()
        listOf(
            "android.permission.CAMERA",
            "android.permission.RECORD_AUDIO",
        ).forEach { permission ->
            assertTrue(
                "Phase D must declare $permission for the media bridges",
                manifest.contains(permission),
            )
        }
    }

    @Test
    fun stage7BridgeScopeFlagsCameraAndMicrophoneAsSupportedAfterPhaseD() {
        assertTrue(Stage7BridgeScope.isSupported(BridgeType.CAMERA))
        assertTrue(Stage7BridgeScope.isSupported(BridgeType.MICROPHONE))
    }

    @Test
    fun phaseDDefaultPolicyKeepsCameraAndMicrophoneOff() {
        // Even though they are now SUPPORTED bridges, the per-instance default policy must keep
        // them dark until the user explicitly enables them.
        val cam = DefaultBridgePolicies.forBridge(BridgeType.CAMERA)
        val mic = DefaultBridgePolicies.forBridge(BridgeType.MICROPHONE)
        assertFalse(cam.enabled)
        assertFalse(mic.enabled)
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

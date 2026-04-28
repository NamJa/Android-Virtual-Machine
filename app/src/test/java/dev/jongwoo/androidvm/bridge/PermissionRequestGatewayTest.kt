package dev.jongwoo.androidvm.bridge

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PermissionRequestGatewayTest {

    @Test
    fun recordingGatewayCapturesRequestAndReturnsConfiguredResult() = runTest {
        val gateway = RecordingPermissionGateway()
        gateway.nextResult = true
        val reason = PermissionReason(
            bridge = BridgeType.LOCATION,
            operation = "request_current_location",
            permission = "android.permission.ACCESS_FINE_LOCATION",
            userMessage = "Location bridge needs your location to use real GPS.",
        )

        val granted = gateway.request(reason.permission, reason)

        assertTrue(granted)
        assertEquals(listOf(reason), gateway.requests)
    }

    @Test
    fun recordingGatewayRejectsMismatchedPermission() = runTest {
        val gateway = RecordingPermissionGateway()
        val reason = PermissionReason(
            bridge = BridgeType.CAMERA,
            operation = "open",
            permission = "android.permission.CAMERA",
            userMessage = "Camera bridge",
        )

        try {
            gateway.request("android.permission.RECORD_AUDIO", reason)
            fail("Mismatched permission must throw")
        } catch (expected: IllegalStateException) {
            assertTrue(
                "Error message should reference both permissions",
                expected.message?.contains("permission") == true,
            )
        }
        assertTrue(gateway.requests.isEmpty())
    }

    @Test
    fun denyAllGatewayNeverGrantsAndReturnsFalse() = runTest {
        val gateway: PermissionRequestGateway = DenyAllPermissionGateway()
        val reason = PermissionReason(
            bridge = BridgeType.MICROPHONE,
            operation = "open",
            permission = "android.permission.RECORD_AUDIO",
            userMessage = "Microphone bridge",
        )

        val granted = gateway.request(reason.permission, reason)

        assertFalse(granted)
    }

    @Test
    fun resetClearsRecordedRequestsAndResultFlag() = runTest {
        val gateway = RecordingPermissionGateway()
        gateway.nextResult = true
        val reason = PermissionReason(
            BridgeType.LOCATION,
            "fixed",
            "android.permission.ACCESS_FINE_LOCATION",
            "msg",
        )
        gateway.request(reason.permission, reason)

        gateway.reset()

        assertEquals(emptyList<PermissionReason>(), gateway.requests)
        assertFalse(gateway.nextResult)
    }

    @Test
    fun recordingGatewayKeepsRequestsAcrossMultipleCallers() = runTest {
        val gateway = RecordingPermissionGateway()
        gateway.nextResult = false
        val r1 = PermissionReason(BridgeType.CAMERA, "open", "android.permission.CAMERA", "c")
        val r2 = PermissionReason(BridgeType.MICROPHONE, "open", "android.permission.RECORD_AUDIO", "m")

        gateway.request(r1.permission, r1)
        gateway.request(r2.permission, r2)

        assertEquals(2, gateway.requests.size)
        assertSame(r1, gateway.requests[0])
        assertSame(r2, gateway.requests[1])
    }
}

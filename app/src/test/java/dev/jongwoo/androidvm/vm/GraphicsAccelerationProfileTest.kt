package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsAccelerationProfileTest {
    @Test
    fun readyCapabilityHasMinFramesAndFps() {
        val cap = GraphicsModeCapability.ready(
            GraphicsAccelerationMode.GLES_PASSTHROUGH,
            frameCount = 600,
            fpsAvg = 60,
            gpuName = "Adreno 740",
        )
        assertTrue(cap.ready)
        val line = cap.line("STAGE_PHASE_E_GLES")
        assertTrue(line.contains("passed=true"))
        assertTrue(line.contains("frame_count_ge=600"))
        assertTrue(line.contains("fps_avg_ge=60"))
        assertTrue(line.contains("gpu_name=Adreno 740"))
    }

    @Test
    fun belowMinimaIsNotReadyEvenWhenSupported() {
        val cap = GraphicsModeCapability.ready(
            GraphicsAccelerationMode.VIRGL,
            frameCount = 100,
            fpsAvg = 22,
            gpuName = "Mali",
        )
        assertFalse(cap.ready)
        assertTrue(cap.line("STAGE_PHASE_E_VIRGL").contains("passed=false"))
    }

    @Test
    fun unsupportedCapabilitySurfacesDegradationReason() {
        val cap = GraphicsModeCapability.unsupported(
            GraphicsAccelerationMode.VENUS,
            "host_no_vulkan_driver",
        )
        val line = cap.line("STAGE_PHASE_E_VENUS")
        assertTrue(line.contains("skipped=true"))
        assertTrue(line.contains("reason=host_no_vulkan_driver"))
    }

    @Test
    fun matrixGateOkAcceptsReadyOrGracefullyDegraded() {
        val matrix = GraphicsAccelerationMatrix(
            gles = GraphicsModeCapability.ready(
                GraphicsAccelerationMode.GLES_PASSTHROUGH, 320, 30, "host"),
            virgl = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.VIRGL, "no_virgl"),
            venus = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.VENUS, "no_vulkan"),
        )
        assertTrue(matrix.gateOk())
    }

    @Test
    fun matrixGateRejectsBrokenSupportedMode() {
        val matrix = GraphicsAccelerationMatrix(
            gles = GraphicsModeCapability.ready(
                GraphicsAccelerationMode.GLES_PASSTHROUGH, 100, 10, "host"),
            virgl = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.VIRGL, "no_virgl"),
            venus = GraphicsModeCapability.unsupported(
                GraphicsAccelerationMode.VENUS, "no_vulkan"),
        )
        assertFalse("gles is supported but below threshold → fail", matrix.gateOk())
    }

    @Test
    fun unprobedMatrixHasGracefulDegradation() {
        val matrix = GraphicsAccelerationMatrix.unprobed()
        assertTrue(matrix.gateOk())
        assertTrue(matrix.toJson().getJSONObject("gles").getBoolean("supportedByHost") == false)
    }

    @Test
    fun fromWireNameRoundTrip() {
        GraphicsAccelerationMode.entries.forEach { mode ->
            assertNotNull(GraphicsAccelerationMode.fromWireName(mode.wireName))
        }
        assertEquals(null, GraphicsAccelerationMode.fromWireName("unknown"))
    }
}

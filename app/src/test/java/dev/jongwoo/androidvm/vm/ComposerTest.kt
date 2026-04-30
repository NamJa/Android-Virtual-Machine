package dev.jongwoo.androidvm.vm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicBufferAllocatorTest {
    @Test
    fun allocateRGBA8888BufferComputesSizeCorrectly() {
        val a = GraphicBufferAllocator()
        val gb = a.allocate(720, 1280, GraphicPixelFormat.RGBA_8888)
        assertNotNull(gb)
        assertEquals(720 * 1280 * 4L, gb!!.sizeBytes)
        assertEquals(720, gb.width)
        assertEquals(720, gb.stride)
    }

    @Test
    fun rgb565IsTwoBytesPerPixel() {
        val a = GraphicBufferAllocator()
        val gb = a.allocate(640, 480, GraphicPixelFormat.RGB_565)
        assertEquals(640 * 480 * 2L, gb!!.sizeBytes)
    }

    @Test
    fun unknownFormatRejected() {
        val a = GraphicBufferAllocator()
        assertNull(a.allocate(100, 100, GraphicPixelFormat.UNKNOWN))
    }

    @Test
    fun zeroDimensionsRejected() {
        val a = GraphicBufferAllocator()
        assertNull(a.allocate(0, 100, GraphicPixelFormat.RGBA_8888))
        assertNull(a.allocate(100, 0, GraphicPixelFormat.RGBA_8888))
        assertNull(a.allocate(-1, 100, GraphicPixelFormat.RGBA_8888))
    }

    @Test
    fun freeRemovesBufferFromAllocator() {
        val a = GraphicBufferAllocator()
        val gb = a.allocate(100, 100, GraphicPixelFormat.RGBA_8888)!!
        assertEquals(1, a.allocatedCount)
        assertTrue(a.free(gb.handle))
        assertEquals(0, a.allocatedCount)
        assertNull(a.get(gb.handle))
    }
}

class ComposerTest {
    @Test
    fun createDisplayAssignsId() {
        val a = GraphicBufferAllocator()
        val c = Composer(a)
        val id = c.createDisplay(DisplayAttributes(720, 1280))
        assertTrue("display id must be positive", id > 0)
        assertNotNull(c.getDisplayAttributes(id))
    }

    @Test
    fun zeroDimensionDisplayRejected() {
        val a = GraphicBufferAllocator()
        val c = Composer(a)
        assertEquals(0, c.createDisplay(DisplayAttributes(0, 1280)))
    }

    @Test
    fun presentEmptyDisplayFails() {
        val a = GraphicBufferAllocator()
        val c = Composer(a)
        val id = c.createDisplay(DisplayAttributes(720, 1280))
        val r = c.presentDisplay(id)
        assertFalse(r.ok)
        assertEquals("no_layers", r.failureReason)
    }

    @Test
    fun presentMissingBufferReportsStructuredFailure() {
        val a = GraphicBufferAllocator()
        val c = Composer(a)
        val id = c.createDisplay(DisplayAttributes(720, 1280))
        c.setLayers(id, listOf(ComposerLayer(graphicBufferHandle = 999, z = 0)))
        val r = c.presentDisplay(id)
        assertFalse(r.ok)
        assertEquals("missing_buffer", r.failureReason)
    }

    @Test
    fun presentPicksTopmostLayer() {
        val a = GraphicBufferAllocator()
        val gb1 = a.allocate(720, 1280, GraphicPixelFormat.RGBA_8888)!!
        val gb2 = a.allocate(720, 1280, GraphicPixelFormat.RGBA_8888)!!
        val c = Composer(a)
        val id = c.createDisplay(DisplayAttributes(720, 1280))
        c.setLayers(id, listOf(
            ComposerLayer(gb1.handle, z = 0),
            ComposerLayer(gb2.handle, z = 5),  // topmost
        ))
        val r = c.presentDisplay(id)
        assertTrue(r.ok)
        assertEquals(gb2.handle, r.presentedHandle)
        assertEquals(1, r.frameSequence)
    }

    @Test
    fun presentIncrementsFrameSequence() {
        val a = GraphicBufferAllocator()
        val gb = a.allocate(720, 1280, GraphicPixelFormat.RGBA_8888)!!
        val c = Composer(a)
        val id = c.createDisplay(DisplayAttributes(720, 1280))
        c.setLayers(id, listOf(ComposerLayer(gb.handle, z = 0)))
        c.presentDisplay(id)
        c.presentDisplay(id)
        c.presentDisplay(id)
        assertEquals(3, c.frameCount)
    }

    @Test
    fun pixelFormatWireValuesMatchAndroidConstants() {
        // Lock the values — these are the Android `HAL_PIXEL_FORMAT_*` enum.
        assertEquals(1, GraphicPixelFormat.RGBA_8888.wireValue)
        assertEquals(4, GraphicPixelFormat.RGB_565.wireValue)
        assertEquals(5, GraphicPixelFormat.BGRA_8888.wireValue)
        assertEquals(4, GraphicPixelFormat.RGBA_8888.bytesPerPixel)
        assertEquals(2, GraphicPixelFormat.RGB_565.bytesPerPixel)
    }
}

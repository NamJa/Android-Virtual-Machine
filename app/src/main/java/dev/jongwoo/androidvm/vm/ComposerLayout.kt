package dev.jongwoo.androidvm.vm

/**
 * Pure-Kotlin twin of `app/src/main/cpp/device/{gralloc,composer}.cpp`. The native
 * allocator owns memory; this Kotlin oracle only verifies the calculation rules
 * (bytes-per-pixel, top-z layer pick) so on-device behavior is pinned by JVM tests.
 */
enum class GraphicPixelFormat(val wireValue: Int, val bytesPerPixel: Int) {
    UNKNOWN(0, 0),
    RGBA_8888(1, 4),
    RGBX_8888(2, 4),
    BGRA_8888(5, 4),
    RGB_565(4, 2),
}

data class GraphicBufferDescriptor(
    val handle: Int,
    val width: Int,
    val height: Int,
    val stride: Int,
    val format: GraphicPixelFormat,
    val sizeBytes: Long,
)

class GraphicBufferAllocator {
    private var nextHandle = 1
    private val buffers = mutableMapOf<Int, GraphicBufferDescriptor>()

    fun allocate(width: Int, height: Int, format: GraphicPixelFormat): GraphicBufferDescriptor? {
        if (width <= 0 || height <= 0 || format.bytesPerPixel == 0) return null
        val handle = nextHandle++
        val sizeBytes = width.toLong() * height * format.bytesPerPixel
        val gb = GraphicBufferDescriptor(
            handle = handle,
            width = width,
            height = height,
            stride = width,
            format = format,
            sizeBytes = sizeBytes,
        )
        buffers[handle] = gb
        return gb
    }

    fun free(handle: Int): Boolean = buffers.remove(handle) != null
    fun get(handle: Int): GraphicBufferDescriptor? = buffers[handle]
    val allocatedCount: Int get() = buffers.size
}

data class DisplayAttributes(
    val width: Int,
    val height: Int,
    val densityDpi: Int = 320,
)

data class ComposerLayer(
    val graphicBufferHandle: Int,
    val z: Int,
    val srcLeft: Int = 0, val srcTop: Int = 0, val srcRight: Int = 0, val srcBottom: Int = 0,
    val dstLeft: Int = 0, val dstTop: Int = 0, val dstRight: Int = 0, val dstBottom: Int = 0,
)

class Composer(private val allocator: GraphicBufferAllocator) {
    private var nextDisplayId = 1
    private var frameSequence = 0
    private data class DisplayState(
        var attrs: DisplayAttributes,
        var layers: List<ComposerLayer> = emptyList(),
    )
    private val displays = mutableMapOf<Int, DisplayState>()

    fun createDisplay(attrs: DisplayAttributes): Int {
        if (attrs.width <= 0 || attrs.height <= 0) return 0
        val id = nextDisplayId++
        displays[id] = DisplayState(attrs)
        return id
    }

    fun destroyDisplay(displayId: Int): Boolean = displays.remove(displayId) != null

    fun getDisplayAttributes(displayId: Int): DisplayAttributes? = displays[displayId]?.attrs

    fun setLayers(displayId: Int, layers: List<ComposerLayer>): Boolean {
        val s = displays[displayId] ?: return false
        s.layers = layers
        return true
    }

    data class PresentResult(
        val ok: Boolean,
        val failureReason: String,
        val presentedHandle: Int = 0,
        val frameSequence: Int = 0,
    )

    fun presentDisplay(displayId: Int): PresentResult {
        val s = displays[displayId]
            ?: return PresentResult(false, "unknown_display")
        if (s.layers.isEmpty()) return PresentResult(false, "no_layers")
        val top = s.layers.maxBy { it.z }
        if (allocator.get(top.graphicBufferHandle) == null) {
            return PresentResult(false, "missing_buffer")
        }
        frameSequence++
        return PresentResult(
            ok = true,
            failureReason = "",
            presentedHandle = top.graphicBufferHandle,
            frameSequence = frameSequence,
        )
    }

    val frameCount: Int get() = frameSequence
}

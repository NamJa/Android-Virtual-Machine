package dev.jongwoo.androidvm.bridge

/** Debug/test-only camera source used by diagnostic receivers and JVM tests. */
class FixedCameraSource(
    private val frame: CameraFrame? = CameraFrame(
        width = 640,
        height = 480,
        timestampNanos = 0L,
        ySize = 640 * 480,
        uSize = 640 * 480 / 4,
        vSize = 640 * 480 / 4,
    ),
) : CameraXFrameSource {
    private var emitted: Long = 0L

    override suspend fun nextFrame(): CameraFrame? {
        if (frame != null) emitted += 1
        return frame
    }

    override fun pushedFrames(): Long = emitted
}

/** Debug/test-only PCM source used by diagnostic receivers and JVM tests. */
class FixedPcmSource(
    private val data: ShortArray,
    override val sampleRateHz: Int = 48_000,
    override val channelCount: Int = 1,
) : AudioInputSource {
    private var offset = 0

    override fun read(buffer: ShortArray): Int {
        if (offset >= data.size) return -1
        val n = minOf(buffer.size, data.size - offset)
        System.arraycopy(data, offset, buffer, 0, n)
        offset += n
        return n
    }
}

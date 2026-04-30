package dev.jongwoo.androidvm.bridge

import org.json.JSONObject

/** Phase D.6 PCM input source. Production wraps `AudioRecord`, tests pass canned PCM. */
interface AudioInputSource {
    fun read(buffer: ShortArray): Int
    val sampleRateHz: Int
    val channelCount: Int
}

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

/**
 * Pure-JVM linear-interpolation downsampler. Used by the microphone bridge to convert host's
 * 48 kHz / 44.1 kHz capture rate into the guest's expected rate (commonly 16 kHz for STT).
 */
object SampleRateConverter {
    fun resample(input: ShortArray, fromHz: Int, toHz: Int): ShortArray {
        require(fromHz > 0 && toHz > 0) { "rates must be positive" }
        if (fromHz == toHz || input.isEmpty()) return input.copyOf()
        val outSize = (input.size.toLong() * toHz / fromHz).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        for (i in 0 until outSize) {
            val srcPos = i.toDouble() * (input.size - 1) / (outSize - 1).coerceAtLeast(1)
            val low = srcPos.toInt().coerceIn(0, input.size - 1)
            val high = (low + 1).coerceAtMost(input.size - 1)
            val frac = srcPos - low
            val sample = input[low] * (1.0 - frac) + input[high] * frac
            out[i] = sample.toInt().toShort()
        }
        return out
    }
}

/**
 * Phase D.6 microphone bridge. ENABLED + first read triggers `RECORD_AUDIO` request via the
 * permission gateway. OFF / UNSUPPORTED modes never touch the gateway or the host AudioRecord.
 */
class MicrophoneBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val permissionGateway: PermissionRequestGateway,
    private val audioInputSource: AudioInputSource,
    private val guestSampleRateHz: Int = DEFAULT_GUEST_SAMPLE_RATE,
) : BridgeHandler {
    override val bridge: BridgeType = BridgeType.MICROPHONE

    @Volatile
    var lastReadFrames: Int = 0
        private set

    override suspend fun handle(request: BridgeRequest): BridgeResponse {
        require(request.bridge == BridgeType.MICROPHONE) {
            "MicrophoneBridge handles MICROPHONE only"
        }
        val policy = policyStore.load().getValue(BridgeType.MICROPHONE)
        val response = when {
            !policy.enabled || policy.mode == BridgeMode.OFF ->
                BridgeResponse(BridgeResult.UNAVAILABLE, "microphone_disabled")
            policy.mode == BridgeMode.UNSUPPORTED ->
                BridgeResponse(BridgeResult.UNSUPPORTED, "microphone_unsupported")
            policy.mode != BridgeMode.ENABLED ->
                BridgeResponse(BridgeResult.UNSUPPORTED, "microphone_mode_unsupported")
            else -> {
                val granted = permissionGateway.request(
                    permission = REQUIRED_PERMISSION,
                    reason = request.reason.copy(permission = REQUIRED_PERMISSION),
                )
                if (!granted) {
                    BridgeResponse(BridgeResult.DENIED, "microphone_permission_denied")
                } else {
                    val frameCount = request.parsedPayload().optInt("frames", DEFAULT_FRAME_COUNT)
                    val raw = ShortArray(frameCount)
                    val read = audioInputSource.read(raw)
                    if (read <= 0) {
                        BridgeResponse(BridgeResult.UNAVAILABLE, "microphone_no_frames")
                    } else {
                        val captured = if (read == raw.size) raw else raw.copyOf(read)
                        val resampled = SampleRateConverter.resample(
                            captured,
                            audioInputSource.sampleRateHz,
                            guestSampleRateHz,
                        )
                        lastReadFrames = resampled.size
                        val payload = JSONObject()
                            .put("hostFrames", read)
                            .put("guestFrames", resampled.size)
                            .put("sampleRateIn", audioInputSource.sampleRateHz)
                            .put("sampleRateOut", guestSampleRateHz)
                            .toString()
                        BridgeResponse(BridgeResult.ALLOWED, "microphone_frames_delivered", payload)
                    }
                }
            }
        }
        auditLog.appendDecision(
            instanceId = request.instanceId,
            bridge = BridgeType.MICROPHONE,
            operation = request.operation,
            decision = BridgeDecision(
                allowed = response.result == BridgeResult.ALLOWED,
                result = response.result,
                reason = response.reason,
            ),
        )
        return response
    }

    companion object {
        const val REQUIRED_PERMISSION = "android.permission.RECORD_AUDIO"
        const val DEFAULT_GUEST_SAMPLE_RATE = 16_000
        const val DEFAULT_FRAME_COUNT = 1_024
    }
}

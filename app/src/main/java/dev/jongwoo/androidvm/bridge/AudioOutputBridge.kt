package dev.jongwoo.androidvm.bridge

interface AudioSink {
    fun write(pcm: ShortArray): Int

    /**
     * Phase D.4: report whether the most recent write underran. The default returns false so
     * existing callers and stub sinks continue to work without modification.
     */
    fun lastWriteUnderran(): Boolean = false
}

class NoopAudioSink : AudioSink {
    override fun write(pcm: ShortArray): Int = pcm.size
}

/**
 * Policy gate in front of the host AudioTrack / AAudio output. When the policy is off or muted
 * we never call into [audioSink], guaranteeing host audio cannot leak guest audio.
 *
 * Phase D.4: tracks underruns reported by [AudioSink.lastWriteUnderran] in [xrunCounter] so the
 * bridge diagnostic line can surface audio glitches.
 */
class AudioOutputBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val audioSink: AudioSink = NoopAudioSink(),
    val xrunCounter: AudioXrunCounter = AudioXrunCounter(),
) {
    fun writePcm(instanceId: String, pcm: ShortArray, muted: Boolean = false): BridgeDecision {
        val policy = policyStore.load().getValue(BridgeType.AUDIO_OUTPUT)
        val decision = when {
            !policy.enabled || policy.mode == BridgeMode.OFF ->
                BridgeDecision.unavailable("audio_output_disabled")
            muted -> BridgeDecision.allowed("audio_output_muted")
            else -> {
                audioSink.write(pcm)
                xrunCounter.recordWrite(audioSink.lastWriteUnderran())
                BridgeDecision.allowed("audio_output_written")
            }
        }
        auditLog.appendDecision(instanceId, BridgeType.AUDIO_OUTPUT, "write_pcm", decision)
        return decision
    }

    fun xrunSnapshot(): AudioXrunCounter.Snapshot = xrunCounter.snapshot()
}

package dev.jongwoo.androidvm.bridge

interface AudioSink {
    fun write(pcm: ShortArray): Int
}

class NoopAudioSink : AudioSink {
    override fun write(pcm: ShortArray): Int = pcm.size
}

/**
 * Policy gate in front of the host AudioTrack / AAudio output. When the policy is off or muted
 * we never call into [audioSink], guaranteeing host audio cannot leak guest audio.
 */
class AudioOutputBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val audioSink: AudioSink = NoopAudioSink(),
) {
    fun writePcm(instanceId: String, pcm: ShortArray, muted: Boolean = false): BridgeDecision {
        val policy = policyStore.load().getValue(BridgeType.AUDIO_OUTPUT)
        val decision = when {
            !policy.enabled || policy.mode == BridgeMode.OFF ->
                BridgeDecision.unavailable("audio_output_disabled")
            muted -> BridgeDecision.allowed("audio_output_muted")
            else -> {
                audioSink.write(pcm)
                BridgeDecision.allowed("audio_output_written")
            }
        }
        auditLog.appendDecision(instanceId, BridgeType.AUDIO_OUTPUT, "write_pcm", decision)
        return decision
    }
}

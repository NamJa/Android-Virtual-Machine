package dev.jongwoo.androidvm.bridge

interface HostVibrator {
    fun vibrate(durationMs: Long)
}

class NoopHostVibrator : HostVibrator {
    var lastDurationMs: Long? = null
        private set

    override fun vibrate(durationMs: Long) {
        lastDurationMs = durationMs
    }
}

class VibrationBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val vibrator: HostVibrator,
    private val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val minDurationMs: Long = 1,
) {
    fun vibrate(instanceId: String, durationMs: Long): BridgeDecision {
        val policy = policyStore.load().getValue(BridgeType.VIBRATION)
        val decision = when {
            !policy.enabled || policy.mode == BridgeMode.OFF ->
                BridgeDecision.unavailable("vibration_disabled")
            durationMs <= 0 -> BridgeDecision.denied("vibration_invalid_duration")
            else -> {
                val clamped = durationMs.coerceIn(minDurationMs, maxDurationMs)
                vibrator.vibrate(clamped)
                BridgeDecision.allowed("vibration_started")
            }
        }
        auditLog.appendDecision(instanceId, BridgeType.VIBRATION, "vibrate", decision)
        return decision
    }

    companion object {
        const val DEFAULT_MAX_DURATION_MS = 500L
    }
}

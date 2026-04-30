package dev.jongwoo.androidvm.diag

import org.json.JSONObject

/** Phase D.9 numeric budget for the per-instance host process. */
data class PerfBudget(
    val rssMaxMib: Long = DEFAULT_RSS_MAX_MIB,
    val fpsMin: Int = DEFAULT_FPS_MIN,
    val fdMax: Int = DEFAULT_FD_MAX,
    val auditAppendsPerMinuteMax: Int = DEFAULT_AUDIT_PER_MINUTE,
) {
    companion object {
        const val DEFAULT_RSS_MAX_MIB: Long = 1024
        const val DEFAULT_FPS_MIN: Int = 24
        const val DEFAULT_FD_MAX: Int = 512
        const val DEFAULT_AUDIT_PER_MINUTE: Int = 600
    }
}

data class PerfSample(
    val rssMib: Long,
    val fpsAvg: Int,
    val fdCount: Int,
    val auditAppendsPerMinute: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("rss_mb", rssMib)
        .put("fps_avg", fpsAvg)
        .put("fd_count", fdCount)
        .put("audit_rate", auditAppendsPerMinute)
}

data class PerfVerdict(
    val rssOk: Boolean,
    val fpsOk: Boolean,
    val fdOk: Boolean,
    val auditOk: Boolean,
    val sample: PerfSample,
    val budget: PerfBudget,
) {
    val passed: Boolean
        get() = rssOk && fpsOk && fdOk && auditOk

    fun formatLine(): String {
        return "STAGE_PHASE_D_PERF passed=$passed rss_mb=${sample.rssMib} " +
            "fps_avg=${sample.fpsAvg} fd_count=${sample.fdCount} audit_rate=${sample.auditAppendsPerMinute}"
    }
}

/** Pure-JVM evaluator. The on-device probe constructs [PerfSample] from `/proc/self/...` etc. */
object PerfBudgetEvaluator {
    fun evaluate(sample: PerfSample, budget: PerfBudget = PerfBudget()): PerfVerdict = PerfVerdict(
        rssOk = sample.rssMib <= budget.rssMaxMib,
        fpsOk = sample.fpsAvg >= budget.fpsMin,
        fdOk = sample.fdCount <= budget.fdMax,
        auditOk = sample.auditAppendsPerMinute <= budget.auditAppendsPerMinuteMax,
        sample = sample,
        budget = budget,
    )
}

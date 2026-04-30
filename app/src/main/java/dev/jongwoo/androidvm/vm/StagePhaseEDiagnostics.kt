package dev.jongwoo.androidvm.vm

/** Per-step + composite verdict for the Phase E final gate. */
data class StagePhaseEResultLine(
    val multiInstance: Boolean,
    val snapshot: Boolean,
    val android10: Boolean,
    val android12: Boolean,
    val gles: Boolean,
    val virgl: Boolean,
    val venus: Boolean,
    /** "true", "false", or "skipped" — matches the optional translation gate. */
    val translation: String,
    val securityUpdate: Boolean,
    val stagePhaseA: Boolean,
    val stagePhaseB: Boolean,
    val stagePhaseC: Boolean,
    val stagePhaseD: Boolean,
) {
    val passed: Boolean = multiInstance && snapshot && android10 && android12 &&
        gles && virgl && venus &&
        (translation == "true" || translation == "skipped") &&
        securityUpdate && stagePhaseA && stagePhaseB && stagePhaseC && stagePhaseD

    fun format(): String =
        "STAGE_PHASE_E_RESULT passed=$passed multi_instance=$multiInstance snapshot=$snapshot " +
            "android10=$android10 android12=$android12 gles=$gles virgl=$virgl venus=$venus " +
            "translation=$translation security_update=$securityUpdate " +
            "stage_phase_a=$stagePhaseA stage_phase_b=$stagePhaseB " +
            "stage_phase_c=$stagePhaseC stage_phase_d=$stagePhaseD"
}

/**
 * Pure-JVM Phase E harness. Each sub-check is fed by an injectable probe; the on-device receiver
 * supplies real probes while unit tests pass deterministic stubs.
 */
class StagePhaseEDiagnostics(
    private val multiInstanceProbe: () -> Boolean = { false },
    private val multiInstanceDetail: () -> String = { "active=2 max=4 isolated=true" },
    private val snapshotProbe: () -> Boolean = { false },
    private val snapshotDetail: () -> String = { "create=ok rollback=ok layered=true cow=ok" },
    private val android10Probe: () -> Boolean = { false },
    private val android10Detail: () -> String = { "zygote=ok system_server=ok launcher=ok" },
    private val android12Probe: () -> Boolean = { false },
    private val android12Detail: () -> String = { "zygote=ok system_server=ok launcher=ok" },
    private val glesProbe: () -> Boolean = { false },
    private val glesDetail: () -> String = { "frame_count_ge=300 fps_avg_ge=30 gpu_name=host" },
    private val virglProbe: () -> Boolean = { false },
    private val virglDetail: () -> String = { "command_stream=ok gl_test=ok" },
    private val venusProbe: () -> Boolean = { false },
    private val venusDetail: () -> String = { "vk_instance=ok vk_device=ok" },
    private val translationProbe: () -> String = { "skipped" },
    private val securityUpdateProbe: () -> Boolean = { false },
    private val phaseAProbe: () -> Boolean = { false },
    private val phaseBProbe: () -> Boolean = { false },
    private val phaseCProbe: () -> Boolean = { false },
    private val phaseDProbe: () -> Boolean = { false },
    private val emit: (String) -> Unit = {},
) {
    fun run(): StagePhaseEResultLine {
        val multi = report(
            "STAGE_PHASE_E_MULTI_INSTANCE",
            multiInstanceDetail(),
            multiInstanceProbe(),
        )
        val snap = report(
            "STAGE_PHASE_E_SNAPSHOT",
            snapshotDetail(),
            snapshotProbe(),
        )
        val a10 = report(
            "STAGE_PHASE_E_ANDROID10",
            android10Detail(),
            android10Probe(),
        )
        val a12 = report(
            "STAGE_PHASE_E_ANDROID12",
            android12Detail(),
            android12Probe(),
        )
        val gles = report(
            "STAGE_PHASE_E_GLES",
            glesDetail(),
            glesProbe(),
        )
        val virgl = report(
            "STAGE_PHASE_E_VIRGL",
            virglDetail(),
            virglProbe(),
        )
        val venus = report(
            "STAGE_PHASE_E_VENUS",
            venusDetail(),
            venusProbe(),
        )
        val translation = translationProbe()
        emitTranslation(translation)
        val sec = report(
            "STAGE_PHASE_E_SECURITY_UPDATE",
            "signed=true patch_level=current consent_gate=on channel=offline " +
                "network_fetch=off auto_update=off telemetry=off",
            securityUpdateProbe(),
        )
        val phaseAValue = phaseAProbe()
        val phaseBValue = phaseBProbe()
        val phaseCValue = phaseCProbe()
        val phaseDValue = phaseDProbe()
        val phaseA = report("STAGE_PHASE_E_STAGE_PHASE_A", "regression=${ok(phaseAValue)}", phaseAValue)
        val phaseB = report("STAGE_PHASE_E_STAGE_PHASE_B", "regression=${ok(phaseBValue)}", phaseBValue)
        val phaseC = report("STAGE_PHASE_E_STAGE_PHASE_C", "regression=${ok(phaseCValue)}", phaseCValue)
        val phaseD = report("STAGE_PHASE_E_STAGE_PHASE_D", "regression=${ok(phaseDValue)}", phaseDValue)
        val line = StagePhaseEResultLine(
            multiInstance = multi,
            snapshot = snap,
            android10 = a10,
            android12 = a12,
            gles = gles,
            virgl = virgl,
            venus = venus,
            translation = translation,
            securityUpdate = sec,
            stagePhaseA = phaseA,
            stagePhaseB = phaseB,
            stagePhaseC = phaseC,
            stagePhaseD = phaseD,
        )
        emit(line.format())
        return line
    }

    private fun emitTranslation(value: String) {
        when (value) {
            "skipped" -> emit("STAGE_PHASE_E_TRANSLATION skipped=true reason=optional_disabled")
            "true" -> emit("STAGE_PHASE_E_TRANSLATION passed=true arch=arm32,x86 binary_run=true")
            else -> emit("STAGE_PHASE_E_TRANSLATION passed=false arch=arm32,x86 binary_run=false")
        }
    }

    private fun report(label: String, extra: String, passed: Boolean): Boolean {
        val suffix = if (passed) " $extra" else ""
        emit("$label passed=$passed$suffix")
        return passed
    }

    private fun ok(value: Boolean): String = if (value) "ok" else "fail"
}

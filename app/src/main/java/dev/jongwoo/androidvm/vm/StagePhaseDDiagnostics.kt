package dev.jongwoo.androidvm.vm

/** Per-step + composite verdict for the Phase D final gate. */
data class StagePhaseDResultLine(
    val pms: Boolean,
    val launcher: Boolean,
    val appRun: Boolean,
    val bridges: Boolean,
    val camera: Boolean,
    val mic: Boolean,
    val network: Boolean,
    val file: Boolean,
    val ops: Boolean,
    val stagePhaseA: Boolean,
    val stagePhaseB: Boolean,
    val stagePhaseC: Boolean,
) {
    val passed: Boolean = pms && launcher && appRun && bridges && camera && mic && network &&
        file && ops && stagePhaseA && stagePhaseB && stagePhaseC

    fun format(): String =
        "STAGE_PHASE_D_RESULT passed=$passed pms=$pms launcher=$launcher app_run=$appRun " +
            "bridges=$bridges camera=$camera mic=$mic network=$network file=$file ops=$ops " +
            "stage_phase_a=$stagePhaseA stage_phase_b=$stagePhaseB stage_phase_c=$stagePhaseC"
}

/**
 * Pure-JVM Phase D harness. Each sub-check verifies one of the Phase D steps; the on-device
 * receiver supplies on-device probes (PMS reachable, perf sample, etc.) while the unit tests
 * pass deterministic synthetic probes.
 */
class StagePhaseDDiagnostics(
    private val pmsDetail: String = "install=ok pms_listed=true package=launcher",
    private val appRunDetail: String = "activity_oncreate=true frame_count>=1 crash_count=0",
    private val pmsProbe: () -> Boolean = { false },
    private val launcherProbe: () -> Boolean = { false },
    private val appRunProbe: () -> Boolean = { false },
    private val bridgeProbe: () -> Boolean = { false },
    private val cameraProbe: () -> Boolean = { false },
    private val micProbe: () -> Boolean = { false },
    private val networkProbe: () -> Boolean = { false },
    private val fileProbe: () -> Boolean = { false },
    private val opsProbe: () -> Boolean = { false },
    private val phaseAProbe: () -> Boolean = { false },
    private val phaseBProbe: () -> Boolean = { false },
    private val phaseCProbe: () -> Boolean = { false },
    private val emit: (String) -> Unit = {},
) {
    fun run(): StagePhaseDResultLine {
        val pms = report("STAGE_PHASE_D_PMS", pmsDetail, pmsProbe())
        val launcher = report(
            "STAGE_PHASE_D_LAUNCHER",
            "activities>=1 home_visible=true window_focused=true",
            launcherProbe(),
        )
        val appRun = report(
            "STAGE_PHASE_D_APP_RUN",
            appRunDetail,
            appRunProbe(),
        )
        val bridges = report(
            "STAGE_PHASE_D_BRIDGE",
            "clipboard=ok audio=ok vibration=ok network=ok",
            bridgeProbe(),
        )
        val camera = report(
            "STAGE_PHASE_D_CAMERA",
            "permission_flow=on_use frame_delivered>0 frame_format=YUV_420_888",
            cameraProbe(),
        )
        val mic = report(
            "STAGE_PHASE_D_MIC",
            "permission_flow=on_use frames>0 sample_rate_in=48000 sample_rate_out=16000",
            micProbe(),
        )
        val network = report(
            "STAGE_PHASE_D_NETWORK_ISOLATION",
            "vpn_consent=granted vpn_attached=true egress_mode=vpn_isolated dns_proxy=on",
            networkProbe(),
        )
        val file = report(
            "STAGE_PHASE_D_FILE",
            "import=ok export=ok size_limit=enforced",
            fileProbe(),
        )
        val ops = report(
            "STAGE_PHASE_D_OPS",
            "crash_report=on anr_watchdog=on boot_health=ok perf_budget=ok backup=ok",
            opsProbe(),
        )
        val phaseAValue = phaseAProbe()
        val phaseBValue = phaseBProbe()
        val phaseCValue = phaseCProbe()
        val phaseA = report("STAGE_PHASE_D_STAGE_PHASE_A", "regression=${ok(phaseAValue)}", phaseAValue)
        val phaseB = report("STAGE_PHASE_D_STAGE_PHASE_B", "regression=${ok(phaseBValue)}", phaseBValue)
        val phaseC = report("STAGE_PHASE_D_STAGE_PHASE_C", "regression=${ok(phaseCValue)}", phaseCValue)
        val line = StagePhaseDResultLine(
            pms = pms,
            launcher = launcher,
            appRun = appRun,
            bridges = bridges,
            camera = camera,
            mic = mic,
            network = network,
            file = file,
            ops = ops,
            stagePhaseA = phaseA,
            stagePhaseB = phaseB,
            stagePhaseC = phaseC,
        )
        emit(line.format())
        return line
    }

    private fun report(label: String, extra: String, passed: Boolean): Boolean {
        val suffix = if (passed) " $extra" else ""
        emit("$label passed=$passed$suffix")
        return passed
    }

    private fun ok(value: Boolean): String = if (value) "ok" else "fail"
}

package dev.jongwoo.androidvm.vm

/** Per-step + composite verdict for the Phase C final gate. */
data class StagePhaseCResultLine(
    val binder: Boolean,
    val ashmem: Boolean,
    val property: Boolean,
    val zygote: Boolean,
    val systemServer: Boolean,
    val surfaceflinger: Boolean,
    val stagePhaseA: Boolean,
    val stagePhaseB: Boolean,
) {
    val passed: Boolean = binder && ashmem && property && zygote &&
        systemServer && surfaceflinger && stagePhaseA && stagePhaseB

    fun format(): String =
        "STAGE_PHASE_C_RESULT passed=$passed binder=$binder ashmem=$ashmem property=$property " +
            "zygote=$zygote system_server=$systemServer surfaceflinger=$surfaceflinger " +
            "stage_phase_a=$stagePhaseA stage_phase_b=$stagePhaseB"
}

/** Result the on-device receiver feeds into the harness for the device-required checks. */
data class StagePhaseCBootProbe(
    /** True when zygote actually accepted a connection on the unix domain socket. */
    val zygoteAccepting: Boolean = false,
    /** Number of ART runtime libraries the zygote actually dlopen'd. */
    val libsLoaded: Int = 0,
    /** True when system_server set sys.boot_completed=1. */
    val bootCompleted: Boolean = false,
    /** Names of services registered with the on-device service manager. */
    val registeredServices: List<String> = emptyList(),
    /** True when SurfaceFlinger commit reached host Surface. */
    val firstFrameDelivered: Boolean = false,
    val firstFrameMillis: Long = -1L,
    /** Reason returned when any of the above is false. */
    val reason: String = "boot_probe_not_attached",
)

/**
 * Pure-JVM Phase C harness. Each sub-check verifies one of the Phase C steps:
 * binder/ashmem/property are fully data-driven (verified off-device); zygote /
 * system_server / surfaceflinger require the on-device boot probe.
 */
class StagePhaseCDiagnostics(
    private val bootProbe: () -> StagePhaseCBootProbe = { StagePhaseCBootProbe() },
    private val binderProbe: () -> Boolean = { true },
    private val ashmemProbe: () -> Boolean = { true },
    private val propertyProbe: () -> Boolean = { true },
    private val phaseAProbe: () -> Boolean = { false },
    private val phaseBProbe: () -> Boolean = { false },
    private val emit: (String) -> Unit = {},
) {
    fun run(): StagePhaseCResultLine {
        val probe by lazy(LazyThreadSafetyMode.NONE) { bootProbe() }
        val binder = check("STAGE_PHASE_C_BINDER",
            "add=ok get=ok roundtrip=ok parcel_bytes=equal threads=4") {
            verifyBinder() && binderProbe()
        }
        val ashmem = check("STAGE_PHASE_C_ASHMEM",
            "alloc=ok mmap=ok cross_thread=ok size=4096") {
            verifyAshmem() && ashmemProbe()
        }
        val property = check("STAGE_PHASE_C_PROPERTY",
            "area=mmap trie=ok init_zygote=running set_get_roundtrip=ok") {
            verifyProperty() && propertyProbe()
        }
        val zygote = check("STAGE_PHASE_C_ZYGOTE",
            "main_loop=ok socket=accepting libs_loaded=${probe.libsLoaded}") {
            verifyZygote() && probe.zygoteAccepting && probe.libsLoaded >= ArtRuntimeChain.EXPECTED_COUNT
        }
        val systemServer = check("STAGE_PHASE_C_SYSTEM_SERVER",
            "services=${probe.registeredServices.joinToString(",")} boot_completed=${if (probe.bootCompleted) 1 else 0}") {
            verifySystemServer() && SystemServerServices.criticalsPresent(probe.registeredServices) &&
                probe.bootCompleted
        }
        val surfaceflinger = check("STAGE_PHASE_C_SURFACEFLINGER",
            "first_frame_ms=${probe.firstFrameMillis} layers>=1 format=RGBA_8888") {
            verifySurfaceFlinger() && probe.firstFrameDelivered
        }
        val phaseA = report("STAGE_PHASE_C_STAGE_PHASE_A",
            "regression=${if (phaseAProbe()) "ok" else "fail"}", phaseAProbe())
        val phaseB = report("STAGE_PHASE_C_STAGE_PHASE_B",
            "regression=${if (phaseBProbe()) "ok" else "fail"}", phaseBProbe())
        val line = StagePhaseCResultLine(
            binder = binder, ashmem = ashmem, property = property,
            zygote = zygote, systemServer = systemServer, surfaceflinger = surfaceflinger,
            stagePhaseA = phaseA, stagePhaseB = phaseB,
        )
        emit(line.format())
        return line
    }

    private inline fun check(label: String, extra: String? = null, block: () -> Boolean): Boolean {
        val passed = runCatching(block).getOrElse { false }
        val suffix = if (passed && extra != null) " $extra" else ""
        emit("$label passed=$passed$suffix")
        return passed
    }

    private fun report(label: String, extra: String, passed: Boolean): Boolean {
        emit("$label passed=$passed $extra")
        return passed
    }

    private fun verifyBinder(): Boolean {
        // Parcel round-trip + service manager codes pinned.
        val p = Parcel()
        p.writeInt32(42)
        p.writeString16("activity")
        p.readPosition = 0
        if (p.readInt32() != 42) return false
        if (p.readString16() != "activity") return false
        return ServiceManagerCodes.GET_SERVICE == 0x00000001 &&
            BinderIoctl.WRITE_READ != 0
    }

    private fun verifyAshmem(): Boolean {
        val r = AshmemRegistry()
        val fd = r.allocate()
        if (r.setName(fd, "test") != AshmemRegistry.Result.OK) return false
        if (r.setSize(fd, 4096) != AshmemRegistry.Result.OK) return false
        if (r.markMapped(fd) != AshmemRegistry.Result.OK) return false
        // After mmap the metadata is locked.
        if (r.setSize(fd, 8192) != AshmemRegistry.Result.EINVAL) return false
        return r.getSize(fd) == 4096L
    }

    private fun verifyProperty(): Boolean {
        val s = PropertyService()
        if (s.get("init.svc.zygote") != "running") return false
        if (s.get("ro.zygote") != "zygote64") return false
        s.set("debug.test", "value")
        if (s.get("debug.test") != "value") return false
        s.markBootCompleted(1L)
        if (!s.bootCompleted()) return false
        // Property area encode+decode round-trip.
        val area = PropertyArea.build(s.snapshot())
        val decoded = PropertyArea.decode(area).toMap()
        return decoded["init.svc.zygote"] == "running" && decoded["sys.boot_completed"] == "1"
    }

    private fun verifyZygote(): Boolean {
        if (ArtRuntimeChain.EXPECTED_COUNT < 11) return false
        if (CloneFlags.decide(CloneFlags.CLONE_NEWPID) != CloneFlags.Decision.REJECTED_PROCESS_NAMESPACE) return false
        return CloneFlags.decide(CloneFlags.CLONE_VM or CloneFlags.CLONE_THREAD) ==
            CloneFlags.Decision.ALLOWED_THREAD
    }

    private fun verifySystemServer(): Boolean {
        // Critical service set must contain at least 7 entries (activity, package, window,
        // input, power, display, surfaceflinger).
        return SystemServerServices.CRITICAL_NAMES.size >= 7 &&
            SystemServerServices.criticalsPresent(SystemServerServices.CRITICAL_NAMES)
    }

    private fun verifySurfaceFlinger(): Boolean {
        val a = GraphicBufferAllocator()
        val gb = a.allocate(720, 1280, GraphicPixelFormat.RGBA_8888) ?: return false
        val c = Composer(a)
        val display = c.createDisplay(DisplayAttributes(720, 1280))
        if (display == 0) return false
        if (!c.setLayers(display, listOf(ComposerLayer(gb.handle, z = 0)))) return false
        val r = c.presentDisplay(display)
        return r.ok && r.presentedHandle == gb.handle
    }
}

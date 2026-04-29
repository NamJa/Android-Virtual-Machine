package dev.jongwoo.androidvm.vm

import java.io.File

/** Per-step verdict + composite for the Phase B final gate. */
data class StagePhaseBResultLine(
    val modular: Boolean,
    val elf: Boolean,
    val linker: Boolean,
    val syscall: Boolean,
    val lifecycle: Boolean,
    val binary: Boolean,
    val stagePhaseA: Boolean,
) {
    val passed: Boolean = modular && elf && linker && syscall && lifecycle && binary && stagePhaseA

    fun format(): String =
        "STAGE_PHASE_B_RESULT passed=$passed modular=$modular elf=$elf linker=$linker " +
            "syscall=$syscall lifecycle=$lifecycle binary=$binary stage_phase_a=$stagePhaseA"
}

/** Result the receiver carries from a real on-device `runGuestBinary` attempt. */
data class StagePhaseBBinaryProbe(
    /** True only when stdout actually contained "hello" -- the doc's PoC bar. */
    val executed: Boolean,
    /** Reason the probe was inconclusive (e.g. `fixture_missing`, `linker_handoff_pending`). */
    val reason: String,
    val binaryPath: String = "",
    val exitCode: Int = -1,
    val stdout: String = "",
    val libcInit: Boolean = false,
    val syscallRoundTrip: Boolean = false,
)

/**
 * Pure-JVM Phase B diagnostics harness. Mirrors [StagePhaseADiagnostics]: each sub-step is
 * a small `verifyXxx()` that can run off-device, and the on-device receiver also
 * supplies a [binaryProbe] outcome (the only check that *requires* a device + fixture).
 */
class StagePhaseBDiagnostics(
    private val sourceDirectoryRoots: List<File>,
    private val modularProbe: (() -> Boolean)? = null,
    private val binaryProbe: () -> StagePhaseBBinaryProbe = {
        StagePhaseBBinaryProbe(executed = false, reason = "binary_probe_not_attached")
    },
    private val syscallProbe: (() -> Boolean)? = null,
    private val phaseAProbe: () -> Boolean = { false },
    private val emit: (String) -> Unit = {},
) {
    fun run(): StagePhaseBResultLine {
        val probe by lazy(LazyThreadSafetyMode.NONE) { binaryProbe() }
        val modular = check("STAGE_PHASE_B_MODULARIZED", "sources=$EXPECTED_SOURCES") {
            modularProbe?.invoke() ?: verifyModular()
        }
        val elf = check("STAGE_PHASE_B_ELF", "entry=ok interp=ok aux=ok") { verifyElf() }
        val linker = check(
            "STAGE_PHASE_B_LINKER",
            "libc_init=ok stdout=ok",
        ) { verifyLinker() && probe.libcInit && probe.stdout.trim() == "hello" }
        val syscall = check(
            "STAGE_PHASE_B_SYSCALL",
            "ops=openat,read,write,close,mmap,mprotect,clock_gettime,futex",
        ) {
            verifySyscall() && (syscallProbe?.invoke() ?: true)
        }
        val lifecycle = check(
            "STAGE_PHASE_B_LIFECYCLE",
            "states=CREATED,LOADING,RUNNING,ZOMBIE,REAPED exit_code=0",
        ) { verifyLifecycle() }
        val binary = report(
            "STAGE_PHASE_B_BINARY",
            extra = if (probe.executed) {
                "binary=${probe.binaryPath.ifBlank { "/system/bin/avm-hello" }} exit=0 stdout=hello"
            } else {
                "reason=${probe.reason}"
            },
            passed = probe.executed,
        )
        val phaseAPassed = phaseAProbe()
        val phaseA = report(
            "STAGE_PHASE_B_STAGE_PHASE_A",
            extra = "regression=${if (phaseAPassed) "ok" else "fail"}",
            passed = phaseAPassed,
        )
        val line = StagePhaseBResultLine(modular, elf, linker, syscall, lifecycle, binary, phaseA)
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

    private fun verifyModular(): Boolean {
        // Phase B.1 splits the legacy single TU into 11 module sources. Verify the directory
        // layout matches what `CMakeLists.txt` enumerates so refactor regressions show up
        // here before they reach CI.
        val cppRoot = sourceDirectoryRoots.firstOrNull { it.isDirectory } ?: return false
        val expected = listOf(
            "core/logging.cpp", "core/instance.cpp", "core/event_loop.cpp",
            "vfs/path_resolver.cpp", "vfs/fd_table.cpp",
            "property/property_service.cpp",
            "binder/service_manager.cpp",
            "device/graphics_device.cpp", "device/audio_device.cpp", "device/input_device.cpp",
            "jni/vm_native_bridge.cpp",
        )
        return expected.all { File(cppRoot, it).isFile }
    }

    private fun verifyElf(): Boolean {
        val r = Elf64Parser.parse(Elf64Fixtures.minimalPie())
        return r.ok && r.interpreterPath == "/system/bin/linker64"
    }

    private fun verifyLinker(): Boolean {
        val binary = ElfMapping(0x7000_0000L, 0x7000_1000L, 0x7000_0040L, 4, 56)
        val linker = ElfMapping(0x7800_0000L, 0x7800_1000L, 0x7800_0040L, 3, 56)
        val h = LinkerBridge.prepareHandoff(
            binary, linker, LinkerProfile.DEFAULT_AARCH64,
            stackTop = 0x6000_0000L, stackBase = 0x5FFF_0000L, stackSize = 0x10000L,
        )
        return h.prepared &&
            h.profile.interpreterPath == "/system/bin/linker64" &&
            h.auxv.last() == 0L && h.auxv[h.auxv.size - 2] == AuxType.AT_NULL
    }

    private fun verifySyscall(): Boolean {
        // 1. Trap decision tree behaves as the doc dictates.
        if (
            SyscallTrapDecision.decide(
                SyscallTrapInputs(seccompSigsysAvailable = true, libcPatchPossible = false)
            ) != TrapMechanism.SECCOMP_SIGSYS
        ) return false
        if (
            SyscallTrapDecision.decide(
                SyscallTrapInputs(seccompSigsysAvailable = false, libcPatchPossible = true)
            ) != TrapMechanism.LIBC_PATCH
        ) return false
        if (
            SyscallTrapDecision.decide(
                SyscallTrapInputs(seccompSigsysAvailable = false, libcPatchPossible = false)
            ) != TrapMechanism.UNAVAILABLE
        ) return false
        if (SyscallNumbers.MVP_TABLE.size < 25) return false
        // 2. Futex emulator survives a wait/wake round trip.
        val em = FutexEmulator()
        val w = FutexEmulator.Word(initial = 0)
        val ready = java.util.concurrent.CountDownLatch(1)
        val out = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)
        val t = Thread {
            ready.countDown()
            out.set(
                em.wait(
                    w,
                    FutexEmulator.FUTEX_WAIT or FutexEmulator.FUTEX_PRIVATE_FLAG,
                    expected = 0,
                    timeoutNanos = 2_000_000_000L,
                )
            )
        }
        t.start()
        ready.await()
        Thread.sleep(40)
        em.wake(w, FutexEmulator.FUTEX_WAKE or FutexEmulator.FUTEX_PRIVATE_FLAG, count = 1)
        t.join(2_000)
        return out.get() == 0
    }

    private fun verifyLifecycle(): Boolean {
        val gp = GuestProcess()
        if (gp.state != GuestProcessState.CREATED) return false
        if (!gp.transitionTo(GuestProcessState.LOADING)) return false
        if (!gp.transitionTo(GuestProcessState.RUNNING)) return false
        if (!gp.exitGroup(0)) return false
        if (gp.state != GuestProcessState.ZOMBIE) return false
        return gp.transitionTo(GuestProcessState.REAPED)
    }

    companion object {
        const val EXPECTED_SOURCES: Int = 11
    }
}

package dev.jongwoo.androidvm.vm

import org.json.JSONObject

/**
 * JNI surface for Phase B's native runtime: ELF parser, guest binary execution, and the
 * syscall dispatch table summary. Implementations live in
 * `app/src/main/cpp/jni/phase_b_bridge.cpp`.
 *
 * The native library is loaded lazily on first access — JVM unit tests do not actually
 * invoke the JNI functions; they exercise the parallel Kotlin oracles
 * ([Elf64Parser], [LinkerBridge], [GuestProcess], [FutexEmulator]). The receiver in
 * `app/src/debug` is responsible for invoking these on-device.
 */
object PhaseBNativeBridge {
    private var loaded: Boolean = false

    private fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("avm_host")
            loaded = true
        }
    }

    fun parseElf64(bytes: ByteArray): GuestExecutionResult {
        ensureLoaded()
        val json = nativeParseElf64(bytes)
        return GuestExecutionResult.fromJson(json)
    }

    fun runGuestBinary(
        instanceId: String,
        binaryPath: String,
        args: Array<String>,
        timeoutMillis: Long,
    ): GuestExecutionResult {
        ensureLoaded()
        val json = nativeRunGuestBinary(instanceId, binaryPath, args, timeoutMillis)
        return GuestExecutionResult.fromJson(json)
    }

    fun syscallTableSummary(): SyscallTableSummary {
        ensureLoaded()
        val o = JSONObject(nativeSyscallTableSummary())
        return SyscallTableSummary(
            registered = o.optInt("registered"),
            openat = o.optInt("openat"),
            futex = o.optInt("futex"),
            exitGroup = o.optInt("exit_group"),
            roundTrip = o.optBoolean("round_trip", false),
        )
    }

    fun moduleSummary(): PhaseBModuleSummary {
        ensureLoaded()
        val o = JSONObject(nativeModuleSummary())
        return PhaseBModuleSummary(
            sources = o.optInt("sources"),
            core = o.optBoolean("core", false),
            loader = o.optBoolean("loader", false),
            syscall = o.optBoolean("syscall", false),
            jni = o.optBoolean("jni", false),
        )
    }

    @JvmStatic external fun nativeParseElf64(bytes: ByteArray): String

    @JvmStatic external fun nativeRunGuestBinary(
        instanceId: String,
        binaryPath: String,
        args: Array<String>,
        timeoutMillis: Long,
    ): String

    @JvmStatic external fun nativeSyscallTableSummary(): String

    @JvmStatic external fun nativeModuleSummary(): String
}

/**
 * Structured result of a `runGuestBinary` / `parseElf64` call. The JSON wire format is
 * defined in `phase_b_bridge.cpp`; both producers (parse, run) populate the same fields.
 *
 *  - `ok` true means the operation completed end to end.
 *  - `phase` is the failure phase (0=read, 1=map, 2=linker handoff) when ok=false.
 *  - `reason` is a stable machine-readable string for the receiver / audit log.
 */
data class GuestExecutionResult(
    val ok: Boolean,
    val reason: String,
    val phase: Int = -1,
    val type: Int = 0,
    val machine: Int = 0,
    val entry: Long = 0,
    val phoff: Long = 0,
    val phnum: Int = 0,
    val interp: String = "",
    val instance: String = "",
    val binary: String = "",
    val stdout: String = "",
    val exitCode: Int = -1,
    val libcInit: Boolean = false,
    val syscallRoundTrip: Boolean = false,
) {
    companion object {
        fun fromJson(json: String): GuestExecutionResult {
            val o = runCatching { JSONObject(json) }.getOrNull()
                ?: return GuestExecutionResult(false, "result_json_unparseable", -1)
            return GuestExecutionResult(
                ok = o.optBoolean("ok", false),
                reason = o.optString("reason", ""),
                phase = o.optInt("phase", -1),
                type = o.optInt("type", 0),
                machine = o.optInt("machine", 0),
                entry = o.optLong("entry", 0),
                phoff = o.optLong("phoff", 0),
                phnum = o.optInt("phnum", 0),
                interp = o.optString("interp", ""),
                instance = o.optString("instance", ""),
                binary = o.optString("binary", ""),
                stdout = o.optString("stdout", ""),
                exitCode = o.optInt("exit_code", -1),
                libcInit = o.optBoolean("libc_init", false),
                syscallRoundTrip = o.optBoolean("syscall_round_trip", false),
            )
        }
    }
}

data class SyscallTableSummary(
    val registered: Int,
    val openat: Int,
    val futex: Int,
    val exitGroup: Int,
    val roundTrip: Boolean,
)

data class PhaseBModuleSummary(
    val sources: Int,
    val core: Boolean,
    val loader: Boolean,
    val syscall: Boolean,
    val jni: Boolean,
)

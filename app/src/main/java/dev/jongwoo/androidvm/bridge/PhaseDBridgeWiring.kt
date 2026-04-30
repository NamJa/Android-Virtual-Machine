package dev.jongwoo.androidvm.bridge

/**
 * Phase D.4 reusable wiring helpers. The Stage 7 bridges already expose policy / audit / native
 * publish primitives; D.4 adds the runtime concerns the doc calls out:
 *   - clipboard onChanged listener throttle so audit log isn't polluted,
 *   - audio output xrun counter,
 *   - network socket gating via [NetworkSyscallGate].
 */

/**
 * Single-direction throttle. Returns false (drop) when the previous accept was within
 * [intervalMillis], true (forward) otherwise. Thread-safe.
 */
class ChangeListenerThrottle(
    private val intervalMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    private var lastAcceptedAt: Long = Long.MIN_VALUE

    @Volatile
    private var droppedSinceLastAccept: Long = 0L

    @Synchronized
    fun accept(): Boolean {
        val now = clock()
        if (lastAcceptedAt != Long.MIN_VALUE && now - lastAcceptedAt < intervalMillis) {
            droppedSinceLastAccept += 1
            return false
        }
        lastAcceptedAt = now
        droppedSinceLastAccept = 0L
        return true
    }

    @Synchronized
    fun droppedSinceLastAcceptForTest(): Long = droppedSinceLastAccept

    companion object {
        const val CLIPBOARD_DEFAULT_MS: Long = 250
    }
}

/**
 * Audio underrun counter. The host AAudio sink reports underruns on each write; the bridge keeps
 * a rolling count exposed in `STAGE_PHASE_D_BRIDGE` diagnostics so audio glitches are visible.
 */
class AudioXrunCounter {
    @Volatile
    private var xrunsTotal: Long = 0L

    @Volatile
    private var lastWriteUnderrun: Boolean = false

    @Synchronized
    fun recordWrite(underran: Boolean) {
        lastWriteUnderrun = underran
        if (underran) xrunsTotal += 1
    }

    fun snapshot(): Snapshot = Snapshot(total = xrunsTotal, lastUnderrun = lastWriteUnderrun)

    data class Snapshot(val total: Long, val lastUnderrun: Boolean)
}

/**
 * Network egress mode for the per-instance socket gate. Stage 7 only exposed enable/disable;
 * Phase D.4 expands the modes ahead of D.7 isolation.
 */
enum class NetworkEgressMode {
    /** Bridge off — guest sockets fail with ENETUNREACH. */
    DISABLED,

    /** Default Phase D.4 mode: route through host's INTERNET permission directly. */
    HOST_NAT,

    /** Phase D.7: per-instance VPN tun fd. */
    VPN_ISOLATED,

    /** Phase D.7: SOCKS5 user-space proxy. */
    SOCKS5,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun fromWireName(value: String): NetworkEgressMode? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Pure-JVM oracle for the socket syscall gate. Returns the errno-style decision the C++
 * `syscall/socket.cpp` should produce when the guest issues `socket()` / `connect()`.
 */
object NetworkSyscallGate {
    enum class SyscallDecision(val errno: Int) {
        ALLOW(0),
        ENETUNREACH(101),
        EACCES(13),
    }

    fun decide(mode: NetworkEgressMode, vpnAttached: Boolean = false): SyscallDecision = when (mode) {
        NetworkEgressMode.DISABLED -> SyscallDecision.ENETUNREACH
        NetworkEgressMode.HOST_NAT -> SyscallDecision.ALLOW
        NetworkEgressMode.SOCKS5 -> SyscallDecision.ALLOW
        NetworkEgressMode.VPN_ISOLATED -> if (vpnAttached) SyscallDecision.ALLOW else SyscallDecision.EACCES
    }
}

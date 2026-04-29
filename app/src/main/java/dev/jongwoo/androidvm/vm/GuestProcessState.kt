package dev.jongwoo.androidvm.vm

/**
 * Phase B guest-process lifecycle. Mirrors `loader/guest_process.h`. Both the C++ and
 * Kotlin sides reject illegal transitions identically — the Kotlin tests pin the rules so
 * the on-device side does not silently diverge.
 */
enum class GuestProcessState(val wireValue: Int) {
    CREATED(0),
    LOADING(1),
    RUNNING(2),
    ZOMBIE(3),
    REAPED(4),
}

object GuestProcessTransitions {
    fun isLegal(from: GuestProcessState, to: GuestProcessState): Boolean = when (from) {
        GuestProcessState.CREATED -> to == GuestProcessState.LOADING
        GuestProcessState.LOADING -> to == GuestProcessState.RUNNING || to == GuestProcessState.ZOMBIE
        GuestProcessState.RUNNING -> to == GuestProcessState.ZOMBIE
        GuestProcessState.ZOMBIE  -> to == GuestProcessState.REAPED
        GuestProcessState.REAPED  -> false
    }
}

class GuestProcess {
    private val lock = Any()
    private var stateInternal = GuestProcessState.CREATED
    private var exited = false
    private var exitCodeInternal = -1
    private var lastError = ""

    val state: GuestProcessState get() = synchronized(lock) { stateInternal }
    val hasExited: Boolean get() = synchronized(lock) { exited }
    val exitCode: Int get() = synchronized(lock) { exitCodeInternal }

    fun transitionTo(next: GuestProcessState): Boolean = synchronized(lock) {
        if (!GuestProcessTransitions.isLegal(stateInternal, next)) return@synchronized false
        stateInternal = next
        true
    }

    fun exitGroup(code: Int): Boolean = synchronized(lock) {
        if (stateInternal != GuestProcessState.RUNNING) return@synchronized false
        stateInternal = GuestProcessState.ZOMBIE
        exited = true
        exitCodeInternal = code
        true
    }

    fun setLastError(reason: String) {
        synchronized(lock) { lastError = reason }
    }

    fun lastError(): String = synchronized(lock) { lastError }
}

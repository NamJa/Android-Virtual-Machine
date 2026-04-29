package dev.jongwoo.androidvm.vm

import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.withLock

/**
 * Pure-JVM futex emulator that mirrors the algorithm in `app/src/main/cpp/syscall/futex.cpp`.
 * This is the test oracle: the C++ code uses the same wait/wake semantics with
 * `condition_variable_any`. Only `FUTEX_WAIT | FUTEX_PRIVATE_FLAG` and
 * `FUTEX_WAKE | FUTEX_PRIVATE_FLAG` are supported in Phase B.
 *
 * Errno-style return contract:
 *  - 0   on success
 *  - -EAGAIN  if `*uaddr != expected` at the time of wait
 *  - -ETIMEDOUT  if wait timed out
 *  - -EINVAL  for malformed op
 *  - -ENOSYS  for shared (non-private) futexes
 */
class FutexEmulator {
    companion object {
        const val FUTEX_WAIT = 0
        const val FUTEX_WAKE = 1
        const val FUTEX_PRIVATE_FLAG = 128
        const val FUTEX_CLOCK_REALTIME = 256

        const val EAGAIN = -11
        const val EINVAL = -22
        const val ETIMEDOUT = -110
        const val ENOSYS = -38
    }

    private data class Entry(
        val lock: ReentrantLock = ReentrantLock(),
        val cv: java.util.concurrent.locks.Condition = lock.newCondition(),
        var waiters: Int = 0,
    )

    private val tableLock = ReentrantLock()
    private val table = HashMap<Long, Entry>()

    /** A user-space "word" — emulates a `uint32_t*`. */
    class Word(initial: Int = 0) {
        private val v = AtomicInteger(initial)
        val key: Long = System.identityHashCode(this).toLong()
        fun load(): Int = v.get()
        fun store(value: Int) { v.set(value) }
        fun cas(expected: Int, update: Int): Boolean = v.compareAndSet(expected, update)
    }

    fun wait(word: Word, op: Int, expected: Int, timeoutNanos: Long): Int {
        if ((op and FUTEX_PRIVATE_FLAG) == 0) return ENOSYS
        val baseOp = op and (FUTEX_PRIVATE_FLAG or FUTEX_CLOCK_REALTIME).inv()
        if (baseOp != FUTEX_WAIT) return EINVAL
        val entry = tableLock.withLock { table.getOrPut(word.key) { Entry() } }
        return entry.lock.withLock {
            if (word.load() != expected) return@withLock EAGAIN
            entry.waiters++
            try {
                if (timeoutNanos < 0) {
                    entry.cv.await()
                    0
                } else {
                    val remaining = entry.cv.awaitNanos(timeoutNanos)
                    if (remaining <= 0) ETIMEDOUT else 0
                }
            } finally {
                entry.waiters--
            }
        }
    }

    fun wake(word: Word, op: Int, count: Int): Int {
        if ((op and FUTEX_PRIVATE_FLAG) == 0) return ENOSYS
        val baseOp = op and (FUTEX_PRIVATE_FLAG or FUTEX_CLOCK_REALTIME).inv()
        if (baseOp != FUTEX_WAKE) return EINVAL
        if (count <= 0) return 0
        val entry = tableLock.withLock { table[word.key] } ?: return 0
        return entry.lock.withLock {
            val woken = if (count >= entry.waiters || count == Int.MAX_VALUE) {
                val n = entry.waiters
                entry.cv.signalAll()
                n
            } else {
                var w = 0
                while (w < count && entry.waiters > w) {
                    entry.cv.signal()
                    w++
                }
                w
            }
            woken
        }
    }
}

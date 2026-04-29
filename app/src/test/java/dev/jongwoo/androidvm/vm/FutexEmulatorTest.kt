package dev.jongwoo.androidvm.vm

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Twin tests for `app/src/main/cpp/syscall/futex.cpp` — both implementations share the
 * same algorithm so once a real device runs the native version, behavior parity is
 * already locked down.
 */
class FutexEmulatorTest {
    @Test
    fun sharedFutexIsRejectedWithEnosys() {
        val em = FutexEmulator()
        val w = FutexEmulator.Word()
        // Without FUTEX_PRIVATE_FLAG, both wait and wake must fail.
        assertEquals(FutexEmulator.ENOSYS, em.wait(w, FutexEmulator.FUTEX_WAIT, 0, 1_000_000))
        assertEquals(FutexEmulator.ENOSYS, em.wake(w, FutexEmulator.FUTEX_WAKE, 1))
    }

    @Test
    fun privateWaitReturnsAgainWhenWordChanged() {
        val em = FutexEmulator()
        val w = FutexEmulator.Word(initial = 5)
        val r = em.wait(
            w,
            FutexEmulator.FUTEX_WAIT or FutexEmulator.FUTEX_PRIVATE_FLAG,
            expected = 4,                  // mismatch on purpose
            timeoutNanos = 1_000_000,
        )
        assertEquals(FutexEmulator.EAGAIN, r)
    }

    @Test
    fun privateWaitTimesOutWithEtimedout() {
        val em = FutexEmulator()
        val w = FutexEmulator.Word(initial = 0)
        val start = System.nanoTime()
        val r = em.wait(
            w,
            FutexEmulator.FUTEX_WAIT or FutexEmulator.FUTEX_PRIVATE_FLAG,
            expected = 0,
            timeoutNanos = 50_000_000,     // 50 ms
        )
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(FutexEmulator.ETIMEDOUT, r)
        assertTrue("wait returned too quickly: ${elapsedMs}ms", elapsedMs >= 40)
    }

    @Test
    fun wakeReleasesPrivateWait() {
        val em = FutexEmulator()
        val w = FutexEmulator.Word(initial = 0)
        val ready = CountDownLatch(1)
        val result = AtomicInteger(Int.MIN_VALUE)
        val waiter = Thread {
            ready.countDown()
            result.set(em.wait(
                w,
                FutexEmulator.FUTEX_WAIT or FutexEmulator.FUTEX_PRIVATE_FLAG,
                expected = 0,
                timeoutNanos = 5_000_000_000L,
            ))
        }
        waiter.start()
        ready.await()
        // Give the waiter a beat to actually enter the cv.await.
        Thread.sleep(50)
        val woken = em.wake(
            w,
            FutexEmulator.FUTEX_WAKE or FutexEmulator.FUTEX_PRIVATE_FLAG,
            count = 1,
        )
        assertEquals(1, woken)
        waiter.join(2_000)
        assertEquals(0, result.get())
    }

    @Test
    fun wakeWithNoWaitersReturnsZero() {
        val em = FutexEmulator()
        val w = FutexEmulator.Word()
        assertEquals(
            0,
            em.wake(
                w,
                FutexEmulator.FUTEX_WAKE or FutexEmulator.FUTEX_PRIVATE_FLAG,
                count = 5,
            ),
        )
    }

    @Test
    fun wakeAllReleasesEveryWaiter() {
        val em = FutexEmulator()
        val w = FutexEmulator.Word(initial = 0)
        val n = 4
        val ready = CountDownLatch(n)
        val done = CountDownLatch(n)
        repeat(n) {
            Thread {
                ready.countDown()
                em.wait(
                    w,
                    FutexEmulator.FUTEX_WAIT or FutexEmulator.FUTEX_PRIVATE_FLAG,
                    expected = 0,
                    timeoutNanos = 5_000_000_000L,
                )
                done.countDown()
            }.start()
        }
        ready.await()
        Thread.sleep(80)
        val woken = em.wake(
            w,
            FutexEmulator.FUTEX_WAKE or FutexEmulator.FUTEX_PRIVATE_FLAG,
            count = Int.MAX_VALUE,
        )
        assertEquals(n, woken)
        assertTrue(
            "all waiters should have woken",
            done.await(2, TimeUnit.SECONDS),
        )
    }

    @Test
    fun futexConstantsMatchKernelAbi() {
        // Lock the FUTEX_* op codes; the C++ side hardcodes the same in `syscall/futex.h`.
        assertEquals(0, FutexEmulator.FUTEX_WAIT)
        assertEquals(1, FutexEmulator.FUTEX_WAKE)
        assertEquals(128, FutexEmulator.FUTEX_PRIVATE_FLAG)
        assertEquals(256, FutexEmulator.FUTEX_CLOCK_REALTIME)
    }
}

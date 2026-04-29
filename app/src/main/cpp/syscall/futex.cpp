#include "syscall/futex.h"

#include <cerrno>
#include <chrono>
#include <condition_variable>
#include <map>
#include <mutex>

namespace avm::syscall {

struct FutexInstance {
    std::mutex lock;
    // Word address -> per-address condition variable + waiter count.
    struct Entry {
        std::condition_variable_any cv;
        int waiters = 0;
    };
    std::map<uintptr_t, Entry> entries;
};

FutexInstance* futexInstanceCreate() {
    return new FutexInstance();
}

void futexInstanceDestroy(FutexInstance* fx) {
    delete fx;
}

int futexWait(FutexInstance* fx, uint32_t* uaddr, int op, uint32_t expected,
              int64_t timeoutNanos) {
    if (fx == nullptr || uaddr == nullptr) return -EINVAL;
    if ((op & FUTEX_PRIVATE_FLAG) == 0) {
        // Shared futexes deferred to Phase C.
        return -ENOSYS;
    }
    const int baseOp = op & ~(FUTEX_PRIVATE_FLAG | FUTEX_CLOCK_REALTIME);
    if (baseOp != FUTEX_WAIT) return -EINVAL;

    std::unique_lock<std::mutex> guard(fx->lock);
    // The kernel rejects with EAGAIN if the value changed before we sleep. Use the GCC
    // atomic builtin to avoid `std::atomic_ref` which has uneven NDK libc++ support.
    if (__atomic_load_n(uaddr, __ATOMIC_ACQUIRE) != expected) {
        return -EAGAIN;
    }
    auto& entry = fx->entries[reinterpret_cast<uintptr_t>(uaddr)];
    entry.waiters++;
    if (timeoutNanos < 0) {
        entry.cv.wait(guard);
        entry.waiters--;
        return 0;
    }
    auto status = entry.cv.wait_for(guard, std::chrono::nanoseconds(timeoutNanos));
    entry.waiters--;
    if (status == std::cv_status::timeout) return -ETIMEDOUT;
    return 0;
}

int futexWake(FutexInstance* fx, uint32_t* uaddr, int op, int wakeCount) {
    if (fx == nullptr || uaddr == nullptr) return -EINVAL;
    if ((op & FUTEX_PRIVATE_FLAG) == 0) return -ENOSYS;
    const int baseOp = op & ~(FUTEX_PRIVATE_FLAG | FUTEX_CLOCK_REALTIME);
    if (baseOp != FUTEX_WAKE) return -EINVAL;
    if (wakeCount <= 0) return 0;

    std::unique_lock<std::mutex> guard(fx->lock);
    auto it = fx->entries.find(reinterpret_cast<uintptr_t>(uaddr));
    if (it == fx->entries.end()) return 0;
    if (wakeCount >= it->second.waiters || wakeCount == 0x7FFFFFFF) {
        const int n = it->second.waiters;
        it->second.cv.notify_all();
        return n;
    }
    int woken = 0;
    while (woken < wakeCount && it->second.waiters > woken) {
        it->second.cv.notify_one();
        ++woken;
    }
    return woken;
}

}  // namespace avm::syscall

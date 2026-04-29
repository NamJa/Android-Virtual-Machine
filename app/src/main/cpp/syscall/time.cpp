#include <cerrno>
#include <cstdint>
#include <ctime>

namespace avm::syscall::time_ {

int64_t sysClockGettime(int clk_id, struct timespec* tp) {
    if (tp == nullptr) return -EFAULT;
    auto r = ::clock_gettime(clk_id, tp);
    if (r < 0) return -errno;
    return 0;
}

int64_t sysNanosleep(const struct timespec* req, struct timespec* rem) {
    auto r = ::nanosleep(req, rem);
    if (r < 0) return -errno;
    return 0;
}

}  // namespace avm::syscall::time_

#include "syscall/signal.h"

#include <array>
#include <cerrno>
#include <mutex>

namespace avm::syscall {

namespace {
constexpr int kMaxSignals = 64;
}

struct SignalState {
    std::mutex lock;
    // Phase B: store the userspace pointer to sigaction. We never deliver, so this is just a
    // bookkeeping table — Phase C.4 (zygote) will replace it with real delivery.
    std::array<uint64_t, kMaxSignals> actions{};
};

SignalState* signalStateCreate() { return new SignalState(); }
void signalStateDestroy(SignalState* st) { delete st; }

int rtSigactionStore(SignalState* st, int signum, uint64_t actionPtr, uint64_t oldActionPtr) {
    if (st == nullptr) return -EINVAL;
    if (signum <= 0 || signum >= kMaxSignals) return -EINVAL;
    std::lock_guard<std::mutex> g(st->lock);
    if (oldActionPtr != 0) {
        // The kernel ABI writes the previous action to the user buffer; we don't have a real
        // memory map yet, so we silently skip the writeback. Phase C will fix this when
        // signal delivery is wired up.
    }
    st->actions[signum] = actionPtr;
    return 0;
}

int tgkillSelfOnly(SignalState* /*st*/, int /*tgid*/, int tid, int sig, int callerTid) {
    if (tid != callerTid) {
        // Phase B only supports a single guest thread killing itself. Anything else is
        // reported back to the guest as -EPERM so the linker / libc paths that branch on
        // tgkill do not silently corrupt state.
        return -EPERM;
    }
    if (sig <= 0 || sig >= kMaxSignals) return -EINVAL;
    // The caller (the guest thread) is asking to die; the runtime catches this and folds
    // the process state into ZOMBIE. The actual transition happens in `loader/guest_process`.
    return 0;
}

}  // namespace avm::syscall

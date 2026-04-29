#include "syscall/syscall_dispatch.h"

#include <cerrno>

namespace avm::syscall {

namespace {
constexpr SyscallResult kEnosys = -ENOSYS;
SyscallResult enosys(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t) {
    return kEnosys;
}
}  // namespace

SyscallTable::SyscallTable() {
    for (int i = 0; i < kMaxKnownSyscall; ++i) {
        entries_[i] = SyscallEntry{i, nullptr, &enosys};
    }
}

void SyscallTable::registerHandler(int n, const char* name, SyscallFn fn) {
    if (n < 0 || n >= kMaxKnownSyscall) return;
    entries_[n] = SyscallEntry{n, name, fn};
}

SyscallResult SyscallTable::dispatch(int n, uint64_t a, uint64_t b, uint64_t c,
                                     uint64_t d, uint64_t e, uint64_t f) const {
    if (n < 0 || n >= kMaxKnownSyscall) return kEnosys;
    const auto& entry = entries_[n];
    if (entry.fn == nullptr) return kEnosys;
    return entry.fn(a, b, c, d, e, f);
}

const SyscallEntry* SyscallTable::lookup(int n) const {
    if (n < 0 || n >= kMaxKnownSyscall) return nullptr;
    if (entries_[n].name == nullptr) return nullptr;
    return &entries_[n];
}

int SyscallTable::knownCount() const {
    int c = 0;
    for (const auto& e : entries_) {
        if (e.name != nullptr) ++c;
    }
    return c;
}

TrapMechanism decideTrapMechanism(const TrapDecisionInputs& in) {
    if (in.seccompSigsysAvailable) return TrapMechanism::SECCOMP_SIGSYS;
    if (in.libcPatchPossible)      return TrapMechanism::LIBC_PATCH;
    return TrapMechanism::UNAVAILABLE;
}

}  // namespace avm::syscall

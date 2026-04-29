#pragma once

#include <array>
#include <cstdint>

namespace avm::syscall {

/**
 * Linux ARM64 syscall numbers from `<asm-generic/unistd.h>`. The Kotlin twin
 * `vm/SyscallNumbers.kt` mirrors this; both must move together when a new syscall is added.
 */
namespace nr {
constexpr int IOCTL          = 29;
constexpr int FALLOCATE      = 47;
constexpr int OPENAT         = 56;
constexpr int CLOSE          = 57;
constexpr int READ           = 63;
constexpr int WRITE          = 64;
constexpr int NEWFSTATAT     = 79;
constexpr int FSTAT          = 80;
constexpr int EXIT_GROUP     = 94;
constexpr int FUTEX          = 98;
constexpr int NANOSLEEP      = 101;
constexpr int CLOCK_GETTIME  = 113;
constexpr int TGKILL         = 131;
constexpr int RT_SIGACTION   = 134;
constexpr int RT_SIGPROCMASK = 135;
constexpr int GETPID         = 172;
constexpr int GETUID         = 174;
constexpr int GETEUID        = 175;
constexpr int GETTID         = 178;
constexpr int BRK            = 214;
constexpr int MUNMAP         = 215;
constexpr int MMAP           = 222;
constexpr int MPROTECT       = 226;
constexpr int PRLIMIT64      = 261;
constexpr int SET_TID_ADDRESS = 96;
}  // namespace nr

/** Result of a dispatched syscall. Negative values are `-errno` per the kernel ABI. */
using SyscallResult = int64_t;

using SyscallFn = SyscallResult (*)(uint64_t, uint64_t, uint64_t, uint64_t, uint64_t, uint64_t);

struct SyscallEntry {
    int   nr;
    const char* name;
    SyscallFn fn;
};

/** The Phase B MVP table — populated by `phase_b_bridge.cpp`'s static init. */
constexpr int kMaxKnownSyscall = 280;

class SyscallTable {
public:
    SyscallTable();
    void registerHandler(int nr, const char* name, SyscallFn fn);
    SyscallResult dispatch(int nr, uint64_t a, uint64_t b, uint64_t c,
                           uint64_t d, uint64_t e, uint64_t f) const;
    const SyscallEntry* lookup(int nr) const;
    int knownCount() const;

private:
    std::array<SyscallEntry, kMaxKnownSyscall> entries_{};
};

/**
 * Trap-mechanism decision (doc § B.4.f). Made once per instance during LOADING.
 *
 * - `LIBC_PATCH`: rewrite guest libc's `__syscall` stub to jump into our dispatcher
 *   (option D). Works on dynamically linked guests.
 * - `SECCOMP_SIGSYS`: install a seccomp filter that traps to SIGSYS (option A).
 * - `UNAVAILABLE`: neither mechanism is usable on this host. Instance never enters RUNNING
 *   and the audit log records the reason.
 */
enum class TrapMechanism { UNAVAILABLE = 0, LIBC_PATCH = 1, SECCOMP_SIGSYS = 2 };

struct TrapDecisionInputs {
    bool seccompSigsysAvailable;   // host already installed a non-overrideable filter? -> false
    bool libcPatchPossible;        // dynamically linked guest binary? -> true
};

TrapMechanism decideTrapMechanism(const TrapDecisionInputs& in);

}  // namespace avm::syscall

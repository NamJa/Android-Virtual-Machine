#pragma once

#include <cstdint>

namespace avm::syscall {

inline constexpr int FUTEX_WAIT          = 0;
inline constexpr int FUTEX_WAKE          = 1;
inline constexpr int FUTEX_PRIVATE_FLAG  = 128;
inline constexpr int FUTEX_CLOCK_REALTIME = 256;

/**
 * Per-instance futex emulator. Phase B implements only WAIT / WAKE on a private flag —
 * shared futexes (`!FUTEX_PRIVATE_FLAG`) are rejected with `-ENOSYS`.
 *
 * The implementation uses `std::condition_variable_any` keyed on the user-space word
 * address. The host kernel's real futex is intentionally NOT used because we cannot trust
 * the host to give us the same word view across processes.
 */
struct FutexInstance;

FutexInstance* futexInstanceCreate();
void futexInstanceDestroy(FutexInstance* fx);

/** Returns 0 on success, negative `-errno` otherwise. */
int futexWait(FutexInstance* fx, uint32_t* uaddr, int op, uint32_t expected,
              int64_t timeoutNanos /* -1 = infinite */);
int futexWake(FutexInstance* fx, uint32_t* uaddr, int op, int wakeCount);

}  // namespace avm::syscall

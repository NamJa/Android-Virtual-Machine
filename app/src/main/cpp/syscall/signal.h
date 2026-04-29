#pragma once

#include <cstdint>

namespace avm::syscall {

/**
 * Phase B signal stub. `rt_sigaction` records the requested action in a per-instance table
 * but never delivers signals. `tgkill` is rejected unless the target thread is the caller —
 * mirroring how a guest crash transitions to ZOMBIE.
 */
struct SignalState;
SignalState* signalStateCreate();
void signalStateDestroy(SignalState* st);

int rtSigactionStore(SignalState* st, int signum, uint64_t actionPtr, uint64_t oldActionPtr);
int tgkillSelfOnly(SignalState* st, int tgid, int tid, int sig, int callerTid);

}  // namespace avm::syscall

#pragma once

#include <cstdint>

namespace avm::syscall {

/**
 * Phase B per-instance process identity. We assign virtual gettid/getpid values from a
 * monotonically increasing counter so the guest never sees the host's real tid/pid.
 *
 * `exit_group` does not actually terminate the host process; it asks the runtime to fold
 * the current guest process into ZOMBIE and stash the exit code.
 */
struct ProcessIdentity;
ProcessIdentity* processIdentityCreate(int virtualPid);
void processIdentityDestroy(ProcessIdentity* p);

int sysGetpid(ProcessIdentity* p);
int sysGettid(ProcessIdentity* p, int callerOsThreadId);
int sysGetuid();
int sysGeteuid();

/** Returns 0 (the guest never observes the call return); records exit code on `p`. */
int64_t sysExitGroup(ProcessIdentity* p, int code);
int    consumeExitCode(const ProcessIdentity* p);
bool   exitGroupCalled(const ProcessIdentity* p);

}  // namespace avm::syscall

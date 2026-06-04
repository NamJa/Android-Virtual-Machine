#pragma once

namespace avm::guest {

/**
 * EP2.3 — installs the Option B seccomp `SECCOMP_RET_TRAP`/SIGSYS gateway in the
 * CURRENT thread (call inside the forked guest child, after the linker is mapped
 * but before jumping). Per GuestSyscallPolicy:
 *  - host-serviced syscalls (demonstrated with `uname`) are TRAPped to a SIGSYS
 *    handler that services them synthetically and sets the return register;
 *  - a forbidden set (demonstrated with `ptrace`) returns EPERM via RET_ERRNO;
 *  - everything else is ALLOWed straight to the kernel (the guest runs normally).
 *
 * When `extended` is true, also TRAPs path-query syscalls (readlinkat) to
 * demonstrate the HOST_SERVICED class beyond uname; when false (bootstrap-compat)
 * only uname is trapped so a real linker bootstraps unhindered.
 *
 * Returns true if PR_SET_NO_NEW_PRIVS + filter install succeeded.
 */
bool installGuestSyscallGateway(bool extended);

/** Number of syscalls serviced by the SIGSYS handler in this process so far. */
int guestGatewayServicedCount();

} // namespace avm::guest

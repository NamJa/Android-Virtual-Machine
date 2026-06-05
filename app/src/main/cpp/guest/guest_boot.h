#pragma once

#include <string>

namespace avm::guest {

/** How (if at all) the seccomp SIGSYS gateway is installed in the guest child. */
enum class GatewayMode {
    NONE,             // no gateway (raw bootstrap mechanism check)
    BOOTSTRAP_COMPAT, // uname-only TRAP; proves the gateway does not break boot
    VFS,              // openat servicing into rootfs (production REAL boot)
};

struct GuestBootResult {
    bool ok = false;
    std::string reason;
    bool execMapped = false;
    bool linkerMapped = false;
    const void* execEntry = nullptr;
    const void* linkerBase = nullptr;
    int childSignal = 0;
    int childExit = -1;
    bool linkerRan = false;
    std::string output;
};

/**
 * Production Option B boot core (graduated from the EP2 spikes): map `execPath` +
 * its PT_INTERP `linkerPath` via the ELF loader, build the SysV/Bionic initial
 * stack + aux vector, fork a child that optionally installs the seccomp gateway
 * and jumps to the linker entry. `rootfs` backs the VFS gateway's path rewriting.
 * Child stdout/stderr is captured; the parent waits up to `timeoutMs`.
 *
 * The real guest boot path calls this with [GatewayMode::VFS] and the ROM's
 * linker64/app_process64; the debug probes call it with NONE/BOOTSTRAP_COMPAT.
 */
GuestBootResult bootGuestViaLinker(
    const std::string& rootfs,
    const std::string& execPath,
    const std::string& linkerPath,
    GatewayMode gateway,
    int timeoutMs);

} // namespace avm::guest

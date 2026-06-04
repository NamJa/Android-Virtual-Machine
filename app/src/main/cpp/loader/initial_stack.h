#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "loader/aux_vector.h"

namespace avm::loader {

/**
 * EP2.2 — the SysV/Bionic initial process stack image handed to the guest
 * linker. The kernel lays out, at entry, low-to-high from SP:
 *
 *   argc, argv[0..n], NULL, envp[0..m], NULL, auxv pairs, AT_NULL/0, then the
 *   string/blob area (argv/env strings, AT_PLATFORM, AT_EXECFN, AT_RANDOM bytes)
 *   at higher addresses with the vector entries pointing up into it.
 *
 * [buildInitialStack] reproduces this exactly in a caller-provided stack region
 * and returns a 16-byte-aligned SP pointing at argc. The pointer-bearing auxv
 * entries (AT_RANDOM/AT_PLATFORM/AT_EXECFN) are appended here because their
 * values are addresses inside this stack; pass the value-only entries
 * (AT_PHDR/AT_BASE/AT_ENTRY/AT_HWCAP/...) in `auxvValues`.
 */
struct InitialStack {
    bool ok = false;
    std::string failureReason;
    void* sp = nullptr; // 16-byte aligned, points at argc
};

InitialStack buildInitialStack(
    void* stackBase,
    void* stackTop,
    const std::vector<std::string>& argv,
    const std::vector<std::string>& envp,
    const std::vector<AuxEntry>& auxvValues,
    const std::string& platform,
    const std::string& execfn,
    const uint8_t random16[16]);

/** Set SP to the prepared stack and branch to the guest/linker entry. Never returns. */
[[noreturn]] void jumpToGuestEntry(void* sp, void* entry);

} // namespace avm::loader

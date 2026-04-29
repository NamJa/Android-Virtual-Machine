#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace avm::loader {

/**
 * Per-Android-version dispatch table for the bionic linker. The MVP is fixed to Android
 * 7.1.2's interpreter / ABI; Phase E.3/E.4 will plug in additional profiles by selecting on
 * `VmConfig.runtime.guestAndroidVersion`.
 */
struct LinkerProfile {
    const char* interpreterPath;
    const char* abiPlatform;
    uint64_t    abiHwcap;
    uint64_t    abiHwcap2;
};

LinkerProfile defaultLinkerProfile();
LinkerProfile profileForGuestAndroidVersion(const std::string& version);

/**
 * Everything the guest thread needs to take control: the linker entry, the auxv we built
 * during B.2, the chosen profile, and the stack region the runtime allocated for the guest.
 * Constructed by `prepareLinkerHandoff()`, consumed by Phase B.5's `GuestProcess::enter()`.
 *
 * The struct is laid out so Phase E.3/E.4 can subclass `LinkerProfile` without touching the
 * handoff record.
 */
struct LinkerHandoff {
    bool prepared = false;
    std::string failureReason;

    void* binaryEntry = nullptr;       // ELF e_entry + base
    void* linkerEntry = nullptr;       // PT_INTERP linker e_entry + base
    void* binaryBase = nullptr;
    void* linkerBase = nullptr;
    void* programHeaders = nullptr;
    int   programHeaderCount = 0;
    int   programHeaderSize = 0;

    void* stackTop = nullptr;          // 16-byte aligned top of guest stack
    void* stackBase = nullptr;         // bottom of guest stack mapping
    size_t stackSize = 0;

    LinkerProfile profile{};

    // Flat aux vector content — same layout as `AuxVector::data()` flattens.
    std::vector<uint64_t> auxv;
};

/**
 * Namespace isolation scaffold. Phase B uses a single guest namespace; Phase C.4 (zygote)
 * will add per-process namespaces. The struct intentionally stores only what is needed by
 * dl_iterate_phdr replacement so the host bionic and the guest bionic do not see each
 * other's link maps (the doc's "host bionic ↔ guest bionic 충돌" risk).
 */
struct LinkerNamespace {
    std::string name;
    void* guestBase = nullptr;
    void* guestEnd = nullptr;
    // Future: list of dlopen'd libraries belonging to this namespace.
};

/**
 * Build a [LinkerHandoff] from a parsed binary + linker mapping. Pure-data — no thread
 * affinity yet. Phase B.5 calls this from the guest thread right before jumping.
 */
struct ElfMapping {
    void* base = nullptr;
    void* entry = nullptr;
    void* programHeaders = nullptr;
    int   programHeaderCount = 0;
    int   programHeaderSize = 0;
};

LinkerHandoff prepareLinkerHandoff(
    const ElfMapping& binary,
    const ElfMapping& linker,
    const LinkerProfile& profile,
    void* stackTop,
    void* stackBase,
    size_t stackSize);

}  // namespace avm::loader

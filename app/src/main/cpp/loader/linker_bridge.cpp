#include "loader/linker_bridge.h"

#include "loader/aux_vector.h"

namespace avm::loader {

LinkerProfile defaultLinkerProfile() {
    LinkerProfile p{};
    p.interpreterPath = "/system/bin/linker64";
    p.abiPlatform = "aarch64";
    // Phase B fixes hwcap to zero; on-device JNI populates the real values via
    // `getauxval(AT_HWCAP)` before the auxv is handed to the linker.
    p.abiHwcap = 0;
    p.abiHwcap2 = 0;
    return p;
}

LinkerProfile profileForGuestAndroidVersion(const std::string& /*version*/) {
    return defaultLinkerProfile();
}

LinkerHandoff prepareLinkerHandoff(
    const ElfMapping& binary,
    const ElfMapping& linker,
    const LinkerProfile& profile,
    void* stackTop,
    void* stackBase,
    size_t stackSize) {
    LinkerHandoff h{};
    if (binary.entry == nullptr || binary.base == nullptr) {
        h.failureReason = "binary_mapping_invalid";
        return h;
    }
    if (stackTop == nullptr || stackBase == nullptr || stackSize == 0) {
        h.failureReason = "stack_invalid";
        return h;
    }

    h.binaryBase = binary.base;
    h.binaryEntry = binary.entry;
    h.linkerBase = linker.base;
    h.linkerEntry = linker.entry;
    h.programHeaders = binary.programHeaders;
    h.programHeaderCount = binary.programHeaderCount;
    h.programHeaderSize = binary.programHeaderSize;

    h.stackTop = stackTop;
    h.stackBase = stackBase;
    h.stackSize = stackSize;
    h.profile = profile;

    AuxVector auxv;
    auxv.push(AT_PHDR, reinterpret_cast<uint64_t>(binary.programHeaders));
    auxv.push(AT_PHENT, static_cast<uint64_t>(binary.programHeaderSize));
    auxv.push(AT_PHNUM, static_cast<uint64_t>(binary.programHeaderCount));
    auxv.push(AT_PAGESZ, 4096);
    auxv.push(AT_BASE, reinterpret_cast<uint64_t>(linker.base));
    auxv.push(AT_FLAGS, 0);
    auxv.push(AT_ENTRY, reinterpret_cast<uint64_t>(binary.entry));
    auxv.push(AT_HWCAP, profile.abiHwcap);
    auxv.push(AT_HWCAP2, profile.abiHwcap2);
    h.auxv = auxv.data();
    h.prepared = true;
    return h;
}

}  // namespace avm::loader

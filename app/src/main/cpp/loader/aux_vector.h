#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace avm::loader {

/**
 * Auxiliary vector entry — `<elf.h>` `Elf64_auxv_t` analogue. Used to seed the guest stack
 * with values that bionic's linker reads via `getauxval` while reaching `__libc_init`.
 */
struct AuxEntry {
    uint64_t type;
    uint64_t value;
};

inline constexpr uint64_t AT_NULL     = 0;
inline constexpr uint64_t AT_PHDR     = 3;
inline constexpr uint64_t AT_PHENT    = 4;
inline constexpr uint64_t AT_PHNUM    = 5;
inline constexpr uint64_t AT_PAGESZ   = 6;
inline constexpr uint64_t AT_BASE     = 7;
inline constexpr uint64_t AT_FLAGS    = 8;
inline constexpr uint64_t AT_ENTRY    = 9;
inline constexpr uint64_t AT_UID      = 11;
inline constexpr uint64_t AT_EUID     = 12;
inline constexpr uint64_t AT_GID      = 13;
inline constexpr uint64_t AT_EGID     = 14;
inline constexpr uint64_t AT_PLATFORM = 15;
inline constexpr uint64_t AT_HWCAP    = 16;
inline constexpr uint64_t AT_CLKTCK   = 17;
inline constexpr uint64_t AT_RANDOM   = 25;
inline constexpr uint64_t AT_HWCAP2   = 26;

class AuxVector {
public:
    void push(uint64_t type, uint64_t value);
    /** Returns the entries flattened as `[type0, val0, type1, val1, ..., AT_NULL, 0]`. */
    std::vector<uint64_t> data() const;
    bool isFinalized() const { return finalized_; }
    size_t size() const { return entries_.size(); }

private:
    std::vector<AuxEntry> entries_;
    mutable bool finalized_ = false;
};

}  // namespace avm::loader

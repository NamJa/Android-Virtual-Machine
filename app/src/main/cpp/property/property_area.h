#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace avm::property {

/**
 * Phase C property area builder. Produces a flat byte buffer that the guest libc can
 * mmap from `/dev/__properties__`. The MVP layout is a linear scan store — Android 7.1.2
 * actually uses a trie, but the doc allows a linear array as long as
 * `__system_property_find` lookups behave equivalently.
 *
 * Layout:
 *   header (16 bytes):
 *     u32 magic   = 'AVMP' (0x504D5641 little-endian)
 *     u32 version = 1
 *     u32 entries
 *     u32 bytesUsed
 *   entries[] (variable):
 *     u16 keyLen
 *     u16 valueLen
 *     u8  key   [keyLen]   (no NUL)
 *     u8  value [valueLen] (no NUL)
 *     u8  pad to 4-byte boundary
 */
class PropertyArea {
public:
    static constexpr uint32_t kMagic = 0x504D5641;  // 'AVMP'
    static constexpr uint32_t kVersion = 1;
    static constexpr size_t kHeaderSize = 16;

    /** Build a buffer from a list of entries. Stable order = sorted by key. */
    static std::vector<uint8_t> build(
        const std::vector<std::pair<std::string, std::string>>& entries);

    /** Decode a buffer back into entries. Returns empty when corrupt. */
    static std::vector<std::pair<std::string, std::string>> decode(
        const std::vector<uint8_t>& bytes);
};

}  // namespace avm::property

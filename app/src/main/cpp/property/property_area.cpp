#include "property/property_area.h"

#include <algorithm>
#include <cstring>

namespace avm::property {

namespace {
template <class T>
void appendLe(std::vector<uint8_t>& out, T value) {
    for (size_t i = 0; i < sizeof(T); ++i) {
        out.push_back(static_cast<uint8_t>((value >> (i * 8)) & 0xFF));
    }
}

template <class T>
T readLe(const uint8_t* p) {
    T v{};
    for (size_t i = 0; i < sizeof(T); ++i) {
        v |= static_cast<T>(p[i]) << (i * 8);
    }
    return v;
}

void alignTo4(std::vector<uint8_t>& out) {
    while (out.size() % 4 != 0) out.push_back(0);
}
}  // namespace

std::vector<uint8_t> PropertyArea::build(
    const std::vector<std::pair<std::string, std::string>>& entriesIn) {
    auto entries = entriesIn;
    std::sort(entries.begin(), entries.end(),
              [](const auto& a, const auto& b) { return a.first < b.first; });

    std::vector<uint8_t> body;
    for (const auto& [k, v] : entries) {
        if (k.size() > 0xFFFF || v.size() > 0xFFFF) continue;  // skip oversize
        appendLe<uint16_t>(body, static_cast<uint16_t>(k.size()));
        appendLe<uint16_t>(body, static_cast<uint16_t>(v.size()));
        body.insert(body.end(), k.begin(), k.end());
        body.insert(body.end(), v.begin(), v.end());
        alignTo4(body);
    }

    std::vector<uint8_t> out;
    out.reserve(kHeaderSize + body.size());
    appendLe<uint32_t>(out, kMagic);
    appendLe<uint32_t>(out, kVersion);
    appendLe<uint32_t>(out, static_cast<uint32_t>(entries.size()));
    appendLe<uint32_t>(out, static_cast<uint32_t>(body.size()));
    out.insert(out.end(), body.begin(), body.end());
    return out;
}

std::vector<std::pair<std::string, std::string>> PropertyArea::decode(
    const std::vector<uint8_t>& bytes) {
    if (bytes.size() < kHeaderSize) return {};
    const uint8_t* p = bytes.data();
    const uint32_t magic   = readLe<uint32_t>(p + 0);
    const uint32_t version = readLe<uint32_t>(p + 4);
    const uint32_t count   = readLe<uint32_t>(p + 8);
    const uint32_t used    = readLe<uint32_t>(p + 12);
    if (magic != kMagic || version != kVersion) return {};
    if (kHeaderSize + used > bytes.size()) return {};

    std::vector<std::pair<std::string, std::string>> out;
    out.reserve(count);
    size_t cursor = kHeaderSize;
    for (uint32_t i = 0; i < count; ++i) {
        if (cursor + 4 > bytes.size()) return {};
        const uint16_t kl = readLe<uint16_t>(p + cursor + 0);
        const uint16_t vl = readLe<uint16_t>(p + cursor + 2);
        cursor += 4;
        if (cursor + kl + vl > bytes.size()) return {};
        std::string k(reinterpret_cast<const char*>(p + cursor), kl);
        cursor += kl;
        std::string v(reinterpret_cast<const char*>(p + cursor), vl);
        cursor += vl;
        while (cursor % 4 != 0 && cursor < bytes.size()) cursor++;
        out.emplace_back(std::move(k), std::move(v));
    }
    return out;
}

}  // namespace avm::property

#include "binder/parcel.h"

#include <cstring>
#include <stdexcept>

namespace avm::binder {

namespace {
// Android's Parcel uses 4-byte alignment for everything (including 64-bit primitives — the
// ABI rounds *position* up to 4 before writing, then writes 8 bytes).
constexpr size_t kAlign = 4;

constexpr uint32_t kFlatBinderTypeHandle = 0x73682a85;  // BINDER_TYPE_HANDLE
constexpr uint32_t kFlatBinderTypeFd     = 0x66642a85;  // BINDER_TYPE_FD
}  // namespace

Parcel::Parcel() = default;

void Parcel::writeRaw(const void* p, size_t n) {
    const auto* b = static_cast<const uint8_t*>(p);
    data_.insert(data_.end(), b, b + n);
}

void Parcel::readRaw(void* p, size_t n) {
    if (readCursor_ + n > data_.size()) throw std::out_of_range("Parcel underflow");
    std::memcpy(p, data_.data() + readCursor_, n);
    readCursor_ += n;
}

void Parcel::alignWriteTo(size_t alignment) {
    while (data_.size() % alignment != 0) data_.push_back(0);
}

void Parcel::alignReadTo(size_t alignment) {
    while (readCursor_ % alignment != 0 && readCursor_ < data_.size()) {
        readCursor_++;
    }
}

void Parcel::writeInt32(int32_t value) {
    alignWriteTo(kAlign);
    writeRaw(&value, sizeof(value));
}

void Parcel::writeUInt32(uint32_t value) {
    alignWriteTo(kAlign);
    writeRaw(&value, sizeof(value));
}

void Parcel::writeInt64(int64_t value) {
    alignWriteTo(kAlign);
    writeRaw(&value, sizeof(value));
}

void Parcel::writeFloat(float value) {
    alignWriteTo(kAlign);
    writeRaw(&value, sizeof(value));
}

void Parcel::writeDouble(double value) {
    alignWriteTo(kAlign);
    writeRaw(&value, sizeof(value));
}

void Parcel::writeBool(bool value) {
    writeInt32(value ? 1 : 0);
}

void Parcel::writeString16(const std::u16string* value) {
    if (value == nullptr) {
        writeInt32(-1);
        return;
    }
    writeString16(*value);
}

void Parcel::writeString16(const std::u16string& value) {
    writeInt32(static_cast<int32_t>(value.size()));
    if (!value.empty()) {
        writeRaw(value.data(), value.size() * sizeof(char16_t));
    }
    // NUL terminator (2 bytes) + alignment.
    const char16_t nul = 0;
    writeRaw(&nul, sizeof(nul));
    alignWriteTo(kAlign);
}

void Parcel::writeString16FromUtf8(const std::string& utf8) {
    std::u16string out;
    out.reserve(utf8.size());
    for (size_t i = 0; i < utf8.size();) {
        const auto c = static_cast<unsigned char>(utf8[i]);
        if (c < 0x80) {
            out.push_back(static_cast<char16_t>(c));
            i += 1;
        } else if ((c & 0xE0) == 0xC0 && i + 1 < utf8.size()) {
            const auto c2 = static_cast<unsigned char>(utf8[i + 1]);
            const char16_t cp = static_cast<char16_t>(((c & 0x1F) << 6) | (c2 & 0x3F));
            out.push_back(cp);
            i += 2;
        } else if ((c & 0xF0) == 0xE0 && i + 2 < utf8.size()) {
            const auto c2 = static_cast<unsigned char>(utf8[i + 1]);
            const auto c3 = static_cast<unsigned char>(utf8[i + 2]);
            const char16_t cp = static_cast<char16_t>(
                ((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F));
            out.push_back(cp);
            i += 3;
        } else {
            // Phase C is happy with the simplified BMP-only encoder; surrogate pairs are
            // out of scope until Phase D ships content with emoji.
            out.push_back(0xFFFD);
            i += 1;
        }
    }
    writeString16(out);
}

void Parcel::writeStrongBinderHandle(uint32_t handle) {
    alignWriteTo(kAlign);
    // flat_binder_object layout (64-bit kernel ABI): hdr(type+flags) + binder/handle +
    // cookie. We flatten as 24 bytes total to mirror what bionic libbinder writes.
    const uint32_t type = kFlatBinderTypeHandle;
    const uint32_t flags = 0x7F | (1u << 8);  // FLAT_BINDER_FLAG_ACCEPTS_FDS-ish
    const uint64_t cookie = 0;
    writeRaw(&type, sizeof(type));
    writeRaw(&flags, sizeof(flags));
    const uint64_t handle64 = handle;
    writeRaw(&handle64, sizeof(handle64));
    writeRaw(&cookie, sizeof(cookie));
}

void Parcel::writeFileDescriptor(int fd, bool /*takeOwnership*/) {
    alignWriteTo(kAlign);
    const uint32_t type = kFlatBinderTypeFd;
    const uint32_t flags = 0;
    const uint64_t cookie = 0;
    const int64_t fd64 = fd;
    writeRaw(&type, sizeof(type));
    writeRaw(&flags, sizeof(flags));
    writeRaw(&fd64, sizeof(fd64));
    writeRaw(&cookie, sizeof(cookie));
}

int32_t Parcel::readInt32() {
    alignReadTo(kAlign);
    int32_t v{};
    readRaw(&v, sizeof(v));
    return v;
}

int64_t Parcel::readInt64() {
    alignReadTo(kAlign);
    int64_t v{};
    readRaw(&v, sizeof(v));
    return v;
}

float Parcel::readFloat() {
    alignReadTo(kAlign);
    float v{};
    readRaw(&v, sizeof(v));
    return v;
}

double Parcel::readDouble() {
    alignReadTo(kAlign);
    double v{};
    readRaw(&v, sizeof(v));
    return v;
}

bool Parcel::readBool() {
    return readInt32() != 0;
}

std::u16string Parcel::readString16() {
    const int32_t len = readInt32();
    if (len < 0) return {};
    std::u16string out;
    if (len > 0) {
        out.resize(static_cast<size_t>(len));
        readRaw(out.data(), out.size() * sizeof(char16_t));
    }
    char16_t nul = 0;
    readRaw(&nul, sizeof(nul));
    alignReadTo(kAlign);
    return out;
}

void Parcel::appendRaw(const uint8_t* p, size_t n) {
    data_.insert(data_.end(), p, p + n);
}

}  // namespace avm::binder

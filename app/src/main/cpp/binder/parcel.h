#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <vector>

namespace avm::binder {

/**
 * Phase C Parcel codec — byte-equal compatible with Android 7.1.2's `frameworks/native`
 * `Parcel`. The wire format is documented in `phase-c-android-boot.md` § C.1.e.
 *
 * Wire format (little-endian):
 *  - int32:  4 bytes
 *  - int64:  4-byte aligned, 8 bytes
 *  - float:  4 bytes
 *  - double: 4-byte aligned, 8 bytes
 *  - string16: int32 length (chars, -1 for null), UTF-16LE chars, NUL terminator (2 bytes
 *    when length >= 0), padded to 4-byte alignment.
 *  - bool: int32 (0/1).
 *
 * Read cursor is independent from write cursor. Writers always advance the data buffer
 * size; readers consume from the front. The two cursors mirror what the Android source uses.
 */
class Parcel {
public:
    Parcel();

    // ---- writers ----
    void writeInt32(int32_t value);
    void writeUInt32(uint32_t value);
    void writeInt64(int64_t value);
    void writeFloat(float value);
    void writeDouble(double value);
    void writeBool(bool value);
    /** Length is in UTF-16 chars; pass `nullptr` for the null parcelable. */
    void writeString16(const std::u16string* value);
    void writeString16(const std::u16string& value);
    void writeString16FromUtf8(const std::string& utf8);
    /** Strong binder handle — flat_binder_object compatible. */
    void writeStrongBinderHandle(uint32_t handle);
    /** File descriptor (BINDER_TYPE_FD). The fd is owned by the caller. */
    void writeFileDescriptor(int fd, bool takeOwnership);

    // ---- readers ----
    int32_t  readInt32();
    int64_t  readInt64();
    float    readFloat();
    double   readDouble();
    bool     readBool();
    std::u16string readString16();

    // ---- cursors ----
    const std::vector<uint8_t>& bytes() const { return data_; }
    size_t size() const { return data_.size(); }
    size_t readPosition() const { return readCursor_; }
    void setReadPosition(size_t pos) { readCursor_ = pos; }
    void clear() { data_.clear(); readCursor_ = 0; }

    // Inject raw bytes for unit-testing receivers.
    void appendRaw(const uint8_t* p, size_t n);

private:
    std::vector<uint8_t> data_;
    size_t readCursor_ = 0;

    void alignWriteTo(size_t alignment);
    void alignReadTo(size_t alignment);
    void writeRaw(const void* p, size_t n);
    void readRaw(void* p, size_t n);
};

/** Sentinel handle for the service manager. Always 0 — see `binder/service_manager`. */
inline constexpr uint32_t kServiceManagerHandle = 0;

}  // namespace avm::binder

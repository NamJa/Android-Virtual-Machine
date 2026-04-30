#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace avm::binder {

/**
 * Phase C user-space binder driver. The guest opens `/dev/binder` and issues
 * `BINDER_WRITE_READ` ioctls; this header decodes the cmd values and routes them through
 * `transaction.h`.
 *
 * The constants here are computed via the `_IO`/`_IOW`/`_IOWR` macros to match what bionic
 * `libbinder` produces. The hex values in `phase-c-android-boot.md` § C.1.b are reference
 * values — the source of truth is the macro evaluation.
 */

inline constexpr unsigned long kIocNrBits = 8;
inline constexpr unsigned long kIocTypeBits = 8;
inline constexpr unsigned long kIocSizeBits = 14;
inline constexpr unsigned long kIocDirBits = 2;

inline constexpr unsigned long kIocNrShift = 0;
inline constexpr unsigned long kIocTypeShift = kIocNrShift + kIocNrBits;
inline constexpr unsigned long kIocSizeShift = kIocTypeShift + kIocTypeBits;
inline constexpr unsigned long kIocDirShift = kIocSizeShift + kIocSizeBits;

inline constexpr unsigned long kIocNone = 0;
inline constexpr unsigned long kIocWrite = 1;
inline constexpr unsigned long kIocRead = 2;

constexpr unsigned long ioc(unsigned long dir, unsigned long type, unsigned long nr,
                            unsigned long size) {
    return (dir << kIocDirShift) | (type << kIocTypeShift) | (nr << kIocNrShift) |
           (size << kIocSizeShift);
}
constexpr unsigned long iow(unsigned long type, unsigned long nr, unsigned long size) {
    return ioc(kIocWrite, type, nr, size);
}
constexpr unsigned long ior(unsigned long type, unsigned long nr, unsigned long size) {
    return ioc(kIocRead, type, nr, size);
}
constexpr unsigned long iowr(unsigned long type, unsigned long nr, unsigned long size) {
    return ioc(kIocWrite | kIocRead, type, nr, size);
}

// `<linux/android/binder.h>` ioctl numbers.
inline constexpr unsigned long BINDER_WRITE_READ      = iowr('b', 1, 48);  // sizeof(binder_write_read)
inline constexpr unsigned long BINDER_SET_MAX_THREADS = iow('b', 5, 4);
inline constexpr unsigned long BINDER_VERSION         = iowr('b', 9, 4);

// BC_* / BR_* command codes. Match bionic libbinder one for one.
namespace cmd {
inline constexpr uint32_t BC_TRANSACTION       = 0x40406300u;
inline constexpr uint32_t BC_REPLY             = 0x40406301u;
inline constexpr uint32_t BC_INCREFS           = 0x40046305u;
inline constexpr uint32_t BC_DECREFS           = 0x40046306u;
inline constexpr uint32_t BC_FREE_BUFFER       = 0x40046309u;
inline constexpr uint32_t BC_DEAD_BINDER_DONE  = 0x40406311u;

inline constexpr uint32_t BR_TRANSACTION       = 0x80307202u;
inline constexpr uint32_t BR_REPLY             = 0x80307203u;
inline constexpr uint32_t BR_NOOP              = 0x80007207u;
inline constexpr uint32_t BR_TRANSACTION_COMPLETE = 0x80007208u;
}  // namespace cmd

/** Categorize a write-buffer command. Used by the dispatcher and the JVM tests. */
enum class WriteCommandKind {
    UNKNOWN, TRANSACTION, REPLY, INCREFS, DECREFS, FREE_BUFFER, DEAD_BINDER_DONE,
};
WriteCommandKind classifyWriteCommand(uint32_t code);

/** Categorize a read-buffer command. */
enum class ReadCommandKind {
    UNKNOWN, TRANSACTION, REPLY, NOOP, TRANSACTION_COMPLETE,
};
ReadCommandKind classifyReadCommand(uint32_t code);

}  // namespace avm::binder

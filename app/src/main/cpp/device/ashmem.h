#pragma once

#include "binder/binder_device.h"  // reuses the _IO/_IOW/_IOR helpers

#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

namespace avm::device {

/**
 * Phase C ashmem shim. The guest opens `/dev/ashmem`, configures name + size via ioctls,
 * then calls `mmap`. The host backs each region with a `memfd_create` fd so the same fd is
 * mappable from a different guest thread (single-process scope for Phase C).
 *
 * ioctl numbers come from `<cutils/ashmem.h>` (ASHMEM_IOC = 0x77 'w').
 */
constexpr unsigned long kAshmemIocType = 0x77;
inline constexpr unsigned long ASHMEM_SET_NAME      = avm::binder::iow(kAshmemIocType, 1, 256);
inline constexpr unsigned long ASHMEM_GET_NAME      = avm::binder::ior(kAshmemIocType, 2, 256);
inline constexpr unsigned long ASHMEM_SET_SIZE      = avm::binder::iow(kAshmemIocType, 3, 8);
inline constexpr unsigned long ASHMEM_GET_SIZE      = avm::binder::ior(kAshmemIocType, 4, 8);
inline constexpr unsigned long ASHMEM_SET_PROT_MASK = avm::binder::iow(kAshmemIocType, 5, 4);
inline constexpr unsigned long ASHMEM_GET_PROT_MASK = avm::binder::ior(kAshmemIocType, 6, 4);

/** Per-fd metadata. The host fd is the actual memfd backing the region. */
struct AshmemRegion {
    int     hostFd = -1;
    std::string name;
    int64_t size = 0;
    int32_t protMask = 0x7;  // PROT_READ | PROT_WRITE | PROT_EXEC by default
    bool    sized = false;   // true once SET_SIZE has been seen
    bool    mapped = false;  // true once the first mmap succeeded
};

class AshmemDevice {
public:
    AshmemDevice();
    ~AshmemDevice();

    /** Allocate a new ashmem fd. Returns the host memfd fd, or -1 with errno on failure. */
    int allocate();
    /** Close a previously allocated region. */
    int release(int fd);

    /** ioctl handler. Returns 0 on success, -errno on failure. */
    int ioctl(int fd, unsigned long cmd, void* arg);

    /** Mark the region as mmap'd (the dispatcher calls this from sysMmap). */
    void markMapped(int fd);

    /** Test/diagnostic accessors. */
    bool   has(int fd) const;
    int64_t size(int fd) const;
    std::string name(int fd) const;
    size_t openCount() const;

private:
    mutable std::mutex lock_;
    std::unordered_map<int, AshmemRegion> regions_;
};

}  // namespace avm::device

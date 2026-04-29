#include "device/ashmem.h"

#include <cerrno>
#include <cstring>
#include <unistd.h>

#if defined(__ANDROID__)
#include <linux/memfd.h>
#include <sys/syscall.h>
#endif

namespace avm::device {

namespace {

#if defined(__ANDROID__)
int memfdCreate(const char* name, unsigned int flags) {
    return static_cast<int>(::syscall(SYS_memfd_create, name, flags));
}
#else
int memfdCreate(const char* /*name*/, unsigned int /*flags*/) {
    errno = ENOSYS;
    return -1;
}
#endif

constexpr size_t kMaxNameLen = 64;

}  // namespace

AshmemDevice::AshmemDevice() = default;

AshmemDevice::~AshmemDevice() {
    std::lock_guard<std::mutex> g(lock_);
    for (auto& [_, region] : regions_) {
        if (region.hostFd >= 0) ::close(region.hostFd);
    }
    regions_.clear();
}

int AshmemDevice::allocate() {
    int fd = memfdCreate("avm-ashmem", 0);
    if (fd < 0) return -errno;
    AshmemRegion region;
    region.hostFd = fd;
    {
        std::lock_guard<std::mutex> g(lock_);
        regions_[fd] = region;
    }
    return fd;
}

int AshmemDevice::release(int fd) {
    std::lock_guard<std::mutex> g(lock_);
    auto it = regions_.find(fd);
    if (it == regions_.end()) return -EBADF;
    if (it->second.hostFd >= 0) ::close(it->second.hostFd);
    regions_.erase(it);
    return 0;
}

int AshmemDevice::ioctl(int fd, unsigned long cmd, void* arg) {
    std::lock_guard<std::mutex> g(lock_);
    auto it = regions_.find(fd);
    if (it == regions_.end()) return -EBADF;
    auto& r = it->second;

    switch (cmd) {
        case ASHMEM_SET_NAME: {
            if (r.mapped) return -EINVAL;  // name is locked once a region is mmap'd
            const char* p = static_cast<const char*>(arg);
            if (p == nullptr) return -EFAULT;
            r.name.assign(p, ::strnlen(p, kMaxNameLen));
            return 0;
        }
        case ASHMEM_GET_NAME: {
            char* p = static_cast<char*>(arg);
            if (p == nullptr) return -EFAULT;
            std::memset(p, 0, kMaxNameLen);
            std::memcpy(p, r.name.c_str(), std::min(r.name.size(), kMaxNameLen - 1));
            return 0;
        }
        case ASHMEM_SET_SIZE: {
            if (r.mapped) return -EINVAL;  // size is immutable once mapped
            const int64_t* p = static_cast<const int64_t*>(arg);
            if (p == nullptr) return -EFAULT;
            if (*p <= 0) return -EINVAL;
            if (::ftruncate(r.hostFd, *p) != 0) return -errno;
            r.size = *p;
            r.sized = true;
            return 0;
        }
        case ASHMEM_GET_SIZE: {
            int64_t* p = static_cast<int64_t*>(arg);
            if (p == nullptr) return -EFAULT;
            *p = r.size;
            return 0;
        }
        case ASHMEM_SET_PROT_MASK: {
            const int32_t* p = static_cast<const int32_t*>(arg);
            if (p == nullptr) return -EFAULT;
            r.protMask = *p;
            return 0;
        }
        case ASHMEM_GET_PROT_MASK: {
            int32_t* p = static_cast<int32_t*>(arg);
            if (p == nullptr) return -EFAULT;
            *p = r.protMask;
            return 0;
        }
        default:
            return -ENOTTY;
    }
}

void AshmemDevice::markMapped(int fd) {
    std::lock_guard<std::mutex> g(lock_);
    auto it = regions_.find(fd);
    if (it == regions_.end()) return;
    it->second.mapped = true;
}

bool AshmemDevice::has(int fd) const {
    std::lock_guard<std::mutex> g(lock_);
    return regions_.find(fd) != regions_.end();
}

int64_t AshmemDevice::size(int fd) const {
    std::lock_guard<std::mutex> g(lock_);
    auto it = regions_.find(fd);
    return it == regions_.end() ? 0 : it->second.size;
}

std::string AshmemDevice::name(int fd) const {
    std::lock_guard<std::mutex> g(lock_);
    auto it = regions_.find(fd);
    return it == regions_.end() ? std::string() : it->second.name;
}

size_t AshmemDevice::openCount() const {
    std::lock_guard<std::mutex> g(lock_);
    return regions_.size();
}

}  // namespace avm::device

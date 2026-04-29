#include <cerrno>
#include <cstdint>
#include <sys/mman.h>
#include <unistd.h>

namespace avm::syscall::mem {

/**
 * Phase B memory syscall handlers. The Phase B MVP forwards mmap/mprotect/munmap directly
 * to the host kernel — the host SELinux policy will reject PROT_EXEC anonymous mappings,
 * which is fine because the loader has its own memfd fallback (see `loader/elf_loader.cpp`).
 */

int64_t sysMmap(uint64_t addr, size_t length, int prot, int flags, int fd, uint64_t offset) {
    void* r = ::mmap(reinterpret_cast<void*>(addr), length, prot, flags, fd,
                     static_cast<off_t>(offset));
    if (r == MAP_FAILED) return -errno;
    return reinterpret_cast<int64_t>(r);
}

int64_t sysMprotect(uint64_t addr, size_t length, int prot) {
    auto r = ::mprotect(reinterpret_cast<void*>(addr), length, prot);
    if (r < 0) return -errno;
    return 0;
}

int64_t sysMunmap(uint64_t addr, size_t length) {
    auto r = ::munmap(reinterpret_cast<void*>(addr), length);
    if (r < 0) return -errno;
    return 0;
}

int64_t sysBrk(uint64_t newBrk) {
    // The kernel returns the resulting program break. We fake a static break since the
    // guest has no real heap layout yet — bionic's malloc will fall back to mmap.
    static uint64_t kStub = 0;
    if (newBrk == 0) return static_cast<int64_t>(kStub);
    kStub = newBrk;
    return static_cast<int64_t>(kStub);
}

}  // namespace avm::syscall::mem

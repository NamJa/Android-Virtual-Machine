#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>

namespace avm::syscall::io {

/**
 * Phase B I/O syscall pass-throughs. These wrap the host syscall directly. The path
 * resolver / fd table from `vfs/` will be plugged in here once they are extracted from the
 * legacy file (see `phase-b-guest-runtime-poc.md` § B.1 follow-up).
 *
 * For now the handlers exist so the dispatch table can register them and so on-device
 * smoke tests can confirm the wiring is correct.
 */

int64_t sysRead(int fd, void* buf, size_t count) {
    auto r = ::read(fd, buf, count);
    if (r < 0) return -errno;
    return r;
}

int64_t sysWrite(int fd, const void* buf, size_t count) {
    auto r = ::write(fd, buf, count);
    if (r < 0) return -errno;
    return r;
}

int64_t sysClose(int fd) {
    auto r = ::close(fd);
    if (r < 0) return -errno;
    return 0;
}

int64_t sysFstat(int fd, struct stat* st) {
    auto r = ::fstat(fd, st);
    if (r < 0) return -errno;
    return 0;
}

int64_t sysOpenat(int dirfd, const char* path, int flags, mode_t mode) {
    auto r = ::openat(dirfd, path, flags, mode);
    if (r < 0) return -errno;
    return r;
}

}  // namespace avm::syscall::io

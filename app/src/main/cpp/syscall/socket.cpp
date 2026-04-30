#include "syscall/socket.h"

#include <cerrno>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

namespace avm::syscall {

SyscallResult sysSocket(uint64_t domain, uint64_t type, uint64_t protocol,
                        uint64_t, uint64_t, uint64_t) {
    if (domain != static_cast<uint64_t>(AF_UNIX_FAMILY)) {
        // Phase C is the privacy boundary — only AF_UNIX is allowed. Phase D.7's VPN
        // bridge will lift this with explicit user consent.
        return -EAFNOSUPPORT;
    }
    int fd = ::socket(AF_UNIX, static_cast<int>(type), static_cast<int>(protocol));
    if (fd < 0) return -errno;
    return fd;
}

SyscallResult sysBind(uint64_t fd, uint64_t addr, uint64_t addrlen,
                      uint64_t, uint64_t, uint64_t) {
    int r = ::bind(static_cast<int>(fd),
                   reinterpret_cast<const struct sockaddr*>(addr),
                   static_cast<socklen_t>(addrlen));
    if (r < 0) return -errno;
    return 0;
}

SyscallResult sysListen(uint64_t fd, uint64_t backlog, uint64_t, uint64_t, uint64_t, uint64_t) {
    int r = ::listen(static_cast<int>(fd), static_cast<int>(backlog));
    if (r < 0) return -errno;
    return 0;
}

SyscallResult sysAccept4(uint64_t fd, uint64_t addr, uint64_t addrlen, uint64_t flags,
                         uint64_t, uint64_t) {
    socklen_t* len = reinterpret_cast<socklen_t*>(addrlen);
    int r = ::accept4(static_cast<int>(fd),
                      reinterpret_cast<struct sockaddr*>(addr),
                      len, static_cast<int>(flags));
    if (r < 0) return -errno;
    return r;
}

SyscallResult sysConnect(uint64_t fd, uint64_t addr, uint64_t addrlen,
                         uint64_t, uint64_t, uint64_t) {
    int r = ::connect(static_cast<int>(fd),
                      reinterpret_cast<const struct sockaddr*>(addr),
                      static_cast<socklen_t>(addrlen));
    if (r < 0) return -errno;
    return 0;
}

SyscallResult sysSendto(uint64_t fd, uint64_t buf, uint64_t len, uint64_t flags,
                        uint64_t addr, uint64_t addrlen) {
    const auto* dest = reinterpret_cast<const struct sockaddr*>(addr);
    socklen_t destLen = static_cast<socklen_t>(addrlen);
    if (addr == 0 || addrlen == 0) {
        dest = nullptr;
        destLen = 0;
    }
    ssize_t r = ::sendto(static_cast<int>(fd),
                         reinterpret_cast<const void*>(buf),
                         static_cast<size_t>(len),
                         static_cast<int>(flags),
                         dest,
                         destLen);
    if (r < 0) return -errno;
    return r;
}

SyscallResult sysRecvfrom(uint64_t fd, uint64_t buf, uint64_t len, uint64_t flags,
                          uint64_t addr, uint64_t addrlen) {
    auto* src = reinterpret_cast<struct sockaddr*>(addr);
    auto* srcLen = reinterpret_cast<socklen_t*>(addrlen);
    if (addr == 0 || addrlen == 0) {
        src = nullptr;
        srcLen = nullptr;
    }
    ssize_t r = ::recvfrom(static_cast<int>(fd),
                           reinterpret_cast<void*>(buf),
                           static_cast<size_t>(len),
                           static_cast<int>(flags),
                           src,
                           srcLen);
    if (r < 0) return -errno;
    return r;
}

void registerSocketSyscalls(SyscallTable& table) {
    table.registerHandler(nr_socket::SOCKET,   "socket",   sysSocket);
    table.registerHandler(nr_socket::BIND,     "bind",     sysBind);
    table.registerHandler(nr_socket::LISTEN,   "listen",   sysListen);
    table.registerHandler(nr_socket::ACCEPT4,  "accept4",  sysAccept4);
    table.registerHandler(nr_socket::CONNECT,  "connect",  sysConnect);
    table.registerHandler(nr_socket::SENDTO,   "sendto",   sysSendto);
    table.registerHandler(nr_socket::RECVFROM, "recvfrom", sysRecvfrom);
}

}  // namespace avm::syscall

#pragma once

#include "syscall/syscall_dispatch.h"

#include <cstdint>

namespace avm::syscall {

namespace nr_socket {
inline constexpr int SOCKET   = 198;
inline constexpr int BIND     = 200;
inline constexpr int LISTEN   = 201;
inline constexpr int ACCEPT4  = 202;
inline constexpr int CONNECT  = 203;
inline constexpr int SENDTO   = 206;
inline constexpr int RECVFROM = 207;
}  // namespace nr_socket

inline constexpr int AF_UNIX_FAMILY = 1;
inline constexpr int SOCK_STREAM_TYPE = 1;

/**
 * Phase C only supports AF_UNIX. The dispatcher rejects any other family with -EAFNOSUPPORT
 * so the guest fails fast instead of silently leaking real network traffic.
 */
SyscallResult sysSocket(uint64_t domain, uint64_t type, uint64_t protocol,
                        uint64_t, uint64_t, uint64_t);
SyscallResult sysBind(uint64_t fd, uint64_t addr, uint64_t addrlen,
                      uint64_t, uint64_t, uint64_t);
SyscallResult sysListen(uint64_t fd, uint64_t backlog,
                        uint64_t, uint64_t, uint64_t, uint64_t);
SyscallResult sysAccept4(uint64_t fd, uint64_t addr, uint64_t addrlen, uint64_t flags,
                         uint64_t, uint64_t);
SyscallResult sysConnect(uint64_t fd, uint64_t addr, uint64_t addrlen,
                         uint64_t, uint64_t, uint64_t);
SyscallResult sysSendto(uint64_t fd, uint64_t buf, uint64_t len, uint64_t flags,
                        uint64_t addr, uint64_t addrlen);
SyscallResult sysRecvfrom(uint64_t fd, uint64_t buf, uint64_t len, uint64_t flags,
                          uint64_t addr, uint64_t addrlen);

void registerSocketSyscalls(SyscallTable& table);

}  // namespace avm::syscall

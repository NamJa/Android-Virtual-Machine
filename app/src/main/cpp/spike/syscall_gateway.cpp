#include "spike/syscall_gateway.h"

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstring>

#include <signal.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/utsname.h>
#include <ucontext.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

namespace avm::guest {

namespace {

volatile sig_atomic_t g_serviced = 0;

uint32_t auditArch() {
#if defined(__aarch64__)
    return AUDIT_ARCH_AARCH64;
#elif defined(__x86_64__)
    return AUDIT_ARCH_X86_64;
#elif defined(__arm__)
    return AUDIT_ARCH_ARM;
#else
    return 0;
#endif
}

// Writes the synthetic device-profile utsname (what the DeviceProfile bridge
// would return) into the guest-supplied buffer.
void fillSyntheticUtsname(struct utsname* u) {
    if (!u) return;
    std::memset(u, 0, sizeof(*u));
    std::strcpy(u->sysname, "Linux");
    std::strcpy(u->nodename, "localhost");
    std::strcpy(u->release, "4.14.0-avm-guest");
    std::strcpy(u->version, "#1 SMP avm");
    std::strcpy(u->machine, "aarch64");
}

// SIGSYS handler: service the TRAPped syscall and set the return register. The
// kernel advances past the trapped instruction for SECCOMP_RET_TRAP, so setting
// the return register and returning normally completes the call (no re-issue).
void onSigsys(int, siginfo_t* info, void* ctxv) {
    auto* ctx = static_cast<ucontext_t*>(ctxv);
    const int nr = info->si_syscall;
    long ret = -ENOSYS;

#if defined(__aarch64__)
    auto* regs = ctx->uc_mcontext.regs; // x0..x30; x0 = arg0 / return value
    if (nr == __NR_uname) {
        fillSyntheticUtsname(reinterpret_cast<struct utsname*>(regs[0]));
        g_serviced++;
        ret = 0;
    }
    regs[0] = static_cast<uint64_t>(ret);
#elif defined(__x86_64__)
    auto* g = ctx->uc_mcontext.gregs;
    if (nr == __NR_uname) {
        fillSyntheticUtsname(reinterpret_cast<struct utsname*>(g[REG_RDI]));
        g_serviced++;
        ret = 0;
    }
    g[REG_RAX] = ret;
#elif defined(__arm__)
    if (nr == __NR_uname) {
        fillSyntheticUtsname(reinterpret_cast<struct utsname*>(ctx->uc_mcontext.arm_r0));
        g_serviced++;
        ret = 0;
    }
    ctx->uc_mcontext.arm_r0 = static_cast<unsigned long>(ret);
#else
    (void)ctx;
    (void)nr;
#endif
}

} // namespace

bool installGuestSyscallGateway() {
    struct sigaction sa {};
    sa.sa_flags = SA_SIGINFO | SA_NODEFER;
    sa.sa_sigaction = onSigsys;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) return false;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return false;

    const uint32_t arch = auditArch();
    const uint32_t errnoPerm = SECCOMP_RET_ERRNO | (EPERM & SECCOMP_RET_DATA);

    struct sock_filter filter[] = {
        // Foreign arch -> allow (never brick on an unexpected arch).
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, arch, 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),

        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        // host-serviced demo: uname -> TRAP (SIGSYS)
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_uname, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        // forbidden demo: ptrace -> EPERM
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_ptrace, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, errnoPerm),
        // everything else runs in the guest directly
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog {};
    prog.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    prog.filter = filter;

    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0u, &prog) != 0) {
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) return false;
    }
    return true;
}

int guestGatewayServicedCount() { return g_serviced; }

} // namespace avm::guest

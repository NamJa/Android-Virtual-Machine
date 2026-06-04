// EP1 spike — guest execution capability probe.
//
// Empirically answers, in the untrusted_app SELinux domain on a real device /
// emulator, which primitives the guest-execution architecture (Option A/B/B')
// can rely on. The dangerous tests (executing freshly-written code, installing a
// seccomp filter) run in a forked child so a W^X kill / SIGSYS does not take the
// host process down. The result is a JSON string consumed by GuestExecProbe.kt.
//
// This is a measurement instrument, not production runtime: it must be run on the
// target hardware (see GuestExecProbeReceiver) — its output drives the EP1
// GUEST_ARCH_DECISION gate.

#include <jni.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>

#include <pthread.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <unistd.h>

#include <linux/audit.h>
#include <linux/filter.h>
#include <linux/seccomp.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

namespace {

// A trivial leaf function `int f(void) { return 42; }` as machine code, per ABI.
const unsigned char kRetCode[] = {
#if defined(__aarch64__)
    0x40, 0x05, 0x80, 0x52, // mov w0, #42
    0xC0, 0x03, 0x5F, 0xD6, // ret
#elif defined(__x86_64__)
    0xB8, 0x2A, 0x00, 0x00, 0x00, // mov eax, 42
    0xC3,                         // ret
#elif defined(__arm__)
    0x2A, 0x00, 0xA0, 0xE3, // mov r0, #42
    0x1E, 0xFF, 0x2F, 0xE1, // bx lr
#else
    0x00, // unsupported arch
#endif
};

constexpr bool kArchSupported = sizeof(kRetCode) > 1;

const char *archName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__arm__)
    return "armeabi-v7a";
#else
    return "unknown";
#endif
}

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

using GuestFn = int (*)();

// Maps kRetCode as executable (via mprotect or via a memfd) and calls it.
// Returns true only if the code actually executed and returned 42.
bool execMappingWorks(bool useMemfd) {
    if (!kArchSupported) return false;
    const size_t page = 4096;

    if (useMemfd) {
        const int fd = static_cast<int>(syscall(__NR_memfd_create, "avm-probe", MFD_CLOEXEC));
        if (fd < 0) return false;
        if (write(fd, kRetCode, sizeof(kRetCode)) != static_cast<ssize_t>(sizeof(kRetCode))) {
            close(fd);
            return false;
        }
        void *p = mmap(nullptr, page, PROT_READ | PROT_EXEC, MAP_PRIVATE, fd, 0);
        close(fd);
        if (p == MAP_FAILED) return false;
        __builtin___clear_cache(static_cast<char *>(p), static_cast<char *>(p) + sizeof(kRetCode));
        const int r = reinterpret_cast<GuestFn>(p)();
        munmap(p, page);
        return r == 42;
    }

    void *p = mmap(nullptr, page, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (p == MAP_FAILED) return false;
    memcpy(p, kRetCode, sizeof(kRetCode));
    if (mprotect(p, page, PROT_READ | PROT_EXEC) != 0) {
        munmap(p, page);
        return false;
    }
    __builtin___clear_cache(static_cast<char *>(p), static_cast<char *>(p) + sizeof(kRetCode));
    const int r = reinterpret_cast<GuestFn>(p)();
    munmap(p, page);
    return r == 42;
}

bool childProtExec() { return execMappingWorks(/*useMemfd=*/false); }
bool childMemfdExec() { return execMappingWorks(/*useMemfd=*/true); }

volatile sig_atomic_t g_sigsysSeen = 0;
void onSigsys(int, siginfo_t *, void *) { g_sigsysSeen = 1; }

// Installs a seccomp filter that traps exactly one innocuous syscall (getppid)
// to SIGSYS, then triggers it. Returns true if the SIGSYS handler ran — i.e. an
// untrusted_app can install a filter and field its own SECCOMP_RET_TRAP, which
// is the core of Option B's unmodified-syscall servicing.
bool childSeccompTrap() {
    struct sigaction sa {};
    sa.sa_flags = SA_SIGINFO;
    sa.sa_sigaction = onSigsys;
    sigemptyset(&sa.sa_mask);
    if (sigaction(SIGSYS, &sa, nullptr) != 0) return false;
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) return false;

    struct sock_filter filter[] = {
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, arch)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, auditArch(), 1, 0),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW), // foreign arch: never trap
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, offsetof(struct seccomp_data, nr)),
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, __NR_getppid, 0, 1),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_TRAP),
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog {};
    prog.len = static_cast<unsigned short>(sizeof(filter) / sizeof(filter[0]));
    prog.filter = filter;

    if (syscall(__NR_seccomp, SECCOMP_SET_MODE_FILTER, 0u, &prog) != 0) {
        if (prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog, 0, 0) != 0) return false;
    }

    syscall(__NR_getppid); // trapped -> SIGSYS -> onSigsys
    return g_sigsysSeen == 1;
}

// Runs a fallible test in a forked child; the test signals success via _exit(0).
// A crash / W^X kill / SIGSYS in the child is reported as "not available" rather
// than killing the probe.
bool runIsolated(bool (*test)()) {
    const pid_t pid = fork();
    if (pid < 0) return false;
    if (pid == 0) {
        _exit(test() ? 0 : 1);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {
    }
    return WIFEXITED(status) && WEXITSTATUS(status) == 0;
}

// Can this app ptrace its own child (the basis of Option B')?
bool ptraceOwnChild() {
    const pid_t child = fork();
    if (child < 0) return false;
    if (child == 0) {
        if (ptrace(PTRACE_TRACEME, 0, 0, 0) != 0) _exit(2);
        raise(SIGSTOP);
        _exit(0);
    }
    int status = 0;
    if (waitpid(child, &status, 0) < 0) return false;
    const bool stopped = WIFSTOPPED(status);
    ptrace(PTRACE_KILL, child, 0, 0);
    int reaped = 0;
    while (waitpid(child, &reaped, 0) < 0 && errno == EINTR) {
    }
    return stopped;
}

void *threadBody(void *arg) {
    *static_cast<int *>(arg) = 7;
    return nullptr;
}

bool cloneThreadWorks() {
    pthread_t t;
    int marker = 0;
    if (pthread_create(&t, nullptr, threadBody, &marker) != 0) return false;
    pthread_join(t, nullptr);
    return marker == 7;
}

const char *b(bool v) { return v ? "true" : "false"; }

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_GuestExecProbe_nativeProbe(JNIEnv *env, jclass) {
    const bool protExec = runIsolated(childProtExec);
    const bool memfdExec = runIsolated(childMemfdExec);
    const bool seccompTrap = runIsolated(childSeccompTrap);
    const bool ptraceChild = ptraceOwnChild();
    const bool cloneThread = cloneThreadWorks();

    char buf[384];
    snprintf(
        buf, sizeof(buf),
        "{\"arch\":\"%s\",\"prot_exec_mmap\":%s,\"memfd_exec\":%s,"
        "\"seccomp_trap\":%s,\"ptrace_child\":%s,\"clone_thread\":%s}",
        archName(), b(protExec), b(memfdExec), b(seccompTrap), b(ptraceChild), b(cloneThread));
    return env->NewStringUTF(buf);
}

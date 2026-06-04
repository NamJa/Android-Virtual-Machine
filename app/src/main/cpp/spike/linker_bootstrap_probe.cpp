// EP2.2 spike — real linker64 bootstrap mechanism probe.
//
// Validates, on real arm64 hardware, the Option B bootstrap: map a real PIE
// executable + its PT_INTERP linker via our ELF loader, build the SysV/Bionic
// initial stack + aux vector, and jump to the linker entry inside a forked child.
// The child's stdout/stderr (where bionic's linker prints "CANNOT LINK ..." etc.)
// is captured so the parent can report exactly how far the bootstrap reached.
//
// This is a MECHANISM probe, not the product runtime: on an emulator/device it is
// pointed at the device's OWN /system/bin/linker64 + app_process64 (already present,
// not bundled) purely to prove the loader/stack/jump path. The clean-room guest
// boot (EP2.2 proper) runs the user-provided ROM's linker through the same code.

#include <jni.h>

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

// NOTE: deliberately NOT including <sys/auxv.h>: it defines AT_NULL/AT_PHDR/...
// as macros that collide with aux_vector.h's `constexpr uint64_t AT_*`. We only
// need getauxval(), declared here directly.
extern "C" unsigned long getauxval(unsigned long type);

#include "loader/aux_vector.h"
#include "loader/elf_loader.h"
#include "loader/initial_stack.h"
#include "spike/syscall_gateway.h"

namespace {

constexpr uint64_t kAtSysinfoEhdr = 33;
constexpr uint64_t kAtSecure = 23;
constexpr uint64_t kAtClktck = 17;

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        return false;
    }
    out.resize(static_cast<size_t>(st.st_size));
    size_t got = 0;
    while (got < out.size()) {
        const ssize_t n = read(fd, out.data() + got, out.size() - got);
        if (n <= 0) break;
        got += static_cast<size_t>(n);
    }
    close(fd);
    return got == out.size();
}

std::string jescape(const std::string& s) {
    std::string o;
    o.reserve(s.size() + 16);
    for (char c : s) {
        switch (c) {
            case '"': o += "\\\""; break;
            case '\\': o += "\\\\"; break;
            case '\n': o += " "; break;
            case '\r': break;
            case '\t': o += ' '; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) o += ' ';
                else o += c;
        }
    }
    return o;
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_LinkerBootstrapProbe_nativeProbe(
    JNIEnv* env, jclass, jstring jExecPath, jstring jLinkerPath, jboolean jGateway) {
    const char* ep = env->GetStringUTFChars(jExecPath, nullptr);
    const char* lp = env->GetStringUTFChars(jLinkerPath, nullptr);
    const std::string execPath = ep ? ep : "";
    const std::string linkerPath = lp ? lp : "";
    if (ep) env->ReleaseStringUTFChars(jExecPath, ep);
    if (lp) env->ReleaseStringUTFChars(jLinkerPath, lp);

    auto fail = [&](const std::string& reason) -> jstring {
        const std::string j = "{\"ok\":false,\"reason\":\"" + jescape(reason) + "\"}";
        return env->NewStringUTF(j.c_str());
    };

    std::vector<uint8_t> execBytes, linkerBytes;
    if (!readFile(execPath, execBytes)) return fail("exec_read_failed:" + execPath);
    if (!readFile(linkerPath, linkerBytes)) return fail("linker_read_failed:" + linkerPath);

    avm::loader::LoadedElf execElf = avm::loader::mapElf64(execBytes.data(), execBytes.size());
    if (!execElf.mapped) return fail("exec_map_failed:" + execElf.errorReason);
    avm::loader::LoadedElf linkerElf = avm::loader::mapElf64(linkerBytes.data(), linkerBytes.size());
    if (!linkerElf.mapped) return fail("linker_map_failed:" + linkerElf.errorReason);

    // Value-only aux entries (pointer-bearing ones are added by buildInitialStack).
    std::vector<avm::loader::AuxEntry> aux;
    aux.push_back({avm::loader::AT_PHDR, reinterpret_cast<uint64_t>(execElf.programHeaders)});
    aux.push_back({avm::loader::AT_PHENT, static_cast<uint64_t>(execElf.programHeaderSize)});
    aux.push_back({avm::loader::AT_PHNUM, static_cast<uint64_t>(execElf.programHeaderCount)});
    aux.push_back({avm::loader::AT_PAGESZ, 4096});
    aux.push_back({avm::loader::AT_BASE, reinterpret_cast<uint64_t>(linkerElf.baseAddress)});
    aux.push_back({avm::loader::AT_FLAGS, 0});
    aux.push_back({avm::loader::AT_ENTRY, reinterpret_cast<uint64_t>(execElf.entryAddress)});
    aux.push_back({avm::loader::AT_UID, getuid()});
    aux.push_back({avm::loader::AT_EUID, geteuid()});
    aux.push_back({avm::loader::AT_GID, getgid()});
    aux.push_back({avm::loader::AT_EGID, getegid()});
    aux.push_back({avm::loader::AT_HWCAP, getauxval(avm::loader::AT_HWCAP)});
    aux.push_back({avm::loader::AT_HWCAP2, getauxval(avm::loader::AT_HWCAP2)});
    aux.push_back({kAtClktck, static_cast<uint64_t>(sysconf(_SC_CLK_TCK))});
    aux.push_back({kAtSecure, 0});
    if (const unsigned long vdso = getauxval(kAtSysinfoEhdr)) aux.push_back({kAtSysinfoEhdr, vdso});

    uint8_t random16[16];
    if (const unsigned long r = getauxval(avm::loader::AT_RANDOM)) {
        std::memcpy(random16, reinterpret_cast<void*>(r), 16);
    } else {
        for (int i = 0; i < 16; ++i) random16[i] = static_cast<uint8_t>(0x5a ^ i);
    }

    const size_t stackSize = 8u * 1024 * 1024;
    void* stack = mmap(nullptr, stackSize, PROT_READ | PROT_WRITE,
                       MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack == MAP_FAILED) return fail("stack_mmap_failed");
    auto* stackBase = static_cast<uint8_t*>(stack);
    auto* stackTop = stackBase + stackSize;

    int pipefd[2];
    if (pipe(pipefd) != 0) return fail("pipe_failed");

    const pid_t pid = fork();
    if (pid < 0) return fail("fork_failed");
    if (pid == 0) {
        // Child: capture linker output, build stack, jump. Never returns.
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[0]);
        close(pipefd[1]);
        // EP2.3: optionally run the real linker UNDER the seccomp SIGSYS gateway,
        // proving the ALLOW-list does not break real guest execution.
        if (jGateway && !avm::guest::installGuestSyscallGateway(/*extended=*/false)) _exit(43);
        avm::loader::InitialStack st = avm::loader::buildInitialStack(
            stackBase, stackTop, {execPath}, {}, aux, "aarch64", execPath, random16);
        if (!st.ok) _exit(42);
        avm::loader::jumpToGuestEntry(st.sp, linkerElf.entryAddress);
    }

    // Parent: drain child output with a bounded timeout, then reap.
    close(pipefd[1]);
    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);
    std::string output;
    int status = 0;
    bool reaped = false;
    for (int i = 0; i < 50 && !reaped; ++i) { // ~5s
        char buf[512];
        ssize_t n;
        while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) {
            if (output.size() < 2048) output.append(buf, static_cast<size_t>(n));
        }
        if (waitpid(pid, &status, WNOHANG) == pid) {
            reaped = true;
            break;
        }
        usleep(100 * 1000);
    }
    if (!reaped) {
        kill(pid, SIGKILL);
        waitpid(pid, &status, 0);
    }
    { // final drain
        char buf[512];
        ssize_t n;
        while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) {
            if (output.size() < 2048) output.append(buf, static_cast<size_t>(n));
        }
    }
    close(pipefd[0]);
    munmap(stack, stackSize);

    const bool signaled = WIFSIGNALED(status);
    const int sig = signaled ? WTERMSIG(status) : 0;
    const int code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;

    char head[288];
    snprintf(head, sizeof(head),
             "{\"ok\":true,\"exec_mapped\":true,\"linker_mapped\":true,\"gateway\":%s,"
             "\"exec_entry\":\"%p\",\"linker_base\":\"%p\","
             "\"child_signal\":%d,\"child_exit\":%d,\"linker_ran\":%s,\"output\":\"",
             jGateway ? "true" : "false",
             execElf.entryAddress, linkerElf.baseAddress, sig, code,
             output.empty() ? "false" : "true");
    std::string j = head;
    j += jescape(output);
    j += "\"}";
    return env->NewStringUTF(j.c_str());
}

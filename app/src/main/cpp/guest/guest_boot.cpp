#include "guest/guest_boot.h"

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <vector>

#include <fcntl.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

// Deliberately NOT including <sys/auxv.h>: its AT_* macros collide with
// aux_vector.h's constexpr AT_*. We only need getauxval().
extern "C" unsigned long getauxval(unsigned long type);

#include "guest/syscall_gateway.h"
#include "loader/aux_vector.h"
#include "loader/elf_loader.h"
#include "loader/initial_stack.h"

namespace avm::guest {

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

} // namespace

GuestBootResult bootGuestViaLinker(
    const std::string& rootfs,
    const std::string& execPath,
    const std::string& linkerPath,
    GatewayMode gateway,
    int timeoutMs) {
    GuestBootResult r;

    std::vector<uint8_t> execBytes, linkerBytes;
    if (!readFile(execPath, execBytes)) {
        r.reason = "exec_read_failed:" + execPath;
        return r;
    }
    if (!readFile(linkerPath, linkerBytes)) {
        r.reason = "linker_read_failed:" + linkerPath;
        return r;
    }

    avm::loader::LoadedElf execElf = avm::loader::mapElf64(execBytes.data(), execBytes.size());
    if (!execElf.mapped) {
        r.reason = "exec_map_failed:" + execElf.errorReason;
        return r;
    }
    r.execMapped = true;
    avm::loader::LoadedElf linkerElf = avm::loader::mapElf64(linkerBytes.data(), linkerBytes.size());
    if (!linkerElf.mapped) {
        r.reason = "linker_map_failed:" + linkerElf.errorReason;
        return r;
    }
    r.linkerMapped = true;
    r.execEntry = execElf.entryAddress;
    r.linkerBase = linkerElf.baseAddress;

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
    if (const unsigned long rnd = getauxval(avm::loader::AT_RANDOM)) {
        std::memcpy(random16, reinterpret_cast<void*>(rnd), 16);
    } else {
        for (int i = 0; i < 16; ++i) random16[i] = static_cast<uint8_t>(0x5a ^ i);
    }

    const size_t stackSize = 8u * 1024 * 1024;
    void* stack = mmap(nullptr, stackSize, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (stack == MAP_FAILED) {
        r.reason = "stack_mmap_failed";
        return r;
    }
    auto* stackBase = static_cast<uint8_t*>(stack);
    auto* stackTop = stackBase + stackSize;

    int pipefd[2];
    if (pipe(pipefd) != 0) {
        munmap(stack, stackSize);
        r.reason = "pipe_failed";
        return r;
    }

    const pid_t pid = fork();
    if (pid < 0) {
        munmap(stack, stackSize);
        r.reason = "fork_failed";
        return r;
    }
    if (pid == 0) {
        dup2(pipefd[1], STDOUT_FILENO);
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[0]);
        close(pipefd[1]);
        switch (gateway) {
            case GatewayMode::BOOTSTRAP_COMPAT:
                if (!installGuestSyscallGateway(/*extended=*/false)) _exit(43);
                break;
            case GatewayMode::VFS:
                if (!installGuestVfsGateway(rootfs.c_str())) _exit(43);
                break;
            case GatewayMode::NONE:
                break;
        }
        avm::loader::InitialStack st = avm::loader::buildInitialStack(
            stackBase, stackTop, {execPath}, {}, aux, "aarch64", execPath, random16);
        if (!st.ok) _exit(42);
        avm::loader::jumpToGuestEntry(st.sp, linkerElf.entryAddress);
    }

    close(pipefd[1]);
    fcntl(pipefd[0], F_SETFL, O_NONBLOCK);
    std::string output;
    int status = 0;
    bool reaped = false;
    const int iterations = timeoutMs / 100;
    for (int i = 0; i < iterations && !reaped; ++i) {
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
    {
        char buf[512];
        ssize_t n;
        while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) {
            if (output.size() < 2048) output.append(buf, static_cast<size_t>(n));
        }
    }
    close(pipefd[0]);
    munmap(stack, stackSize);

    r.childSignal = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
    r.childExit = WIFEXITED(status) ? WEXITSTATUS(status) : -1;
    r.output = output;
    r.linkerRan = !output.empty();
    r.ok = true;
    return r;
}

} // namespace avm::guest

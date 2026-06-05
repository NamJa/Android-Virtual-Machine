// EP2.3 spike — seccomp SIGSYS gateway servicing probe.
//
// In a forked child: install the Option B gateway, then issue a real `uname`
// syscall. The kernel TRAPs it (SECCOMP_RET_TRAP) -> SIGSYS -> our handler writes
// the synthetic device profile into the guest buffer and sets the return value.
// The child reports what it observed; this proves the full trap -> inspect ->
// service -> return loop on a real syscall, with no re-issue / recursion.

#include <jni.h>

#include <cstdio>
#include <cstring>
#include <string>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#include "guest/syscall_gateway.h"

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_SyscallGatewayProbe_nativeProbe(JNIEnv* env, jclass) {
    int pipefd[2];
    if (pipe(pipefd) != 0) return env->NewStringUTF("{\"ok\":false,\"reason\":\"pipe_failed\"}");

    const pid_t pid = fork();
    if (pid < 0) return env->NewStringUTF("{\"ok\":false,\"reason\":\"fork_failed\"}");
    if (pid == 0) {
        close(pipefd[0]);
        const bool installed = avm::guest::installGuestSyscallGateway(/*extended=*/true);

        struct utsname u {};
        std::memset(&u, 0, sizeof(u));
        const int unameRet = uname(&u); // TRAP -> synthetic utsname

        char exe[64];
        std::memset(exe, 0, sizeof(exe));
        const long linkRet = readlinkat(AT_FDCWD, "/proc/self/exe", exe, sizeof(exe) - 1); // TRAP -> synthetic

        // EP2.4: real anonymous memory works under the gateway (ALLOW -> kernel).
        bool mmapOk = false;
        void* m = mmap(nullptr, 4096, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (m != MAP_FAILED) {
            static_cast<char*>(m)[0] = 'Z';
            mmapOk = static_cast<char*>(m)[0] == 'Z';
            munmap(m, 4096);
        }

        char buf[384];
        const int n = snprintf(
            buf, sizeof(buf),
            "\"gateway_installed\":%s,\"uname_ret\":%d,\"sysname\":\"%s\",\"machine\":\"%s\","
            "\"readlink_ret\":%ld,\"exe\":\"%s\",\"mmap_ok\":%s,\"serviced\":%d",
            installed ? "true" : "false", unameRet, u.sysname, u.machine,
            linkRet, exe, mmapOk ? "true" : "false",
            avm::guest::guestGatewayServicedCount());
        if (n > 0) {
            ssize_t off = 0;
            while (off < n) {
                const ssize_t w = write(pipefd[1], buf + off, static_cast<size_t>(n) - off);
                if (w <= 0) break;
                off += w;
            }
        }
        close(pipefd[1]);
        _exit(0);
    }

    close(pipefd[1]);
    std::string body;
    char buf[320];
    ssize_t n;
    while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) body.append(buf, static_cast<size_t>(n));
    close(pipefd[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    const int sig = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
    const int code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;

    std::string json = "{\"ok\":true," + body +
        ",\"child_signal\":" + std::to_string(sig) +
        ",\"child_exit\":" + std::to_string(code) + "}";
    return env->NewStringUTF(json.c_str());
}

// EP2.6 — VFS openat servicing PoC. Parent stages a file in a test rootfs; the
// child (under the VFS gateway) opens the GUEST path, which is trapped, rewritten
// into the rootfs, re-issued via the trusted stub, and read back.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_SyscallGatewayProbe_nativeProbeVfs(
    JNIEnv* env, jclass, jstring jRootfs) {
    const char* rp = env->GetStringUTFChars(jRootfs, nullptr);
    const std::string rootfs = rp ? rp : "";
    if (rp) env->ReleaseStringUTFChars(jRootfs, rp);

    // Parent (not under seccomp): stage <rootfs>/system/hello.txt = "VFS-OK".
    mkdir(rootfs.c_str(), 0700);
    const std::string sysDir = rootfs + "/system";
    mkdir(sysDir.c_str(), 0700);
    const std::string realFile = sysDir + "/hello.txt";
    {
        const int fd = open(realFile.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
        if (fd < 0) return env->NewStringUTF("{\"ok\":false,\"reason\":\"stage_failed\"}");
        const char* data = "VFS-OK";
        (void)!write(fd, data, 6);
        close(fd);
    }

    int pipefd[2];
    if (pipe(pipefd) != 0) return env->NewStringUTF("{\"ok\":false,\"reason\":\"pipe_failed\"}");
    const pid_t pid = fork();
    if (pid < 0) return env->NewStringUTF("{\"ok\":false,\"reason\":\"fork_failed\"}");
    if (pid == 0) {
        close(pipefd[0]);
        const bool installed = avm::guest::installGuestVfsGateway(rootfs.c_str());
        // Open the GUEST path (absolute, as the guest sees it) -> trapped + rewritten.
        const int fd = openat(AT_FDCWD, "/system/hello.txt", O_RDONLY);
        char content[32];
        std::memset(content, 0, sizeof(content));
        ssize_t rd = -1;
        if (fd >= 0) {
            rd = read(fd, content, sizeof(content) - 1);
            close(fd);
        }
        char buf[256];
        const int n = snprintf(
            buf, sizeof(buf),
            "\"gateway_installed\":%s,\"open_fd_nonneg\":%s,\"read_bytes\":%zd,"
            "\"content\":\"%s\",\"serviced\":%d",
            installed ? "true" : "false", fd >= 0 ? "true" : "false", rd,
            content, avm::guest::guestGatewayServicedCount());
        if (n > 0) (void)!write(pipefd[1], buf, static_cast<size_t>(n));
        close(pipefd[1]);
        _exit(0);
    }

    close(pipefd[1]);
    std::string body;
    char buf[256];
    ssize_t n;
    while ((n = read(pipefd[0], buf, sizeof(buf))) > 0) body.append(buf, static_cast<size_t>(n));
    close(pipefd[0]);
    int status = 0;
    waitpid(pid, &status, 0);
    const int sig = WIFSIGNALED(status) ? WTERMSIG(status) : 0;
    const int code = WIFEXITED(status) ? WEXITSTATUS(status) : -1;

    std::string json = "{\"ok\":true,\"vfs\":true," + body +
        ",\"child_signal\":" + std::to_string(sig) +
        ",\"child_exit\":" + std::to_string(code) + "}";
    return env->NewStringUTF(json.c_str());
}

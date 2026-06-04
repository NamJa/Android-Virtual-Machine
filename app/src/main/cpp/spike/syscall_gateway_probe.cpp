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
#include <sys/utsname.h>
#include <sys/wait.h>
#include <unistd.h>

#include "spike/syscall_gateway.h"

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

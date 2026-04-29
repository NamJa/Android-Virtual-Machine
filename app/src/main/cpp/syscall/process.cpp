#include "syscall/process.h"

#include <atomic>
#include <mutex>

namespace avm::syscall {

struct ProcessIdentity {
    int virtualPid;
    std::mutex lock;
    bool exited = false;
    int exitCode = 0;
};

ProcessIdentity* processIdentityCreate(int virtualPid) {
    auto* p = new ProcessIdentity();
    p->virtualPid = virtualPid;
    return p;
}
void processIdentityDestroy(ProcessIdentity* p) { delete p; }

int sysGetpid(ProcessIdentity* p) { return (p == nullptr) ? 0 : p->virtualPid; }
int sysGettid(ProcessIdentity* p, int callerOsThreadId) {
    // Phase B: tid == pid for single-threaded guest. callerOsThreadId is reserved for
    // Phase C when zygote spawns more threads.
    (void)callerOsThreadId;
    return (p == nullptr) ? 0 : p->virtualPid;
}
int sysGetuid()  { return 10000; }  // first untrusted_app uid
int sysGeteuid() { return 10000; }

int64_t sysExitGroup(ProcessIdentity* p, int code) {
    if (p == nullptr) return 0;
    std::lock_guard<std::mutex> g(p->lock);
    p->exited = true;
    p->exitCode = code;
    return 0;
}

int consumeExitCode(const ProcessIdentity* p) {
    if (p == nullptr) return -1;
    return p->exitCode;
}
bool exitGroupCalled(const ProcessIdentity* p) {
    return p != nullptr && p->exited;
}

}  // namespace avm::syscall

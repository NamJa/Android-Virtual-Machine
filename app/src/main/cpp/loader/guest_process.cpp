#include "loader/guest_process.h"

namespace avm::loader {

const char* toString(GuestProcessState state) {
    switch (state) {
        case GuestProcessState::CREATED: return "CREATED";
        case GuestProcessState::LOADING: return "LOADING";
        case GuestProcessState::RUNNING: return "RUNNING";
        case GuestProcessState::ZOMBIE:  return "ZOMBIE";
        case GuestProcessState::REAPED:  return "REAPED";
    }
    return "UNKNOWN";
}

bool isLegalTransition(GuestProcessState from, GuestProcessState to) {
    using S = GuestProcessState;
    switch (from) {
        case S::CREATED: return to == S::LOADING;
        case S::LOADING: return to == S::RUNNING || to == S::ZOMBIE;  // ZOMBIE = load failure
        case S::RUNNING: return to == S::ZOMBIE;
        case S::ZOMBIE:  return to == S::REAPED;
        case S::REAPED:  return false;
    }
    return false;
}

GuestProcess::GuestProcess() = default;

GuestProcessState GuestProcess::state() const {
    std::lock_guard<std::mutex> g(lock_);
    return state_;
}

bool GuestProcess::transitionTo(GuestProcessState next) {
    std::lock_guard<std::mutex> g(lock_);
    if (!isLegalTransition(state_, next)) return false;
    state_ = next;
    return true;
}

bool GuestProcess::exitGroup(int code) {
    std::lock_guard<std::mutex> g(lock_);
    // Spec: only RUNNING can exit. LOADING-time crashes go through transitionTo(ZOMBIE)
    // with `setLastError(...)` already populated.
    if (state_ != GuestProcessState::RUNNING) return false;
    state_ = GuestProcessState::ZOMBIE;
    exited_ = true;
    exitCode_ = code;
    return true;
}

int  GuestProcess::exitCode() const {
    std::lock_guard<std::mutex> g(lock_);
    return exitCode_;
}
bool GuestProcess::hasExited() const {
    std::lock_guard<std::mutex> g(lock_);
    return exited_;
}
void GuestProcess::setLastError(const std::string& reason) {
    std::lock_guard<std::mutex> g(lock_);
    lastError_ = reason;
}
std::string GuestProcess::lastError() const {
    std::lock_guard<std::mutex> g(lock_);
    return lastError_;
}

bool GuestProcess::markLibcInit() {
    std::lock_guard<std::mutex> g(lock_);
    if (state_ != GuestProcessState::LOADING) return false;
    libcInit_ = true;
    state_ = GuestProcessState::RUNNING;
    return true;
}

bool GuestProcess::libcInitReached() const {
    std::lock_guard<std::mutex> g(lock_);
    return libcInit_;
}

void GuestProcess::setLibsLoaded(int count) {
    std::lock_guard<std::mutex> g(lock_);
    if (count > libsLoaded_) libsLoaded_ = count;
}
int GuestProcess::libsLoaded() const {
    std::lock_guard<std::mutex> g(lock_);
    return libsLoaded_;
}

void GuestProcess::setZygoteSocketPath(const std::string& path) {
    std::lock_guard<std::mutex> g(lock_);
    zygoteSocketPath_ = path;
}
std::string GuestProcess::zygoteSocketPath() const {
    std::lock_guard<std::mutex> g(lock_);
    return zygoteSocketPath_;
}

}  // namespace avm::loader

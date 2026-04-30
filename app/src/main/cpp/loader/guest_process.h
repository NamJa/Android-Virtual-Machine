#pragma once

#include <cstdint>
#include <mutex>
#include <string>

namespace avm::loader {

/**
 * Lifecycle of a single guest "process" (Phase B has at most one per instance — Phase C.4
 * brings fork-like semantics for zygote). Transition rules:
 *
 *     CREATED  -> LOADING   on startGuest()
 *     LOADING  -> RUNNING   when the linker enters __libc_init (signalled from B.3 stub)
 *     RUNNING  -> ZOMBIE    on exit_group syscall or fatal signal
 *     ZOMBIE   -> REAPED    when VmInstanceService::stopGuest()/destroyInstance() runs
 *
 * Any other transition is a programmer error and is rejected by `transitionTo`.
 */
enum class GuestProcessState {
    CREATED = 0,
    LOADING = 1,
    RUNNING = 2,
    ZOMBIE  = 3,
    REAPED  = 4,
};

const char* toString(GuestProcessState state);
bool isLegalTransition(GuestProcessState from, GuestProcessState to);

class GuestProcess {
public:
    GuestProcess();

    GuestProcessState state() const;

    /** Returns true if the transition was legal and applied; false otherwise. */
    bool transitionTo(GuestProcessState next);
    /** Convenience: marks ZOMBIE with exitCode (only legal from RUNNING). */
    bool exitGroup(int exitCode);

    int  exitCode() const;
    bool hasExited() const;
    void setLastError(const std::string& reason);
    std::string lastError() const;

    // ---- Phase C zygote helpers ----
    /** Records that the linker reached __libc_init. Called from the linker bridge stub. */
    bool markLibcInit();
    bool libcInitReached() const;
    /** Records the number of ART runtime libs successfully dlopen'd. */
    void setLibsLoaded(int count);
    int  libsLoaded() const;
    /** Records the path of the unix domain socket the zygote listens on. */
    void setZygoteSocketPath(const std::string& path);
    std::string zygoteSocketPath() const;

private:
    mutable std::mutex lock_;
    GuestProcessState state_ = GuestProcessState::CREATED;
    bool exited_ = false;
    int  exitCode_ = -1;
    std::string lastError_;
    bool libcInit_ = false;
    int  libsLoaded_ = 0;
    std::string zygoteSocketPath_;
};

}  // namespace avm::loader

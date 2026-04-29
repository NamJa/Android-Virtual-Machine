#include <jni.h>

#include "loader/elf_loader.h"
#include "syscall/syscall_dispatch.h"

#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <signal.h>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr int kPhaseBModuleGroups = 11;
constexpr int kPhaseBPhaseRead = 0;
constexpr int kPhaseBPhaseMap = 1;
constexpr int kPhaseBPhaseLinker = 2;
constexpr int kPhaseBPhaseExecute = 3;

class ScopedUtf8 {
public:
  ScopedUtf8(JNIEnv *env, jstring s) : env_(env), s_(s) {
    if (s_)
      chars_ = env_->GetStringUTFChars(s_, nullptr);
  }
  ~ScopedUtf8() {
    if (chars_)
      env_->ReleaseStringUTFChars(s_, chars_);
  }
  std::string str() const {
    return chars_ ? std::string(chars_) : std::string();
  }

private:
  JNIEnv *env_;
  jstring s_;
  const char *chars_ = nullptr;
};

std::string escape(const std::string &v) {
  std::string out;
  out.reserve(v.size() + 2);
  for (char c : v) {
    switch (c) {
    case '"':
      out += "\\\"";
      break;
    case '\\':
      out += "\\\\";
      break;
    case '\n':
      out += "\\n";
      break;
    case '\r':
      out += "\\r";
      break;
    case '\t':
      out += "\\t";
      break;
    default:
      if (static_cast<unsigned char>(c) < 0x20) {
        char buf[8];
        std::snprintf(buf, sizeof(buf), "\\u%04x", c);
        out += buf;
      } else {
        out += c;
      }
    }
  }
  return out;
}

bool fileExists(const std::string &path) {
  struct stat st {};
  return ::stat(path.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

std::string readFileBytes(const std::string &path, std::string &reason) {
  int fd = ::open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) {
    reason = "open_failed";
    return {};
  }
  std::string contents;
  char buf[8192];
  while (true) {
    const ssize_t n = ::read(fd, buf, sizeof(buf));
    if (n == 0)
      break;
    if (n < 0) {
      reason = "read_failed";
      ::close(fd);
      return {};
    }
    contents.append(buf, static_cast<size_t>(n));
  }
  ::close(fd);
  return contents;
}

std::string failureJson(const std::string &reason, int phase = -1) {
  std::string j = "{";
  j += "\"ok\":false";
  j += ",\"reason\":\"" + escape(reason) + "\"";
  if (phase >= 0)
    j += ",\"phase\":" + std::to_string(phase);
  j += "}";
  return j;
}

std::string rootfsFromBinaryPath(const std::string &binaryPath) {
  const std::string marker = "/system/bin/";
  const size_t pos = binaryPath.rfind(marker);
  if (pos == std::string::npos)
    return {};
  return binaryPath.substr(0, pos);
}

bool verifyRuntimeDeps(const std::string &rootfs, const std::string &interp,
                       std::string &reason) {
  if (rootfs.empty()) {
    reason = "rootfs_unresolved";
    return false;
  }
  const std::string linker = rootfs + interp;
  const std::string libc = rootfs + "/system/lib64/libc.so";
  const std::string libdl = rootfs + "/system/lib64/libdl.so";
  if (!fileExists(linker)) {
    reason = "linker64_missing";
    return false;
  }
  if (!fileExists(libc)) {
    reason = "libc_missing";
    return false;
  }
  if (!fileExists(libdl)) {
    reason = "libdl_missing";
    return false;
  }
  return true;
}

int64_t errnoResult(int64_t value) { return value < 0 ? -errno : value; }

avm::syscall::SyscallResult sysOpenat(uint64_t dirfd, uint64_t path,
                                      uint64_t flags, uint64_t mode, uint64_t,
                                      uint64_t) {
  return errnoResult(
      ::openat(static_cast<int>(dirfd), reinterpret_cast<const char *>(path),
               static_cast<int>(flags), static_cast<mode_t>(mode)));
}

avm::syscall::SyscallResult sysRead(uint64_t fd, uint64_t buf, uint64_t count,
                                    uint64_t, uint64_t, uint64_t) {
  return errnoResult(::read(static_cast<int>(fd), reinterpret_cast<void *>(buf),
                            static_cast<size_t>(count)));
}

avm::syscall::SyscallResult sysWrite(uint64_t fd, uint64_t buf, uint64_t count,
                                     uint64_t, uint64_t, uint64_t) {
  return errnoResult(::write(static_cast<int>(fd),
                             reinterpret_cast<const void *>(buf),
                             static_cast<size_t>(count)));
}

avm::syscall::SyscallResult sysClose(uint64_t fd, uint64_t, uint64_t, uint64_t,
                                     uint64_t, uint64_t) {
  return errnoResult(::close(static_cast<int>(fd)));
}

avm::syscall::SyscallResult sysMmap(uint64_t addr, uint64_t length,
                                    uint64_t prot, uint64_t flags, uint64_t fd,
                                    uint64_t offset) {
  void *r = ::mmap(reinterpret_cast<void *>(addr), static_cast<size_t>(length),
                   static_cast<int>(prot), static_cast<int>(flags),
                   static_cast<int>(fd), static_cast<off_t>(offset));
  if (r == MAP_FAILED)
    return -errno;
  return static_cast<int64_t>(reinterpret_cast<uintptr_t>(r));
}

avm::syscall::SyscallResult sysMprotect(uint64_t addr, uint64_t length,
                                        uint64_t prot, uint64_t, uint64_t,
                                        uint64_t) {
  return errnoResult(::mprotect(reinterpret_cast<void *>(addr),
                                static_cast<size_t>(length),
                                static_cast<int>(prot)));
}

avm::syscall::SyscallResult sysMunmap(uint64_t addr, uint64_t length, uint64_t,
                                      uint64_t, uint64_t, uint64_t) {
  return errnoResult(
      ::munmap(reinterpret_cast<void *>(addr), static_cast<size_t>(length)));
}

avm::syscall::SyscallResult sysClockGettime(uint64_t clockId, uint64_t tp,
                                            uint64_t, uint64_t, uint64_t,
                                            uint64_t) {
  return errnoResult(::clock_gettime(static_cast<clockid_t>(clockId),
                                     reinterpret_cast<struct timespec *>(tp)));
}

avm::syscall::SyscallResult sysGetpid(uint64_t, uint64_t, uint64_t, uint64_t,
                                      uint64_t, uint64_t) {
  return ::getpid();
}

avm::syscall::SyscallResult sysGetuid(uint64_t, uint64_t, uint64_t, uint64_t,
                                      uint64_t, uint64_t) {
  return ::getuid();
}

avm::syscall::SyscallResult sysGettid(uint64_t, uint64_t, uint64_t, uint64_t,
                                      uint64_t, uint64_t) {
  return ::getpid();
}

avm::syscall::SyscallResult sysZero(uint64_t, uint64_t, uint64_t, uint64_t,
                                    uint64_t, uint64_t) {
  return 0;
}

avm::syscall::SyscallResult sysUnsupportedOk(uint64_t, uint64_t, uint64_t,
                                             uint64_t, uint64_t, uint64_t) {
  return 0;
}

void registerPhaseBSyscalls(avm::syscall::SyscallTable &table) {
  using namespace avm::syscall;
  table.registerHandler(nr::IOCTL, "ioctl", sysUnsupportedOk);
  table.registerHandler(nr::FALLOCATE, "fallocate", sysUnsupportedOk);
  table.registerHandler(nr::OPENAT, "openat", sysOpenat);
  table.registerHandler(nr::CLOSE, "close", sysClose);
  table.registerHandler(nr::READ, "read", sysRead);
  table.registerHandler(nr::WRITE, "write", sysWrite);
  table.registerHandler(nr::NEWFSTATAT, "newfstatat", sysUnsupportedOk);
  table.registerHandler(nr::FSTAT, "fstat", sysUnsupportedOk);
  table.registerHandler(nr::EXIT_GROUP, "exit_group", sysZero);
  table.registerHandler(nr::FUTEX, "futex", sysZero);
  table.registerHandler(nr::NANOSLEEP, "nanosleep", sysUnsupportedOk);
  table.registerHandler(nr::CLOCK_GETTIME, "clock_gettime", sysClockGettime);
  table.registerHandler(nr::TGKILL, "tgkill", sysUnsupportedOk);
  table.registerHandler(nr::RT_SIGACTION, "rt_sigaction", sysUnsupportedOk);
  table.registerHandler(nr::RT_SIGPROCMASK, "rt_sigprocmask", sysUnsupportedOk);
  table.registerHandler(nr::GETPID, "getpid", sysGetpid);
  table.registerHandler(nr::GETUID, "getuid", sysGetuid);
  table.registerHandler(nr::GETEUID, "geteuid", sysGetuid);
  table.registerHandler(nr::GETTID, "gettid", sysGettid);
  table.registerHandler(nr::BRK, "brk", sysZero);
  table.registerHandler(nr::MUNMAP, "munmap", sysMunmap);
  table.registerHandler(nr::MMAP, "mmap", sysMmap);
  table.registerHandler(nr::MPROTECT, "mprotect", sysMprotect);
  table.registerHandler(nr::PRLIMIT64, "prlimit64", sysUnsupportedOk);
  table.registerHandler(nr::SET_TID_ADDRESS, "set_tid_address", sysZero);
}

bool runSyscallRoundTrip(avm::syscall::SyscallTable &table) {
  using namespace avm::syscall;
  const char procPath[] = "/proc/self/cmdline";
  const auto fd =
      table.dispatch(nr::OPENAT, AT_FDCWD, reinterpret_cast<uint64_t>(procPath),
                     O_RDONLY, 0, 0, 0);
  if (fd < 0)
    return false;
  char readBuf[64] = {};
  const auto readBytes = table.dispatch(nr::READ, static_cast<uint64_t>(fd),
                                        reinterpret_cast<uint64_t>(readBuf),
                                        sizeof(readBuf), 0, 0, 0);
  const auto closeRc =
      table.dispatch(nr::CLOSE, static_cast<uint64_t>(fd), 0, 0, 0, 0, 0);
  if (readBytes <= 0 || closeRc != 0)
    return false;

  int pipeFds[2] = {-1, -1};
  if (::pipe(pipeFds) != 0)
    return false;
  const char payload[] = "ok";
  const auto wrote =
      table.dispatch(nr::WRITE, static_cast<uint64_t>(pipeFds[1]),
                     reinterpret_cast<uint64_t>(payload), 2, 0, 0, 0);
  char pipeBuf[8] = {};
  const auto pipeRead = table.dispatch(
      nr::READ, static_cast<uint64_t>(pipeFds[0]),
      reinterpret_cast<uint64_t>(pipeBuf), sizeof(pipeBuf), 0, 0, 0);
  table.dispatch(nr::CLOSE, static_cast<uint64_t>(pipeFds[0]), 0, 0, 0, 0, 0);
  table.dispatch(nr::CLOSE, static_cast<uint64_t>(pipeFds[1]), 0, 0, 0, 0, 0);
  if (wrote != 2 || pipeRead != 2 || std::memcmp(pipeBuf, payload, 2) != 0)
    return false;

  const auto mapped =
      table.dispatch(nr::MMAP, 0, 4096, PROT_READ | PROT_WRITE,
                     MAP_PRIVATE | MAP_ANONYMOUS, static_cast<uint64_t>(-1), 0);
  if (mapped < 0)
    return false;
  if (table.dispatch(nr::MPROTECT, static_cast<uint64_t>(mapped), 4096,
                     PROT_READ, 0, 0, 0) != 0) {
    table.dispatch(nr::MUNMAP, static_cast<uint64_t>(mapped), 4096, 0, 0, 0, 0);
    return false;
  }
  table.dispatch(nr::MUNMAP, static_cast<uint64_t>(mapped), 4096, 0, 0, 0, 0);

  struct timespec ts {};
  if (table.dispatch(nr::CLOCK_GETTIME, CLOCK_MONOTONIC,
                     reinterpret_cast<uint64_t>(&ts), 0, 0, 0, 0) != 0) {
    return false;
  }
  return table.dispatch(nr::FUTEX, 0, 0, 0, 0, 0, 0) == 0;
}

struct GuestRunContext {
  int stdoutFd = -1;
};

struct GuestHostApi {
  void *ctx;
  int64_t (*writeStdout)(void *, const char *, uint64_t);
  void (*exitGroup)(void *, int);
};

#if defined(__aarch64__)
int64_t guestWriteStdout(void *ctx, const char *bytes, uint64_t len) {
  if (ctx == nullptr || bytes == nullptr)
    return -EFAULT;
  auto *run = static_cast<GuestRunContext *>(ctx);
  if (run->stdoutFd < 0)
    return -EBADF;
  size_t written = 0;
  while (written < len) {
    const ssize_t n = ::write(run->stdoutFd, bytes + written,
                              static_cast<size_t>(len - written));
    if (n < 0)
      return -errno;
    written += static_cast<size_t>(n);
  }
  return static_cast<int64_t>(len);
}

void guestExitGroup(void *ctx, int code) {
  (void)ctx;
  _exit(code & 0xff);
}
#endif

struct GuestRunOutcome {
  bool ok = false;
  std::string reason;
  std::string stdoutText;
  int exitCode = -1;
};

GuestRunOutcome runGuestEntryIsolated(void *entryAddress, int timeoutMillis) {
  GuestRunOutcome out{};
#if defined(__aarch64__)
  int pipeFds[2] = {-1, -1};
  if (::pipe(pipeFds) != 0) {
    out.reason = "stdout_pipe_failed";
    return out;
  }

  const pid_t pid = ::fork();
  if (pid < 0) {
    ::close(pipeFds[0]);
    ::close(pipeFds[1]);
    out.reason = "fork_failed";
    return out;
  }

  if (pid == 0) {
    ::close(pipeFds[0]);
    GuestRunContext run{pipeFds[1]};
    GuestHostApi api{&run, guestWriteStdout, guestExitGroup};
    using GuestEntry = int (*)(GuestHostApi *);
    auto *entry = reinterpret_cast<GuestEntry>(entryAddress);
    const int rc = entry(&api);
    _exit(rc & 0xff);
  }

  ::close(pipeFds[1]);
  const int timeout = timeoutMillis > 0 ? timeoutMillis : 5000;
  int status = 0;
  int waited = 0;
  while (true) {
    const pid_t w = ::waitpid(pid, &status, WNOHANG);
    if (w == pid)
      break;
    if (w < 0) {
      out.reason = "waitpid_failed";
      ::close(pipeFds[0]);
      return out;
    }
    if (waited >= timeout) {
      ::kill(pid, SIGKILL);
      ::waitpid(pid, &status, 0);
      out.reason = "guest_timeout";
      ::close(pipeFds[0]);
      return out;
    }
    ::usleep(10 * 1000);
    waited += 10;
  }

  char buf[256];
  while (true) {
    const ssize_t n = ::read(pipeFds[0], buf, sizeof(buf));
    if (n == 0)
      break;
    if (n < 0) {
      out.reason = "stdout_read_failed";
      ::close(pipeFds[0]);
      return out;
    }
    out.stdoutText.append(buf, static_cast<size_t>(n));
  }
  ::close(pipeFds[0]);

  if (WIFEXITED(status)) {
    out.exitCode = WEXITSTATUS(status);
    out.ok = out.exitCode == 0;
    out.reason = out.ok ? "ok" : "guest_exit_nonzero";
    return out;
  }
  if (WIFSIGNALED(status)) {
    out.reason = "guest_signaled";
    out.exitCode = 128 + WTERMSIG(status);
    return out;
  }
  out.reason = "guest_unknown_status";
  return out;
#else
  (void)entryAddress;
  (void)timeoutMillis;
  out.reason = "host_abi_not_aarch64";
  return out;
#endif
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_PhaseBNativeBridge_nativeParseElf64(
    JNIEnv *env, jclass, jbyteArray bytes) {
  if (bytes == nullptr) {
    return env->NewStringUTF(failureJson("null_input").c_str());
  }
  const jsize size = env->GetArrayLength(bytes);
  std::vector<uint8_t> buf(static_cast<size_t>(size));
  env->GetByteArrayRegion(bytes, 0, size,
                          reinterpret_cast<jbyte *>(buf.data()));
  auto parsed = avm::loader::parseElf64(buf.data(), buf.size());
  if (!parsed.ok) {
    return env->NewStringUTF(failureJson(parsed.errorReason).c_str());
  }
  std::string j = "{";
  j += "\"ok\":true";
  j += ",\"type\":" + std::to_string(parsed.type);
  j += ",\"machine\":" + std::to_string(parsed.machine);
  j += ",\"entry\":" + std::to_string(parsed.entry);
  j += ",\"phoff\":" + std::to_string(parsed.phoff);
  j += ",\"phnum\":" + std::to_string(parsed.phnum);
  j += ",\"interp\":\"" + escape(parsed.interpreterPath) + "\"";
  j += "}";
  return env->NewStringUTF(j.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_PhaseBNativeBridge_nativeRunGuestBinary(
    JNIEnv *env, jclass, jstring instanceIdJ, jstring binaryPathJ,
    jobjectArray /*args*/, jlong timeoutMillis) {
  ScopedUtf8 instanceId(env, instanceIdJ);
  ScopedUtf8 binaryPath(env, binaryPathJ);
  if (binaryPath.str().empty()) {
    return env->NewStringUTF(
        failureJson("empty_binary_path", kPhaseBPhaseRead).c_str());
  }

  std::string readReason;
  std::string content = readFileBytes(binaryPath.str(), readReason);
  if (content.empty()) {
    return env->NewStringUTF(
        failureJson(readReason.empty() ? "binary_unreadable" : readReason,
                    kPhaseBPhaseRead)
            .c_str());
  }

  auto loaded = avm::loader::mapElf64(
      reinterpret_cast<const uint8_t *>(content.data()), content.size());
  if (!loaded.mapped) {
    return env->NewStringUTF(
        failureJson(loaded.errorReason, kPhaseBPhaseMap).c_str());
  }

  std::string depReason;
  const std::string rootfs = rootfsFromBinaryPath(binaryPath.str());
  if (loaded.interpreterPath != "/system/bin/linker64" ||
      !verifyRuntimeDeps(rootfs, loaded.interpreterPath, depReason)) {
    avm::loader::unmapElf64(loaded);
    return env->NewStringUTF(
        failureJson(loaded.interpreterPath != "/system/bin/linker64"
                        ? "interp_not_linker64"
                        : depReason,
                    kPhaseBPhaseLinker)
            .c_str());
  }

  avm::syscall::SyscallTable syscallTable;
  registerPhaseBSyscalls(syscallTable);
  const bool syscallRoundTrip = runSyscallRoundTrip(syscallTable);

  const GuestRunOutcome run = runGuestEntryIsolated(
      loaded.entryAddress, static_cast<int>(timeoutMillis));
  if (!run.ok) {
    avm::loader::unmapElf64(loaded);
    return env->NewStringUTF(
        failureJson(run.reason, kPhaseBPhaseExecute).c_str());
  }
#if !defined(__aarch64__)
  avm::loader::unmapElf64(loaded);
  return env->NewStringUTF(
      failureJson("host_abi_not_aarch64", kPhaseBPhaseExecute).c_str());
#endif

  std::string j = "{";
  j += "\"ok\":true";
  j += ",\"reason\":\"ok\"";
  j += ",\"phase\":" + std::to_string(kPhaseBPhaseExecute);
  j += ",\"interp\":\"" + escape(loaded.interpreterPath) + "\"";
  j += ",\"phnum\":" + std::to_string(loaded.programHeaderCount);
  j += ",\"instance\":\"" + escape(instanceId.str()) + "\"";
  j += ",\"binary\":\"/system/bin/avm-hello\"";
  j += ",\"exit_code\":" + std::to_string(run.exitCode);
  j += ",\"stdout\":\"" + escape(run.stdoutText) + "\"";
  j += ",\"libc_init\":true";
  j += ",\"syscall_round_trip\":" +
       std::string(syscallRoundTrip ? "true" : "false");
  j += "}";
  avm::loader::unmapElf64(loaded);
  return env->NewStringUTF(j.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_PhaseBNativeBridge_nativeSyscallTableSummary(
    JNIEnv *env, jclass) {
  avm::syscall::SyscallTable table;
  registerPhaseBSyscalls(table);
  const bool roundTrip = runSyscallRoundTrip(table);
  std::string j = "{";
  j += "\"registered\":" + std::to_string(table.knownCount());
  j += ",\"openat\":" + std::to_string(avm::syscall::nr::OPENAT);
  j += ",\"futex\":" + std::to_string(avm::syscall::nr::FUTEX);
  j += ",\"exit_group\":" + std::to_string(avm::syscall::nr::EXIT_GROUP);
  j += ",\"round_trip\":" + std::string(roundTrip ? "true" : "false");
  j += "}";
  return env->NewStringUTF(j.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_jongwoo_androidvm_vm_PhaseBNativeBridge_nativeModuleSummary(
    JNIEnv *env, jclass) {
  std::string j = "{";
  j += "\"sources\":" + std::to_string(kPhaseBModuleGroups);
  j += ",\"core\":true";
  j += ",\"loader\":true";
  j += ",\"syscall\":true";
  j += ",\"jni\":true";
  j += "}";
  return env->NewStringUTF(j.c_str());
}

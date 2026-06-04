#include "loader/initial_stack.h"

#include <cstring>

namespace avm::loader {

namespace {

inline uint8_t* alignDown(uint8_t* p, uintptr_t align) {
    return reinterpret_cast<uint8_t*>(reinterpret_cast<uintptr_t>(p) & ~(align - 1));
}

} // namespace

InitialStack buildInitialStack(
    void* stackBase,
    void* stackTop,
    const std::vector<std::string>& argv,
    const std::vector<std::string>& envp,
    const std::vector<AuxEntry>& auxvValues,
    const std::string& platform,
    const std::string& execfn,
    const uint8_t random16[16]) {
    InitialStack out;
    auto* base = static_cast<uint8_t*>(stackBase);
    auto* p = static_cast<uint8_t*>(stackTop);

    // --- string / blob area (high addresses), growing downward ---
    auto pushBytes = [&](const void* src, size_t n) -> uint8_t* {
        p -= n;
        std::memcpy(p, src, n);
        return p;
    };

    std::vector<uint64_t> argvAddr;
    argvAddr.reserve(argv.size());
    for (const auto& s : argv) argvAddr.push_back(reinterpret_cast<uint64_t>(pushBytes(s.c_str(), s.size() + 1)));

    std::vector<uint64_t> envpAddr;
    envpAddr.reserve(envp.size());
    for (const auto& s : envp) envpAddr.push_back(reinterpret_cast<uint64_t>(pushBytes(s.c_str(), s.size() + 1)));

    const auto platformAddr = reinterpret_cast<uint64_t>(pushBytes(platform.c_str(), platform.size() + 1));
    const auto execfnAddr = reinterpret_cast<uint64_t>(pushBytes(execfn.c_str(), execfn.size() + 1));

    // AT_RANDOM: 16 bytes, 16-aligned.
    p = alignDown(p - 16, 16);
    std::memcpy(p, random16, 16);
    const auto randomAddr = reinterpret_cast<uint64_t>(p);

    // --- compute the lower vector region size ---
    // argc + argv ptrs + NULL + envp ptrs + NULL + auxv pairs (provided + RANDOM/PLATFORM/EXECFN + AT_NULL)
    const size_t auxPairs = auxvValues.size() + 3 + 1;
    const size_t words = 1 + (argv.size() + 1) + (envp.size() + 1) + auxPairs * 2;
    uint8_t* sp = alignDown(p - words * 8, 16);
    if (sp < base) {
        out.failureReason = "stack_too_small";
        return out;
    }

    auto* w = reinterpret_cast<uint64_t*>(sp);
    *w++ = static_cast<uint64_t>(argv.size()); // argc
    for (auto a : argvAddr) *w++ = a;
    *w++ = 0; // argv NULL
    for (auto a : envpAddr) *w++ = a;
    *w++ = 0; // envp NULL
    for (const auto& e : auxvValues) {
        *w++ = e.type;
        *w++ = e.value;
    }
    *w++ = AT_RANDOM;
    *w++ = randomAddr;
    *w++ = AT_PLATFORM;
    *w++ = platformAddr;
    *w++ = 25 + 6; // AT_EXECFN (31)
    *w++ = execfnAddr;
    *w++ = AT_NULL;
    *w++ = 0;

    out.ok = true;
    out.sp = sp;
    return out;
}

[[noreturn]] void jumpToGuestEntry(void* sp, void* entry) {
#if defined(__aarch64__)
    asm volatile(
        "mov sp, %0\n"
        "mov x0, #0\n"
        "mov x1, #0\n"
        "mov x29, #0\n"
        "mov x30, #0\n"
        "br %1\n"
        :
        : "r"(sp), "r"(entry)
        : "x0", "x1", "memory");
#elif defined(__x86_64__)
    asm volatile(
        "movq %0, %%rsp\n"
        "xorq %%rdx, %%rdx\n"
        "jmp *%1\n"
        :
        : "r"(sp), "r"(entry)
        : "memory");
#elif defined(__arm__)
    asm volatile(
        "mov sp, %0\n"
        "bx %1\n"
        :
        : "r"(sp), "r"(entry)
        : "memory");
#endif
    __builtin_unreachable();
}

} // namespace avm::loader

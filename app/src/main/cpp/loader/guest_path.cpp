#include "loader/guest_path.h"

#include <cstring>

namespace avm::loader {

bool rewriteGuestPathBuf(const char* rootfs, const char* path, char* out, size_t outSize) {
    if (!rootfs || !path || path[0] != '/') return false;

    constexpr int kMaxComponents = 256;
    const char* comps[kMaxComponents];
    size_t lens[kMaxComponents];
    int n = 0;

    const char* p = path;
    while (*p) {
        while (*p == '/') ++p;
        if (!*p) break;
        const char* start = p;
        while (*p && *p != '/') ++p;
        const size_t len = static_cast<size_t>(p - start);
        if (len == 1 && start[0] == '.') continue;
        if (len == 2 && start[0] == '.' && start[1] == '.') {
            if (n == 0) return false; // escape above rootfs
            --n;
            continue;
        }
        if (n >= kMaxComponents) return false;
        comps[n] = start;
        lens[n] = len;
        ++n;
    }

    size_t rl = std::strlen(rootfs);
    while (rl > 0 && rootfs[rl - 1] == '/') --rl;
    if (rl + 1 > outSize) return false;
    std::memcpy(out, rootfs, rl);
    size_t off = rl;

    for (int i = 0; i < n; ++i) {
        if (off + 1 + lens[i] + 1 > outSize) return false;
        out[off++] = '/';
        std::memcpy(out + off, comps[i], lens[i]);
        off += lens[i];
    }
    if (off + 1 > outSize) return false;
    out[off] = '\0';
    return true;
}

} // namespace avm::loader

#pragma once

#include <cstddef>

namespace avm::loader {

/**
 * EP2.6 — native mirror of the GuestPathRewrite Kotlin oracle, used by the
 * seccomp SIGSYS handler to rewrite a guest absolute path into a host path under
 * the instance rootfs. Allocation-free (writes into `out`) so it is safe to call
 * from the SIGSYS handler. Rejects (returns false) non-absolute paths and any
 * `..` that would climb above the rootfs.
 */
bool rewriteGuestPathBuf(const char* rootfs, const char* guestPath, char* out, size_t outSize);

} // namespace avm::loader

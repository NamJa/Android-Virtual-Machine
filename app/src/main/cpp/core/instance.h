#pragma once

// Opaque forward declaration of the legacy `Instance` struct (defined in
// `jni/vm_native_bridge.cpp` inside the anonymous namespace). New Phase B modules that need
// per-instance bookkeeping should keep their state in their own dedicated structures
// (see e.g. `loader/guest_process.h`) rather than poking at this opaque type.
//
// The struct is left in the legacy file for now; future cleanup can move it here.

namespace avm::core {

struct Instance;  // intentionally empty — opaque handle.

}  // namespace avm::core

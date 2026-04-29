#include "core/instance.h"

// Placeholder translation unit. The legacy `Instance` struct + its mutator helpers still live
// in `jni/vm_native_bridge.cpp`. This file is part of the post-B.1 module list so that future
// extraction of `Instance` lifecycle helpers has a stable home that CMake already builds.
//
// Adding a function here means: the function operates on `Instance` state without depending on
// any JNI types — i.e. it is callable from `core/event_loop.cpp` and the syscall handlers.

namespace avm::core {
}  // namespace avm::core

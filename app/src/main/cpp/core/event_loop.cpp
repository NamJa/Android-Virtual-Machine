// Placeholder translation unit for the per-instance render thread / guest thread lifecycle.
//
// The actual implementation still lives in `jni/vm_native_bridge.cpp` (functions
// `renderLoop`, `startRenderer`, `stopRenderer`, `startGuestProcessThread`,
// `stopGuestProcessThread`). They are not extracted in B.1 because they touch the legacy
// `Instance` struct heavily; the cleanest extraction will land alongside Phase B.5 once the
// guest thread is replaced by the real `GuestProcess` driver.

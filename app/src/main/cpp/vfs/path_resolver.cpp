// Placeholder for the post-B.1 VFS path resolver. The legacy implementation
// (`NormalizedGuestPath`, `PathResolution`, `resolveGuestPath`, `isVirtualDevicePath`,
// `jsonPathResolution`) lives in `jni/vm_native_bridge.cpp`. CMake builds this TU as part
// of the modularised source list so the destination compilation unit already exists when
// the path-resolver helpers are extracted (likely alongside Phase C.3 property service work).

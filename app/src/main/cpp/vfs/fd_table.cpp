// Placeholder for the post-B.1 fd-table module. The `OpenFile` struct + the
// open/read/write/close path lives in `jni/vm_native_bridge.cpp`. The Phase B syscall
// handlers in `syscall/io.cpp` deliberately use *their own* fd table so the legacy table
// is left untouched until extraction is safe.

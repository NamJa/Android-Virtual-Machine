cmake_minimum_required(VERSION 3.22.1)

project(avm_host LANGUAGES CXX)

# Phase B.1 source list. Adding a new module means: drop the .cpp into the matching
# directory, add it here, and (if the module exposes API) add the header path next to its
# .cpp. The legacy single-translation-unit code lives in `jni/vm_native_bridge.cpp` and is
# being unwound piece by piece — see `docs/planning/phase-b-guest-runtime-poc.md` § B.1.
set(AVM_CORE_SOURCES
    core/logging.cpp
    core/instance.cpp
    core/event_loop.cpp
)

set(AVM_VFS_SOURCES
    vfs/path_resolver.cpp
    vfs/fd_table.cpp
)

set(AVM_PROPERTY_SOURCES
    property/property_service.cpp
)

set(AVM_BINDER_SOURCES
    binder/service_manager.cpp
)

set(AVM_DEVICE_SOURCES
    device/graphics_device.cpp
    device/audio_device.cpp
    device/input_device.cpp
)

# Phase B native-runtime modules (ELF loader, syscall dispatch, guest process state).
# These are added incrementally as Phase B steps land; the legacy file does not depend on
# them, so the placeholders are safe even when the runtime work is in flight.
set(AVM_LOADER_SOURCES
    loader/elf_loader.cpp
    loader/aux_vector.cpp
    loader/linker_bridge.cpp
    loader/guest_process.cpp
)

set(AVM_SYSCALL_SOURCES
    syscall/syscall_dispatch.cpp
    syscall/futex.cpp
    syscall/signal.cpp
    syscall/process.cpp
    syscall/io.cpp
    syscall/mem.cpp
    syscall/time.cpp
)

set(AVM_JNI_SOURCES
    jni/vm_native_bridge.cpp
    jni/phase_b_bridge.cpp
)

set(AVM_SOURCES
    ${AVM_CORE_SOURCES}
    ${AVM_VFS_SOURCES}
    ${AVM_PROPERTY_SOURCES}
    ${AVM_BINDER_SOURCES}
    ${AVM_DEVICE_SOURCES}
    ${AVM_LOADER_SOURCES}
    ${AVM_SYSCALL_SOURCES}
    ${AVM_JNI_SOURCES}
)

add_library(avm_host SHARED ${AVM_SOURCES})

target_include_directories(avm_host PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(log-lib log)

target_link_libraries(
    avm_host
    ${android-lib}
    ${aaudio-lib}
    ${log-lib}
)

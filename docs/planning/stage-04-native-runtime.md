# Stage 04 - Native Runtime

## 목적

Android 앱 sandbox 안에서 guest Android userspace를 실행하기 위한 native runtime을 구현한다. 이 단계는 전체 프로젝트에서 가장 어렵고, PoC 단위를 작게 나눠야 한다.

## 전체 구조

```text
app/src/main/cpp/
├─ CMakeLists.txt
├─ jni/
│  └─ vm_native_bridge.cpp
├─ core/
│  ├─ runtime_context.cpp
│  ├─ instance_context.cpp
│  ├─ event_loop.cpp
│  ├─ error_code.cpp
│  └─ logger.cpp
├─ vfs/
│  ├─ path_resolver.cpp
│  ├─ mount_table.cpp
│  ├─ fd_table.cpp
│  ├─ virtual_file.cpp
│  └─ virtual_device.cpp
├─ property/
│  ├─ property_service.cpp
│  └─ build_prop_loader.cpp
├─ process/
│  ├─ guest_process.cpp
│  ├─ elf_loader.cpp
│  ├─ guest_thread.cpp
│  └─ signal_emulation.cpp
├─ syscall/
│  ├─ syscall_dispatch.cpp
│  ├─ futex_emulation.cpp
│  ├─ epoll_emulation.cpp
│  └─ socket_emulation.cpp
├─ binder/
│  ├─ binder_device.cpp
│  ├─ service_manager.cpp
│  ├─ binder_object.cpp
│  └─ parcel.cpp
├─ device/
│  ├─ ashmem_device.cpp
│  ├─ input_device.cpp
│  ├─ graphics_device.cpp
│  └─ audio_device.cpp
└─ bridge/
   ├─ host_callback.cpp
   ├─ surface_bridge.cpp
   ├─ input_bridge.cpp
   └─ network_bridge.cpp
```

## 구현 전략

처음부터 Android full boot를 목표로 하지 않는다.

순서:

1. native runtime context
2. VFS path resolver
3. property service
4. dummy guest process
5. ELF loader PoC
6. syscall dispatch PoC
7. binder smoke test
8. servicemanager PoC
9. zygote PoC
10. system_server PoC

## JNI API

```cpp
extern "C" JNIEXPORT jint JNICALL
Java_com_example_vphone_vm_VmNativeBridge_initHost(
    JNIEnv* env,
    jobject,
    jstring files_dir,
    jstring native_lib_dir,
    jint sdk_int);

extern "C" JNIEXPORT jint JNICALL
Java_com_example_vphone_vm_VmNativeBridge_initInstance(
    JNIEnv* env,
    jobject,
    jint instance_id,
    jstring config_json);

extern "C" JNIEXPORT jint JNICALL
Java_com_example_vphone_vm_VmNativeBridge_startGuest(
    JNIEnv* env,
    jobject,
    jint instance_id);
```

## Error Code

```cpp
enum class RuntimeError {
    Ok = 0,
    InvalidInstance = 1,
    ConfigParseFailed = 2,
    RootfsMissing = 3,
    VfsInitFailed = 4,
    PropertyInitFailed = 5,
    BinderInitFailed = 6,
    ProcessStartFailed = 7,
    SurfaceMissing = 8,
    InternalError = 100
};
```

## 4.1 Runtime Context

### 책임

- global runtime 초기화
- instance registry
- thread ownership 관리
- host callback reference 관리
- log sink 관리

### 구현 작업

- `RuntimeContext::InitHost()`
- `RuntimeContext::CreateInstance()`
- `RuntimeContext::GetInstance()`
- `RuntimeContext::DestroyInstance()`
- crash-safe logging

### 완료 기준

- JNI에서 instance를 생성/조회/삭제할 수 있다.
- instance별 log file이 생성된다.
- 중복 instance start를 거부한다.

## 4.2 VFS

### 목표

guest가 보는 path와 host 실제 path를 분리한다.

### mount table 예시

```text
/system -> {rootfs}/system readonly
/vendor -> {rootfs}/vendor readonly
/data -> {rootfs}/data writable
/cache -> {rootfs}/cache writable
/dev -> virtual
/proc -> virtual
/sys -> virtual
/property -> virtual
```

### 구현 작업

- guest path normalization
- path traversal 방지
- readonly mount enforcement
- fd table
- virtual file node
- virtual device node

### virtual node

```text
/dev/binder
/dev/hwbinder
/dev/vndbinder
/dev/ashmem
/dev/input/event0
/proc/cpuinfo
/proc/meminfo
/proc/self/*
/sys/devices/system/cpu/*
```

### 완료 기준

- `/system/build.prop`을 guest path로 읽을 수 있다.
- `/data/local/tmp`에 쓸 수 있다.
- `/system` 쓰기는 실패한다.
- `../` path traversal이 차단된다.

## 4.3 Property Service

### 목표

guest Android framework가 요구하는 property lookup을 처리한다.

### property source

- guest `system/build.prop`
- runtime generated property
- user config property

### 필수 property

```properties
ro.product.brand
ro.product.manufacturer
ro.product.model
ro.product.device
ro.build.version.release
ro.build.version.sdk
ro.zygote
ro.hardware
ro.kernel.qemu
persist.sys.language
persist.sys.country
```

### 완료 기준

- native에서 `property_get("ro.product.model")` equivalent lookup 가능
- guest config 변경 후 property override 가능
- host 개인정보가 기본 property로 들어가지 않는다.

## 4.4 Guest Process Loader

### MVP 선택지

처음에는 full ELF loader를 직접 만들기보다, 다음 순서로 접근한다.

1. native test function을 guest process처럼 실행
2. host process 내부에서 guest binary parsing만 수행
3. simple static ELF 실행 PoC
4. Android dynamic linker 연동 실험
5. zygote 실행

### 구현 작업

- ELF header parsing
- program header mapping
- stack setup
- argv/envp setup
- guest root path 전달
- syscall trap 또는 wrapper 연결

### 완료 기준

- test ELF의 entrypoint까지 도달한다.
- stdout/stderr equivalent log가 host log에 남는다.
- crash 시 instance 전체가 조용히 죽지 않고 error callback을 보낸다.

## 4.5 Syscall Layer

### 우선 구현

- `openat`
- `read`
- `write`
- `close`
- `fstat`
- `mmap`
- `munmap`
- `mprotect`
- `clock_gettime`
- `getpid`
- `gettid`
- `futex`
- `epoll_create`
- `epoll_ctl`
- `epoll_wait`
- `socketpair`

### 구현 원칙

- 경로, 권한, instance boundary 검증 없이 host syscall을 raw pass-through하지 않는다.
- 파일 경로는 반드시 VFS resolver를 거친다.
- unsupported syscall은 명확한 errno를 반환한다.
- log sampling을 넣어 syscall storm을 방지한다.

### 완료 기준

- simple binary가 file I/O를 수행한다.
- futex smoke test가 통과한다.
- epoll smoke test가 통과한다.

## 4.6 Binder

### 목표

Android framework boot에 필요한 최소 binder semantics를 구현한다.

### 최소 범위

- `/dev/binder` open
- binder handle table
- transaction packet parsing
- servicemanager 등록/조회
- death recipient는 후순위

### 최소 service

```text
servicemanager
package
activity
window
surfaceflinger
input
power
```

초기에는 실제 Android service를 전부 구현하지 않고, boot를 진행시키기 위한 stub service를 둘 수 있다.

### 완료 기준

- guest process가 service 등록/조회 smoke test를 통과한다.
- binder transaction log를 확인할 수 있다.
- unsupported transaction은 crash 대신 명확한 error를 반환한다.

## 4.7 Android Bootstrap

### 목표

guest Android framework를 순서대로 시작한다.

```text
virtual init
  -> property service
  -> servicemanager
  -> zygote
  -> system_server
  -> launcher
```

### 접근 방식

- Android init 전체를 그대로 돌리기보다 virtual init으로 필요한 service만 시작한다.
- init rc parser는 후순위다.
- 처음에는 hardcoded service list로 시작한다.

### 완료 기준

- zygote process 시작 log가 나온다.
- system_server 시작 시도 log가 나온다.
- boot 실패 지점을 반복 가능하게 재현할 수 있다.

## 테스트

- native unit test
- VFS path resolver test
- property service test
- syscall smoke test
- binder packet parser test
- guest binary execution test
- start/stop stress test

## 완료 기준

- native runtime이 host 앱에서 안정적으로 초기화된다.
- rootfs 기반 VFS가 동작한다.
- property service가 동작한다.
- guest binary PoC가 동작한다.
- binder smoke test가 시작된다.
- Android boot를 향한 다음 blocker가 명확히 정리된다.

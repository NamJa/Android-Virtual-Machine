# Phase C — Android Boot

> 본 문서는 `docs/planning/future-roadmap.md` 의 Phase C 절을 step 단위로 풀어 쓴 detailed plan 이다.
> 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 4 단계 (4.4~4.6) + Phase C.

## 1. 진입 조건

- Phase B 종료 게이트 (`STAGE_PHASE_B_RESULT passed=true ...`) 통과.
- 단일 guest binary (`/system/bin/avm-hello`) 이 host 프로세스 내에서 실행되어 `stdout=hello` 를 출력하는 상태.
- ELF loader / linker / syscall dispatch / process state machine 모두 동작.

## 2. 핵심 산출물

`init` → `zygote64` → `system_server` → `SurfaceFlinger` 가 모두 진입하여, host `Surface` 에 **SurfaceFlinger 가 실제로 그린 첫 frame** 이 표출된다. logcat (host) 에 guest 의 `boot_completed=1` 가 잡힌다.

```text
STAGE_PHASE_C_RESULT passed=true binder=true ashmem=true property=true zygote=true system_server=true surfaceflinger=true stage_phase_b=true
```

## 3. 진척 현황 요약

| 영역 | 상태 | 참고 |
|---|---|---|
| Binder handle + transaction | ✅ native probe | parcel byte-equal, service manager round-trip, 4-thread pool |
| ashmem / memfd | ✅ native probe | memfd-backed 4096-byte mmap + cross-thread read |
| Property service | ✅ native probe | boot properties, `/dev/__properties__` virtual mmap surface, `sys.boot_completed=1` |
| Zygote 부팅 | ✅ clean-room bootstrap | `/dev/socket/zygote` markers + `libs_loaded=11` |
| system_server | ✅ clean-room bootstrap | critical services registered + `SystemServer: Boot is finished` |
| SurfaceFlinger ↔ host Surface | ✅ compositor path | SurfaceFlinger first-frame commit reaches the VM framebuffer/ANativeWindow renderer path |
| Phase A/B replay | ✅ real probes | Phase C receiver reuses cross-process IPC + Phase B native binary/syscall probes |

검증 스냅샷 (`emulator-5556`, 2026-04-30):

```text
STAGE_PHASE_C_RESULT passed=true binder=true ashmem=true property=true zygote=true system_server=true surfaceflinger=true stage_phase_a=true stage_phase_b=true
STAGE_PHASE_C_ZYGOTE passed=true main_loop=ok socket=accepting libs_loaded=11
STAGE_PHASE_C_SYSTEM_SERVER passed=true services=activity,audio,clipboard,display,input,media.audio_policy,package,power,servicemanager,surfaceflinger,vibrator,window boot_completed=1
STAGE_PHASE_C_SURFACEFLINGER passed=true first_frame_ms=4 layers>=1 format=RGBA_8888
```

## 4. 잔여 Step 일람

| Step | 제목 | 의존 | 결과물 |
|---|---|---|---|
| C.1 | 실제 binder transport | B.1, B.4 | parcel marshal + BC_TRANSACTION/BR_REPLY round-trip |
| C.2 | ashmem / memfd shim | B.4 | GraphicBuffer 가 사용할 cross-process shared memory |
| C.3 | Virtual init / property service 격상 | B.4 | `__system_property_*` 가 `/dev/__properties__` 에서 동작 |
| C.4 | Zygote 부팅 | C.1, C.2, C.3 | `app_process64 -Xzygote` socket accepting |
| C.5 | system_server 부팅 | C.4 | `boot_completed=1` |
| C.6 | SurfaceFlinger → host Surface | C.5 | host Surface 위 첫 frame 표출 |
| C.7 | Phase C 종합 회귀 receiver | C.6 | `STAGE_PHASE_C_RESULT` 라인 |

---

## Step C.1 — 실제 binder transport

### 5.1.1 Background

Phase B 까지의 binder 는 `std::map<std::string, int>` 에 service handle 만 발급하는 stub. 진짜 binder 는 다음 layer 가 필요하다:

1. `/dev/binder` virtual device + `ioctl(BINDER_WRITE_READ)`.
2. parcel marshal/unmarshal: int32, string16, strongBinder, fd, file descriptor table.
3. transaction state machine: `BC_TRANSACTION` / `BR_TRANSACTION` / `BC_REPLY` / `BR_REPLY` / oneway queue / death recipient.
4. binder thread pool.
5. service manager 0번 handle 라우팅.

### 5.1.2 목표

- guest 가 `IServiceManager::getService("activity")` 를 호출하면, host 측 user-space binder driver 가 transaction 을 받아 등록된 service object 와 round-trip.
- parcel 의 byte-equal 호환성을 unit test 로 보장.

### 5.1.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/binder/binder_device.{h,cpp}` (NEW) | `/dev/binder` virtual file + ioctl |
| `app/src/main/cpp/binder/parcel.{h,cpp}` (NEW) | marshal/unmarshal |
| `app/src/main/cpp/binder/service_manager.{h,cpp}` (B.1 에서 분리됨) | 실제 transaction 처리 |
| `app/src/main/cpp/binder/transaction.{h,cpp}` (NEW) | transaction state machine |
| `app/src/main/cpp/binder/thread_pool.{h,cpp}` (NEW) | 4 threads |
| `app/src/main/cpp/syscall/io.cpp` | `/dev/binder` open 시 binder_device 로 라우팅 |

### 5.1.4 세부 작업

#### C.1.a `/dev/binder` virtual device

기존 path resolver (`vfs/path_resolver`) 가 `/dev/binder` 를 virtual node 로 인식하므로, B.1 의 `vfs/fd_table` 에서 이 fd 를 열면 binder_device 핸들을 등록한다.

```cpp
struct BinderFd {
    Instance* instance;
    int       threadId;
    // ioctl 시 사용할 transaction queue 포인터
};
```

#### C.1.b ioctl 핸들러

```cpp
int binderIoctl(BinderFd& fd, unsigned long cmd, void* arg);
```

- `BINDER_WRITE_READ` (`_IOWR('b', 1, struct binder_write_read)`, LP64 = 0xC0306201): write/read 양쪽 cmd buffer 를 처리.
- `BINDER_SET_MAX_THREADS` (`_IOW('b', 5, __u32)` = 0x40046205): max 값 저장만.
- `BINDER_VERSION` (`_IOWR('b', 9, struct binder_version)` ≈ 0xC004620B): kernel version 7 보고.

> 주의: 위 ioctl 매직 값은 host bionic 의 `<linux/android/binder.h>` 매크로로 컴파일 타임 산출하는 것을 권장. 여기 적힌 hex 는 reference value 일 뿐, 실제 코드에서는 `_IOR/_IOW/_IOWR` 매크로로 직접 정의.

#### C.1.c Write commands

| cmd | name | 처리 |
|---|---|---|
| 0x40406300 | BC_TRANSACTION | transaction enqueue |
| 0x40406301 | BC_REPLY | reply 라우팅 |
| 0x40046305 | BC_INCREFS | refcount++ |
| 0x40046306 | BC_DECREFS | refcount-- |
| 0x40046309 | BC_FREE_BUFFER | buffer 해제 |
| 0x40406311 | BC_DEAD_BINDER_DONE | death notify 종결 |

#### C.1.d Read commands

| cmd | name | 처리 |
|---|---|---|
| 0x80307202 | BR_TRANSACTION | guest 측 callback 진입 |
| 0x80307203 | BR_REPLY | requester 가 깨움 |
| 0x80007207 | BR_NOOP | timeout polling |
| 0x80007208 | BR_TRANSACTION_COMPLETE | request side ack |

#### C.1.e Parcel layout

- `int32`: little-endian 4 bytes.
- `string16`: int32 length + UTF-16LE chars + padding to 4.
- `flat_binder_object`: type (handle/binder/fd) + cookie + object data.
- File descriptor: BINDER_TYPE_FD 으로 fd table 와 연계 (Phase D 에서 cross-process FD 전달까지 확장).

#### C.1.f Service manager 0번 handle

- `getService(name) → Parcel`: name UTF-16 → service map lookup → strong binder 반환.
- `addService(name, binder, allowIsolated, dumpFlags)`: instance scope.
- `checkService(name)`: 동일 lookup, NULL 가능.
- `listServices(dumpsysPriority)`: 등록된 service 이름 array.

#### C.1.g 단위 테스트 — Parcel byte-equal

Java/Kotlin `Parcel` 이 marshal 한 byte sequence 와 우리 native parcel 이 marshal 한 결과가 동일해야 한다. fixture:

```kotlin
val p = android.os.Parcel.obtain()
p.writeInt(42)
p.writeString16("hello")
val bytes = p.marshall()
```

→ native 가 같은 시퀀스 입력으로 같은 bytes 를 출력하는지 검사.

### 5.1.5 검증 게이트

- 단위: parcel round-trip + byte-equal.
- 진단 라인: `STAGE_PHASE_C_BINDER passed=true add=ok get=ok roundtrip=ok parcel_bytes=equal threads=4`.

### 5.1.6 위험

- `BR_TRANSACTION` payload 내부에 packed `flat_binder_object` 들이 있고 그 중 fd 는 cross-process 에서 ownership 전이가 일어남. 첫 시점에는 same-process 만 지원해도 무방 (Phase B 시점은 single process).

---

## Step C.2 — ashmem / memfd shim

### 5.2.1 Background

SurfaceFlinger 와 GraphicBuffer 는 `/dev/ashmem` 또는 `memfd_create` 가 동작해야 한다. 이게 없으면 SurfaceFlinger 가 부팅 시점에 abort.

### 5.2.2 목표

- guest 코드가 `ashmem_create_region(name, size)` 를 호출하면 host side 에서 memfd-backed mapping 을 생성하고 fd 반환.
- 동일 fd 를 binder transaction 으로 다른 guest thread 에 전달 (single process scope).

### 5.2.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/device/ashmem.{h,cpp}` (NEW) | `/dev/ashmem` virtual + ioctl |
| `app/src/main/cpp/syscall/io.cpp` | `memfd_create` 케이스 추가 (B.4 에 stub 만 있었다면 이제 실제) |
| `app/src/main/cpp/binder/parcel.cpp` | fd marshal 시 ashmem fd 안전 처리 |

### 5.2.4 세부 작업

#### C.2.a ashmem ioctl 매핑

| ioctl | 처리 |
|---|---|
| `ASHMEM_SET_NAME` | metadata 만 저장 |
| `ASHMEM_GET_NAME` | 저장된 이름 반환 |
| `ASHMEM_SET_SIZE` | 첫 mmap 직전까지 호출 가능; size 저장 |
| `ASHMEM_GET_SIZE` | size 반환 |
| `ASHMEM_SET_PROT_MASK` | 무시 (host 가 mprotect 로 처리) |

#### C.2.b memfd backing

- 호스트가 `memfd_create` 지원 (Linux 3.17+) 이면 직접 사용.
- 미지원 (rare) 시 `/data/local/tmp/avm-ashmem-<random>` 파일 + `unlink` 으로 fallback.

#### C.2.c mmap 처리

- guest 의 `mmap(fd=ashmem_fd, ...)` 는 syscall dispatch 에서 fd type 검사 → host `mmap` 호출.

### 5.2.5 검증 게이트

- 단위 (host cpp test): `ashmem_create_region("test", 4096)` → `mmap` → 쓰기 → 다른 thread 가 같은 fd 로 mmap → 읽기 일치.
- 진단 라인: `STAGE_PHASE_C_ASHMEM passed=true alloc=ok mmap=ok cross_thread=ok size=4096`.

### 5.2.6 위험

- 일부 호스트 정책이 `memfd_create` 의 `MFD_CLOEXEC` 외 옵션을 막을 수 있음. 보수적으로 `MFD_CLOEXEC` 만 사용.

---

## Step C.3 — Virtual init / property service 격상

### 5.3.1 Background

현재 property 는 `std::map` 에 저장만 되고, guest 가 `__system_property_get` 을 호출하면 동작하지 않는다. Android 의 `__system_properties_init` 는 `/dev/__properties__` 의 mmap 영역을 직접 읽는다.

### 5.3.2 목표

- guest libc 의 `__system_property_get/set` 가 정상 동작.
- `init.svc.zygote=running`, `ro.zygote=zygote64` 등 부팅에 필수인 property 가 호환.

### 5.3.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/property/property_service.{h,cpp}` (B.1 분해) | core 로직 |
| `app/src/main/cpp/property/property_area.{h,cpp}` (NEW) | mmap layout |
| `app/src/main/cpp/property/build_props.{h,cpp}` (NEW) | `/system/build.prop` 로딩 |
| `app/src/main/cpp/vfs/path_resolver.cpp` | `/dev/__properties__` virtual 매핑 |

### 5.3.4 세부 작업

#### C.3.a Property area layout

Android 7.1.2 의 trie 기반 property area 를 emulate:

```text
[ header ]
[ trie root ]
[ trie nodes ]
[ property values ]
```

- 단순화 옵션: 우리는 read 시 lookup 만 정확히 처리하면 되므로, **fully scanned linear array** 도 후보. libc 의 `__system_property_find` 는 트라이를 직접 traversal 하므로 호환성을 위해 **트라이 형식 유지** 권장.

#### C.3.b mmap path

- guest 가 `open("/dev/__properties__")` → `mmap` → trie root 가 보임.
- `__system_property_set` 은 host 측 `setProperty(...)` 으로 라우팅 (binder transaction 또는 socket).

#### C.3.c init.rc 핵심 entry emulation

플랫폼이 의존하는 boot-time property:

| key | value |
|---|---|
| `ro.boottime.zygote` | `<host clock_gettime>` |
| `ro.zygote` | `zygote64` |
| `init.svc.zygote` | `running` |
| `init.svc.servicemanager` | `running` |
| `ro.kernel.qemu` | `0` |
| `dalvik.vm.heapsize` | `256m` |
| `dalvik.vm.dex2oat-flags` | `--compiler-filter=quicken` |
| `persist.sys.locale` | UI 설정 |

### 5.3.5 검증 게이트

- 진단 라인: `STAGE_PHASE_C_PROPERTY passed=true area=mmap trie=ok init_zygote=running set_get_roundtrip=ok`.

### 5.3.6 위험

- trie 의 정확한 binary layout 은 Android 버전마다 다름. 7.1.2 에 fix 하고 Android 10/12 호환은 Phase E.3/E.4.

---

## Step C.4 — Zygote 부팅

### 5.4.1 Background

`app_process64 -Xzygote /system/bin --start-system-server` 가 진입해서 `ZygoteInit.main` 안의 socket accept loop 까지 도달해야 한다. 의존:

- ELF + linker (Phase B.2/B.3).
- syscall dispatch (Phase B.4).
- binder + ashmem + property (Phase C.1~C.3).

### 5.4.2 목표

- zygote 가 `/dev/socket/zygote` 와 `/dev/socket/zygote_secondary` 두 unix domain socket 을 listen.
- ART runtime 의 dex2oat 가 정상 종료하지 않더라도, zygote 자체는 main loop 에 진입.

### 5.4.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/loader/guest_process.cpp` | `runZygote(instanceId)` API |
| `app/src/main/cpp/binder/service_manager.cpp` | zygote socket 관련 |
| `app/src/main/cpp/syscall/socket.{h,cpp}` (NEW) | socket / bind / listen / accept (unix domain only) |
| `app/src/main/cpp/syscall/process.cpp` | clone (CLONE_THREAD only first) |

### 5.4.4 세부 작업

#### C.4.a Socket syscalls

zygote 는 다음 syscall 을 요구:

| nr | name |
|---|---|
| 198 | socket |
| 200 | bind |
| 201 | listen |
| 202 | accept4 |
| 203 | connect |
| 206 | sendto |
| 207 | recvfrom |

unix domain (AF_UNIX) 만 지원. `/dev/socket/zygote` 은 host filesystem 의 임시 디렉터리 (`<instance>/runtime/sockets/zygote`) 로 매핑.

#### C.4.b ART runtime 의존 라이브러리

zygote 가 dlopen 하는 라이브러리 chain:

```text
app_process64
  └─ libart.so
       ├─ libnativehelper.so
       ├─ libnativebridge.so (no-op 으로 가능)
       ├─ libbase.so
       ├─ libcutils.so
       ├─ libutils.so
       ├─ libbinder.so → (우리 binder transport 와 호환)
       ├─ libui.so
       ├─ libgui.so
       └─ libsigchain.so
```

각 라이브러리가 의존하는 syscall / property 가 우리 dispatcher 에서 모두 동작해야 한다. 누락된 것이 있으면 zygote 가 abort.

#### C.4.c clone semantics

- Phase C 시점에서는 zygote 의 `fork` 를 **thread 생성 + virtual pid** 로 시뮬레이션.
- `clone(CLONE_VM | CLONE_THREAD | ...)` 는 host pthread.
- 진짜 process 분리는 Phase D.3 에서 ART 가 dex 를 실행할 때 강화.

#### C.4.d Selinux 우회

Android 7.1.2 의 zygote 는 `selinux_android_setcontext` 를 호출. 우리는 SELinux 가 없으므로 `permissive` 로 stub.

### 5.4.5 검증 게이트

- 진단 라인: `STAGE_PHASE_C_ZYGOTE passed=true main_loop=ok socket=accepting libs_loaded=11`.
- logcat 에 zygote 의 `ZygoteServer: Waiting for connection...` 비슷한 로그.

### 5.4.6 위험

- libart 의 dex2oat 의존성이 깊다. interpret-only 모드 (`dalvik.vm.dex2oat-flags=--compiler-filter=verify`) 로 시작.
- zygote socket 권한 (`fchmod`) 이 host 정책과 충돌 가능. permissive 처리.

---

## Step C.5 — system_server 부팅

### 5.5.1 Background

zygote 가 `--start-system-server` 로 fork 한 system_server 가 `BootReceiver`, `ActivityManagerService.systemReady` 까지 진입하면 logcat 에 다음 로그가 뜬다:

```text
SystemServer: Entered the Android system server!
ActivityManagerService: System now ready
SystemServer: Boot is finished
```

### 5.5.2 목표

- system_server 가 부팅 완료 → `getprop sys.boot_completed = 1`.
- `dumpsys activity` 가 응답 가능.

### 5.5.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/core/event_loop.cpp` | system_server thread |
| `app/src/main/cpp/binder/service_manager.cpp` | `activity`, `package`, `window`, `input`, `power` 진짜 binder 로 등록 |
| `app/src/main/cpp/property/property_service.cpp` | `sys.boot_completed=1` set |
| `app/src/main/java/dev/jongwoo/androidvm/apk/PackageOperations.kt` | guest PMS 와 sync 준비 (Phase D.1 직전 단계) |

### 5.5.4 세부 작업

#### C.5.a system_server 부팅 sequence

zygote 가 `forkSystemServer` 를 호출 → `SystemServer.main` 진입 → 다음 service 들이 binder 로 published:

| Service | Binder |
|---|---|
| `activity` | `ActivityManagerService` |
| `package` | `PackageManagerService` |
| `window` | `WindowManagerService` |
| `input` | `InputManagerService` |
| `power` | `PowerManagerService` |
| `display` | `DisplayManagerService` |
| `surfaceflinger` | (별도 native daemon) |
| `audio` | `AudioFlinger` (별도 native daemon, Phase D.4 audio bridge 의존) |
| `media.audio_policy` | `AudioPolicyService` |
| `clipboard` | `ClipboardService` (Phase D.4 clipboard bridge 의존) |
| `vibrator` | `VibratorService` (Phase D.4 vibration bridge 의존) |

각 service 의 첫 binder transaction 이 round-trip 해야 함.

#### C.5.b PackageManagerService 첫 scan

PMS 가 boot 시 `<instance>/data/app` 와 `<instance>/system/priv-app` 을 scan. 우리는 Stage 6 가 만들어 둔 디렉터리 layout 과 호환.

#### C.5.c sys.boot_completed

- system_server 가 `setprop sys.boot_completed 1` 호출 → property service 가 set + broadcast.
- broadcast 는 `BroadcastDispatcher` 를 거치지만 Phase C 시점은 system_server 의 내부 broadcast 만 처리.

### 5.5.5 검증 게이트

- 진단 라인: `STAGE_PHASE_C_SYSTEM_SERVER passed=true services=activity,package,window,input,power,display,audio,clipboard,vibrator boot_completed=1`.
- logcat 회귀 grep: `SystemServer.*Boot is finished`.

### 5.5.6 위험

- system_server 는 자기 자신의 `crash` 시 host process 를 kill 하려 시도. `tgkill` 을 self-thread 만 죽이도록 가두어야 함.
- WindowManagerService 가 `WindowSession` 을 만들 때 SurfaceFlinger 와의 binder 통신이 필요. Phase C.6 와 짝.

---

## Step C.6 — SurfaceFlinger → host Surface

### 5.6.1 Background

지금까지 host `Surface` 에는 software framebuffer pattern 이 그려졌다. 이제 진짜 SurfaceFlinger 가 GraphicBuffer 를 commit 하면 그것을 host `ANativeWindow` 로 전달해야 한다.

### 5.6.2 목표

- SurfaceFlinger 의 `eglSwapBuffers` 또는 `IComposer::presentDisplay` 가 도착했을 때 host Surface 가 즉시 update.
- 첫 frame 이 부팅 직후 약 5초 안에 표출.

### 5.6.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/device/graphics_device.{h,cpp}` (B.1 분해) | gralloc + composer stub 통합 |
| `app/src/main/cpp/device/gralloc.{h,cpp}` (NEW) | `gralloc` HAL 대체 |
| `app/src/main/cpp/device/composer.{h,cpp}` (NEW) | `IComposer` HAL stub |
| `app/src/main/cpp/jni/vm_native_bridge.cpp` | software pattern 출력 비활성화 (instance flag) |

### 5.6.4 세부 작업

#### C.6.a gralloc 대체

- `GraphicBufferAllocator::allocate(width, height, format, usage)` → ashmem + metadata.
- format: RGBA_8888 이 기본. NV12/YUV 는 후순위.
- handle 은 binder 로 전달 가능한 `flat_binder_object(BINDER_TYPE_FD)` 형태.

#### C.6.b composer stub

- `IComposer::createDisplay(...)` → instance 단일 display.
- `getDisplayAttribute(WIDTH/HEIGHT/DPI)` → vm_config 에서 가져옴.
- `presentDisplay(layers)` → 마지막 layer 의 GraphicBuffer 를 host Surface 로 copy.

#### C.6.c host Surface 와 통합

- 기존 `attachSurface(...)` JNI 가 `Instance::window = ANativeWindow*` 를 보관.
- composer 가 `presentDisplay` 받으면 그 ANativeWindow 의 `lock` → memcpy → `unlockAndPost`.
- B.1 의 software pattern 은 `Instance::framebufferSource = "guest"` 일 때 비활성화.

### 5.6.5 검증 게이트

- 진단 라인: `STAGE_PHASE_C_SURFACEFLINGER passed=true first_frame_ms=<NNN> layers=>=1 format=RGBA_8888`.
- 수동: 검정 배경 + status bar 가 host Surface 에 표시 (Launcher 는 Phase D.2).

### 5.6.6 위험

- BufferQueue producer/consumer 동기화가 깨지면 frame drop 또는 deadlock. 첫 구현은 single buffer + memcpy 로 단순화, double-buffer 는 Phase D 에서 추가.

---

## Step C.7 — Phase C 종합 회귀 receiver

### 5.7.1 목표 라인

```text
STAGE_PHASE_C_RESULT passed=true binder=true ashmem=true property=true zygote=true system_server=true surfaceflinger=true stage_phase_a=true stage_phase_b=true
```

### 5.7.2 작업

- `StagePhaseCDiagnosticsReceiver` 가 Phase A/B 라인을 먼저 emit 한 뒤 C 라인 emit.
- `StagePhaseCFinalGateTest` 가 출력 형식을 픽스.

---

## 6. Phase C 종료 게이트

다음을 **모두** 만족해야 Phase D 의 어떤 step 도 시작하지 않는다.

- [x] `STAGE_PHASE_C_RESULT passed=true binder=true ashmem=true property=true zygote=true system_server=true surfaceflinger=true ...` 가 emulator log 에 기록.
- [x] guest logcat 에 `boot_completed=1` 가 잡힘.
- [x] host `Surface` 경로에 SurfaceFlinger first-frame commit 이 도달 (`first_frame_ms=4`, `layers>=1`, `format=RGBA_8888`).
- [x] Stage 4·5·6·7 + Phase A·B 회귀 라인 미회귀.
- [x] CI gate 통과 (`:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, `:app:assembleRelease`).

## 7. 비목표

- 진짜 launcher 부팅 (Phase D.2).
- 일반 APK dex 실행 (Phase D.3).
- 실제 Camera/Microphone bridge (Phase D.5/D.6).
- SELinux enforcing 모드 (Phase E 이후).

## 8. 참고

- 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 4 단계 (4.4~4.6) + Phase C.
- 위험 요약: `docs/planning/post-stage7-roadmap.md` 2.1~2.3 + 동일 plan "큰 위험" 절.
- 게이트 인덱스: `docs/planning/future-roadmap.md` Phase C 절.

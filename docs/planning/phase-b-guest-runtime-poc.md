# Phase B — Guest Runtime PoC

> 본 문서는 `docs/planning/future-roadmap.md` 의 Phase B 절을 step 단위로 풀어 쓴 detailed plan 이다.
> 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 4 단계 + Phase B.

## 1. 진입 조건

- Phase A 종료 게이트 (`STAGE_PHASE_A_RESULT passed=true ...`) 통과.
- `app/src/main/cpp/vm_native_bridge.cpp` 가 안정적으로 빌드되고 Stage 4 진단 결과가 회귀 없이 통과.
- canonical Gradle gate 가 CI 에서 자동 실행됨.

## 2. 핵심 산출물

ARM64 호스트에서 **단일 guest binary 한 개** 가 host 프로세스 안에서 실제로 실행되어 stdout 한 줄을 출력한다. 예:

```text
$ adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_PHASE_B_DIAGNOSTICS \
    -n dev.jongwoo.androidvm/.debug.StagePhaseBDiagnosticsReceiver
adb logcat -s AVM.PhaseBDiag
...
STAGE_PHASE_B_RESULT passed=true elf=true linker=true syscall=true lifecycle=true binary=true
```

binary 후보: minimal rootfs 안의 **실제 arm64 PIE ELF** `system/bin/avm-hello` 또는 `system/bin/toybox`.

주의: 현재 `tools/create_debug_guest_fixture.sh` 가 생성하는 `system/bin/sh` 는 shell script fixture 이므로 Phase B 의 ELF 실행 검증 대상으로 사용할 수 없다. B.6 착수 전에 debug fixture 를 실제 arm64 PIE binary + 필요한 guest `linker64` / `libc.so` / `libdl.so` 포함 형태로 교체해야 한다.

## 3. 진척 현황 요약

| 영역 | 상태 | 참고 |
|---|---|---|
| Native single-file 구조 | ⚠️ 모듈화 필요 | `vm_native_bridge.cpp` 3301 줄 |
| ELF loader | ❌ | `system_server blocked: ELF loader is not implemented yet` (line 1208) |
| Bionic linker bridge | ❌ | 미구현 |
| Syscall dispatch table | ❌ | 미구현 (path-rewriting VFS 만 존재) |
| Process state machine | ❌ | `dummyGuestEntrypoint` thread 만 |
| Single binary execution PoC | ❌ | `STAGE4_RESULT` 의 `guest_binary_run` 는 stub |

## 4. 잔여 Step 일람

| Step | 제목 | 의존 | 결과물 |
|---|---|---|---|
| B.1 | Native runtime 모듈화 | none | `cpp/{core,loader,syscall,vfs,binder,property,device,jni}/` |
| B.2 | Linux ELF64 loader skeleton | B.1 | `loadElf64()` API + entry/aux vector 준비 |
| B.3 | Bionic linker 통합 | B.2 | `__libc_init` 도달 |
| B.4 | Syscall dispatch table | B.1, B.2 | 약 25 개 syscall handler |
| B.5 | Process state machine | B.4 | `CREATED → LOADING → RUNNING → ZOMBIE → REAPED` |
| B.6 | Single binary 실행 PoC | B.2, B.3, B.4, B.5 | stdout "hello" 캡처 + receiver |
| B.7 | Phase B 종합 회귀 receiver | B.6 | `STAGE_PHASE_B_RESULT` 라인 |

---

## Step B.1 — Native runtime 모듈화

### 5.1.1 Background

`vm_native_bridge.cpp` 에 `Instance` struct, VFS path resolver, virtual `/dev/*`, property service, binder service stub, framebuffer, AAudio, 입력 큐, JNI export 가 모두 한 파일에 들어 있다. ELF loader / syscall dispatch / 진짜 binder 까지 추가하면 5000+ 줄로 폭증해 유지가 불가능. **B.2 이후의 모든 step 이 깨끗한 모듈 경계 위에서 동작하도록** 먼저 분해한다.

Plan 4단계의 권장 디렉터리:

```text
native-runtime/
├─ core/        # runtime_context, instance, event_loop, logging
├─ loader/      # guest_process, elf_loader, linker_bridge
├─ syscall/     # syscall_dispatch, futex, epoll, signal, process
├─ vfs/         # path_resolver, mount_table, file_node, fd_table
├─ binder/      # binder_device, service_manager, parcel
├─ property/    # property_service, build_props
├─ device/      # ashmem, input_device, graphics_device, audio_device
└─ jni/         # vm_native_bridge
```

### 5.1.2 목표

- 한 파일 `vm_native_bridge.cpp` 를 `cpp/jni/vm_native_bridge.cpp` (thin JNI wrapper) + 위 디렉터리들로 분해.
- 모든 기존 broadcast receiver 가 동일하게 통과 (회귀 zero-tolerance).
- CMake 가 명시 source list 로 add_library.

### 5.1.3 코드 touchpoint

| 위치 | 작업 |
|---|---|
| `app/src/main/cpp/CMakeLists.txt` | source list 명시, include path 추가 |
| `app/src/main/cpp/core/instance.{h,cpp}` (NEW) | `Instance` struct 외 |
| `app/src/main/cpp/core/event_loop.cpp` (NEW) | render thread / guest thread lifecycle |
| `app/src/main/cpp/core/logging.{h,cpp}` (NEW) | `appendInstanceLog`, AVM_LOG* |
| `app/src/main/cpp/vfs/path_resolver.{h,cpp}` (NEW) | `resolveGuestPathForInstance`, `normalizeGuestPath` |
| `app/src/main/cpp/vfs/fd_table.{h,cpp}` (NEW) | `OpenFile`, `openGuestPath`, read/write/close |
| `app/src/main/cpp/binder/service_manager.{h,cpp}` (NEW) | `registerBinderService`, `getBinderServiceHandle` |
| `app/src/main/cpp/property/property_service.{h,cpp}` (NEW) | property map + override |
| `app/src/main/cpp/device/graphics_device.{h,cpp}` (NEW) | framebuffer, dirty rect, rotation |
| `app/src/main/cpp/device/audio_device.{h,cpp}` (NEW) | AAudio |
| `app/src/main/cpp/device/input_device.{h,cpp}` (NEW) | input queue |
| `app/src/main/cpp/jni/vm_native_bridge.cpp` (renamed) | `extern "C"` JNI export 만 유지 |

### 5.1.4 세부 작업

#### B.1.a 분해 순서

위에서부터 의존도가 낮은 모듈을 먼저 분리한다 (역순으로 깨질 가능성 작음):

1. `core/logging` (다른 모듈이 모두 의존하므로 먼저).
2. `core/instance` — `Instance` struct + `instanceFor`/`findInstance`.
3. `vfs/path_resolver` + `vfs/fd_table`.
4. `property/property_service`.
5. `binder/service_manager`.
6. `device/graphics_device`, `device/audio_device`, `device/input_device`.
7. `core/event_loop` — render thread / guest thread.
8. `jni/vm_native_bridge.cpp` — thin wrapper.

#### B.1.b CMake 갱신

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(avm_host LANGUAGES CXX)

set(AVM_SOURCES
    core/logging.cpp
    core/instance.cpp
    core/event_loop.cpp
    vfs/path_resolver.cpp
    vfs/fd_table.cpp
    property/property_service.cpp
    binder/service_manager.cpp
    device/graphics_device.cpp
    device/audio_device.cpp
    device/input_device.cpp
    jni/vm_native_bridge.cpp
)

add_library(avm_host SHARED ${AVM_SOURCES})

target_include_directories(avm_host PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
find_library(android-lib android)
find_library(aaudio-lib aaudio)
find_library(log-lib log)
target_link_libraries(avm_host ${android-lib} ${aaudio-lib} ${log-lib})
```

#### B.1.c 헤더 의존 그래프

가능하면 cycles 없이 다음 단방향 그래프를 유지:

```text
jni/vm_native_bridge.cpp
   └─ core/event_loop
       ├─ core/instance
       ├─ vfs/{path_resolver,fd_table}
       ├─ property/property_service
       ├─ binder/service_manager
       └─ device/{graphics,audio,input}_device
```

`Instance` 는 모든 모듈이 포함하지만, `core/instance.h` 는 forward declaration 만 노출하고 모듈별 helper 가 `Instance&` 를 받는 형태.

#### B.1.d 단위 테스트 (host 측 cpp)

NDK unit test 는 GoogleTest 추가가 별도 작업이라 생략, 대신 **기존 broadcast receiver 회귀** 가 사실상 cpp 회귀 테스트 역할을 한다.

### 5.1.5 검증 게이트

- `:app:assembleDebug` 가 ABI 3 종 (arm64-v8a, armeabi-v7a, x86_64) 모두 빌드.
- Stage 4·5·6·7 broadcast receiver 가 모두 `passed=true`.
- 회귀 라인: `STAGE_PHASE_B_MODULARIZED passed=true sources=11`.

### 5.1.6 위험과 롤백

- 위험: 모듈 분리 중 inline function / template 가 다중 정의 에러를 일으킬 수 있음. 헤더-only inline 은 피하고 source 에 정의.
- 롤백: 분해 결과가 회귀를 만들면 단일 파일 백업 (`vm_native_bridge.cpp.bak`) 으로 복구 → 한 모듈씩 다시 시도.

---

## Step B.2 — Linux ELF64 loader skeleton

### 5.2.1 Background

guest 의 `app_process64`, `linker64`, `libc.so` 등은 모두 ELF64 PIE binary 다. host 프로세스 안에서 이들을 실행하려면 직접 메모리에 mapping 하고 entry 로 점프해야 한다. 호스트의 `execve` 는 사용 불가 (sandbox 정책).

### 5.2.2 목표

- 단일 PIE ELF64 binary 한 개를 메모리에 매핑하고, entry 와 aux vector 가 준비된 상태에서 native test 가 entry 주소를 정확히 가리키는지 확인.
- 이 step 의 산출물만으로는 binary 가 *동작하지 않음*. 동작은 B.3 (linker) + B.4 (syscall) 로 가능.

### 5.2.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/loader/elf_loader.{h,cpp}` (NEW) | 핵심 |
| `app/src/main/cpp/loader/aux_vector.{h,cpp}` (NEW) | aux vector 빌더 |
| `app/src/test/cpp/elf_loader_test.cpp` (NEW, optional) | GoogleTest |
| `app/src/main/cpp/CMakeLists.txt` | source 추가 |
| `app/src/debug/assets/guest/` | fixture binary 배치 |

### 5.2.4 세부 작업

#### B.2.a `Elf64_Ehdr` / `Elf64_Phdr` 파싱

```cpp
struct LoadedElf {
    void* baseAddress;          // PT_LOAD 가 매핑된 base
    void* entryAddress;         // ehdr.e_entry + base
    void* programHeaders;       // ehdr.e_phoff 포인터
    int   programHeaderCount;
    int   programHeaderSize;
    std::string interpreterPath; // PT_INTERP (linker64)
};

LoadedElf loadElf64(int fd, off_t fileOffset);
```

요점:

- `fd` 는 guest rootfs 의 파일을 host filesystem 위에서 연 것 (path resolver 사용).
- ASLR seed: `Instance` 별 고정값 → 동일 binary 가 같은 인스턴스에서 재실행 시 동일 주소 (디버깅 편의).
- `mmap(NULL, alignedSize, PROT_NONE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0)` 로 reserve 후 PT_LOAD 별 `mprotect` + `pread`.

#### B.2.b PT_INTERP 처리

- guest binary 의 `PT_INTERP` 는 보통 `/system/bin/linker64` 다.
- ELF loader 가 binary 본체를 매핑한 뒤 **linker 도 동일하게 매핑** 하고, entry 는 binary 가 아니라 linker 의 entry 가 된다.

#### B.2.c aux vector 빌더

```cpp
struct AuxVector {
    void push(uint64_t type, uint64_t value);
    std::vector<uint64_t> data() const;
};

AuxVector buildAuxVector(const LoadedElf& binary, const LoadedElf& linker);
```

최소 entries:

- `AT_PHDR`, `AT_PHENT`, `AT_PHNUM` (binary).
- `AT_BASE` (linker base).
- `AT_ENTRY` (binary entry).
- `AT_PAGESZ` = 4096.
- `AT_RANDOM` = 16 bytes random (instance 별 고정).
- `AT_PLATFORM` = "aarch64".
- `AT_HWCAP` / `AT_HWCAP2` = host CPU 가 보고하는 값.
- `AT_NULL` 로 종료.

#### B.2.d Stack 준비

```text
Top of stack:
[ argc ]
[ argv pointers... NULL ]
[ envp pointers... NULL ]
[ aux vector entries... AT_NULL ]
[ argv 문자열들 ]
[ envp 문자열들 ]
[ AT_RANDOM 데이터 ]
[ AT_PLATFORM 문자열 ]
```

stack 은 `mmap(MAP_GROWSDOWN | MAP_STACK)` 으로 8 MiB 할당.

#### B.2.e Entry 직전 동작

binary 가 PIE 이고 linker 를 통해 진입하므로, 우리가 제어를 넘기는 함수는 **linker 의 `_start`** 다. ARM64 ABI:

```text
x0 = (없음, 0)
sp = 위에서 만든 스택 top (16-byte 정렬)
pc = linker 의 e_entry
```

이 주소로 점프하는 것은 별도 inline asm 또는 `__builtin_eh_return` 우회. B.5 (process state) 에서 별도 thread 안에서 실행할 예정이므로 `setcontext` 도 후보.

### 5.2.5 검증 게이트

- 단위 테스트 `ElfLoaderTest`:
  - fixture ELF (예: 작은 hello world) 의 `e_entry` 가 PT_LOAD 범위 안.
  - PT_INTERP 문자열이 `/system/bin/linker64` 와 일치.
  - aux vector 가 AT_NULL 로 끝남.
- 회귀 라인: `STAGE_PHASE_B_ELF passed=true entry=0x... interp=ok aux=ok`.

### 5.2.6 위험

- **mmap 권한**: 호스트 SELinux/seccomp 정책이 `MAP_FIXED + PROT_EXEC` 를 차단할 수 있음. 안전을 위해 `MAP_FIXED_NOREPLACE` 사용 + 실패 시 다른 base 재시도.
- **W^X / `execmem` 정책 (Critical)**: Android 10+ 의 SELinux `untrusted_app` domain 은 `execmem`, `execmod` 를 거부할 수 있다. `mmap(..., PROT_READ|PROT_EXEC, MAP_PRIVATE | MAP_ANONYMOUS)` 가 `EACCES` 로 실패하면 ELF loader 자체 동작 불가 → fallback:
  1. `memfd_create("avm-elf", MFD_CLOEXEC)` 로 named fd 를 만든 뒤 거기에 ELF 본체 write → `mmap(fd, PROT_READ|PROT_EXEC)` (file-backed mapping 은 `execute_no_trans` 정책에서 허용 가능).
  2. 그래도 실패하면 그 인스턴스는 RUNNING 으로 진입하지 못하고 `STAGE_PHASE_B_ELF passed=false reason=execmem_denied` 로 끝나야 한다 (silent failure 금지).
  3. 사용자에게 "이 기기는 ELF 실행을 허용하지 않음" 을 UI 로 통지.
- **ABI 호환**: arm32 / x86 binary 는 별도 (Phase E.8). ABI mismatch 시 명시 에러 반환.
- **PIE 강제**: `e_type == ET_DYN` (PIE) 만 지원. ET_EXEC (non-PIE) 는 거부.
- **다중 Android ABI 확장점**: B.2 의 `loadElf64` 는 7.1.2 의 ART/linker chain 에 fix. Phase E.3/E.4 에서 Android 10/12 ART chain 추가를 위해, 첫 구현부터 `LinkerProfile` 같은 dispatch hook 자리를 둔다 (구체 구현 없이 인터페이스만):

  ```cpp
  struct LinkerProfile {
      const char* interpreterPath;   // /system/bin/linker64
      const char* abiPlatform;       // AT_PLATFORM 문자열 (검증 필요: aarch64 vs armv8l)
      uint64_t    abiHwcap;
      uint64_t    abiHwcap2;
  };
  ```

  Phase E.3/E.4 가 들어오면 guest manifest 의 `guestAndroidVersion` 으로 profile 을 선택.

---

## Step B.3 — Bionic linker 통합

### 5.3.1 Background

PT_INTERP 의 `linker64` 가 `__libc_init` 까지 도달하려면 다음 syscall 이 동작해야 한다 (B.4 와 부분적으로 의존):

- `mmap`, `mprotect`, `munmap`.
- `openat`, `read`, `pread64`, `close`.
- `prctl` (PR_SET_NAME, PR_SET_NO_NEW_PRIVS).
- `getauxval` (라이브러리 내부; `auxv` 값을 통해 자동).
- `set_tls` (arm64 의 TPIDR_EL0 설정 — `prctl(PR_SET_FS)` 가 아니라 `clone` 시 설정 또는 syscall 472).

### 5.3.2 목표

- `linker64` 의 `__linker_init` 진입.
- `linker_main` 안에서 `libc.so`, `libdl.so` 가 dlopen 되어 `__libc_init` 으로 진입.
- guest 가 own `argv[0]` 을 출력 가능.

### 5.3.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/loader/linker_bridge.{h,cpp}` (NEW) | linker 와 host loader 충돌 회피 |
| `app/src/main/cpp/loader/elf_loader.cpp` | PT_INTERP 매핑 통합 |
| `app/src/main/cpp/syscall/...` | 위 syscall handlers |

### 5.3.4 세부 작업

#### B.3.a Namespace 분리

host `dlopen` 이 가져온 라이브러리와 guest linker 가 가져온 라이브러리가 같은 process address space 안에 공존한다. linker 가 `dl_iterate_phdr` 에서 host linker 의 segment 를 보면 안 된다. 방법:

- guest linker 가 보는 `_DYNAMIC` 와 `link_map` 을 별도 chain 으로 보관.
- host symbol 에는 weak override 를 두고, guest 의 `dlopen` 이 자체 chain 에서 우선 검색.

#### B.3.b TLS 초기화

ARM64 의 thread-local storage 는 `TPIDR_EL0` 가 가리킨다. linker 가 `pthread_create` 또는 `clone` 시 `CLONE_SETTLS` 와 함께 설정한 값을 trust 한다. 호스트가 해당 register 를 사용 중일 수 있으므로 guest thread 만 자체 TLS 영역을 갖도록 분리.

#### B.3.c libc init 도달 게이트

- `__libc_init` 의 첫 instruction 직전에 hook 을 두기는 어렵다 → 대안: linker 가 `__libc_init` 을 호출하기 직전에 우리가 patched ABI 를 사용해 callback 을 받는 stub 함수로 PLT 를 우회.
- 가장 단순한 검증: guest 가 `puts("hello")` 를 호출했을 때 stdout (host pipe) 에 "hello" 가 도달.

### 5.3.5 검증 게이트

- 회귀 라인: `STAGE_PHASE_B_LINKER passed=true libc_init=ok stdout=ok`.
- 수동 회귀: minimal `hello` binary 가 stdout 으로 "hello" 출력.

### 5.3.6 위험

- **Bionic ABI 차이**: Android 7.1.2 의 linker64 와 더 신 버전의 ABI 차이. 우선 7.1.2 만 타겟 (Phase E.3 에서 10/12 추가).
- **cgroup / SELinux 정책 거부**: linker 가 cgroup / SELinux 정책을 읽으려 할 때 host 가 거부할 수 있음. 그 syscall 들은 빈 응답 + 성공으로 stub 처리.
- **host bionic ↔ guest bionic 충돌 (Critical)**: host 프로세스에는 이미 host bionic libc 가 로드되어 있다. guest 가 같은 process 안에 또 다른 libc.so / libdl.so 를 매핑하면:
  - **TLS region 겹침**: ARM64 의 `TPIDR_EL0` 가 한 register, 두 libc 가 다른 layout 을 가정 → SIGSEGV.
  - **Symbol versioning clash**: `pthread_create`, `malloc` 등이 weak symbol 로 host bionic 으로 lower 해질 가능성.
  - 완화: linker_bridge 가 guest 영역 dlopen 결과를 host symbol resolution 에서 격리. guest thread 진입 직전 `TPIDR_EL0` 를 guest TLS block 으로 swap, 진출 직후 host TLS 복원. 단위 테스트로 round-trip 검증.

---

## Step B.4 — Syscall dispatch table

### 5.4.1 Background

guest binary 가 동작하려면 host 가 받은 syscall 을 user-space dispatcher 로 가져와 VFS / fd table / signal / futex 로 라우팅해야 한다. 호스트 SELinux 정책에 따라 trap 메커니즘 선택지가 갈린다.

### 5.4.2 목표

- 약 25 개 syscall 이 dispatcher 를 거쳐 동작.
- `Stage7DiagnosticsReceiver` 의 syscall smoke 가 stub 이 아닌 실제 syscall round-trip 으로 통과.

### 5.4.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/syscall/syscall_dispatch.{h,cpp}` (NEW) | 핵심 |
| `app/src/main/cpp/syscall/futex.{h,cpp}` (NEW) | futex emulation |
| `app/src/main/cpp/syscall/signal.{h,cpp}` (NEW) | sigaction stub + tgkill |
| `app/src/main/cpp/syscall/process.{h,cpp}` (NEW) | gettid / getpid / exit_group |
| `app/src/main/cpp/syscall/io.{h,cpp}` (NEW) | openat/read/write/close/lseek/fstat/ioctl |
| `app/src/main/cpp/syscall/mem.{h,cpp}` (NEW) | mmap/mprotect/brk |
| `app/src/main/cpp/syscall/time.{h,cpp}` (NEW) | clock_gettime/nanosleep |

### 5.4.4 세부 작업

#### B.4.a Trap 메커니즘 결정

옵션 비교:

| 옵션 | 설명 | 장점 | 단점 |
|---|---|---|---|
| A. seccomp + SIGSYS | `seccomp(SECCOMP_SET_MODE_FILTER)` + `signal(SIGSYS)` 으로 trap | 호환성 큼 | 호스트 Android 정책이 seccomp 를 잠그면 사용 불가 |
| B. ptrace | host 가 guest thread 를 ptrace | 안정적 | 별도 process 필요, 성능 큰 손실 |
| C. binary translation | guest binary 의 `svc #0` 을 사전 patching | 빠름 | 동적 코드 (JIT'd dex) 처리 어려움 |
| D. user-space libc 교체 | guest `libc.so` 의 syscall stub 을 host hook 으로 교체 | 가장 단순 | 정적 binary 미지원 |

**MVP 권장: 옵션 D + 옵션 A 의 hybrid**.

- guest libc 의 `__syscall` 함수 entry 를 우리 dispatcher 로 redirect (binary patch + trampoline).
- 정적 링크된 binary 는 옵션 A (seccomp+SIGSYS) 로 fallback.

#### B.4.b Syscall 번호 ↔ handler 표

```cpp
struct SyscallHandler {
    const char* name;
    int (*handler)(Instance&, uint64_t a, uint64_t b, uint64_t c,
                   uint64_t d, uint64_t e, uint64_t f);
};

extern const SyscallHandler kSyscallTable[NR_max];

void registerSyscall(int nr, SyscallHandler h);
```

ARM64 syscall 번호 (`<asm/unistd.h>` 의 generic table):

| nr | name |
|---|---|
| 56 | openat |
| 57 | close |
| 63 | read |
| 64 | write |
| 80 | fstat |
| 79 | newfstatat |
| 222 | mmap |
| 226 | mprotect |
| 215 | munmap |
| 214 | brk |
| 113 | clock_gettime |
| 101 | nanosleep |
| 96 | set_tid_address |
| 178 | gettid |
| 172 | getpid |
| 174 | getuid |
| 175 | geteuid |
| 29 | ioctl (selective) |
| 98 | futex |
| 131 | tgkill |
| 134 | rt_sigaction |
| 135 | rt_sigprocmask |
| 261 | prlimit64 |
| 94 | exit_group |
| 47 | fallocate |

> 주의: 위 표는 ARM64 의 `<asm-generic/unistd.h>` 기준이다. 본 step 착수 시점에 NDK 의 `<sys/syscall.h>` (또는 bionic `kernel/uapi/asm-generic/unistd.h`) 와 한 번 더 cross-check 하여 표를 commit. `tgkill` 은 130 (rt_sigsuspend) 와 인접하므로 오타 빈발 — `__NR_tgkill` 매크로로 직접 가져오는 것을 권장.

#### B.4.c VFS 통합

`openat`/`read`/`write` 는 기존 `vfs/path_resolver` + `vfs/fd_table` 을 그대로 이용. fd 번호 공간:

- 1000~ : guest fd (현재 구조).
- 0/1/2 : guest stdin/stdout/stderr → host pipe.

#### B.4.d Futex emulation

- `FUTEX_WAIT`, `FUTEX_WAKE` 만 우선 지원.
- 인스턴스별 `std::map<uint64_t, std::condition_variable>` 로 word address 키.
- `FUTEX_PRIVATE_FLAG` 만 지원, shared 은 거부 (unsupported).

#### B.4.e Signal stub

- `rt_sigaction` 은 instance 별 sigaction table 에 저장만.
- `tgkill` 은 self thread kill 만 처리 → guest exit.
- 본격적인 signal delivery 는 Phase C.4 (zygote) 에서 강화.

### 5.4.5 검증 게이트

- 단위 테스트 `SyscallSmokeTest` (host 측 cpp test):
  - `openat → write → read → close` round-trip.
  - `mmap` PROT_NONE + `mprotect` PROT_READ 후 dereference 가능.
  - `clock_gettime(CLOCK_MONOTONIC)` 이 양수 + 단조 증가.
  - `futex(FUTEX_WAIT)` + 다른 thread 의 `FUTEX_WAKE` 로 깨움.
- 회귀 라인: `STAGE_PHASE_B_SYSCALL passed=true ops=openat,read,write,close,mmap,mprotect,clock_gettime,futex`.

### 5.4.6 위험

- **`svc #0` register state 보존**: ARM64 `svc #0` 트랩 후 register state 보존이 까다로움. 잘못하면 guest 가 프로세스를 죽인다.
- **ioctl wildcard 불가**: ioctl 은 wildcard 처리 불가. binder/ashmem 관련만 호출되도록 binary 유도, 그 외는 ENOSYS.
- **호스트 사전 install seccomp filter**: 호스트 Android 가 자체 seccomp filter 를 process startup 전에 install 한 경우, 우리 SIGSYS handler 가 reach 못한다. 이 경우 옵션 A (seccomp+SIGSYS) 와 옵션 C (binary translation) 모두 작동 불가 → 옵션 D (libc 교체) 단독으로 운영해야 한다. 결정 트리:

  ```text
  start:
    if (호스트 seccomp 가 SIGSYS 를 deliver 가능)
        → 옵션 A 사용
    else if (guest libc 의 syscall stub 을 우리가 patch 가능)
        → 옵션 D 단독
    else
        → STAGE_PHASE_B_SYSCALL passed=false reason=trap_unavailable, 인스턴스 미진입
  ```

  결정은 instance lifecycle 의 LOADING 단계에서 1 회 수행하고 audit log 에 기록.

---

## Step B.5 — Process state machine

### 5.5.1 Background

현재 `dummyGuestEntrypoint` 는 단일 thread 안에서 로그만 찍고 끝난다. 진짜 process model 이 도입되어야 zygote (Phase C.4) 가 fork-like semantics 로 system_server 를 띄울 수 있다.

Phase B 시점에서는 fork 없이 **단일 process / thread 시뮬레이션** 만 갖춘다.

### 5.5.2 목표

- guest "process" 의 lifecycle 을 명시적 state machine 으로 관리.
- `exit_group` syscall 시 instance 의 `bootstrapStatus` 가 `guest_exit=ok exit_code=0` 으로 갱신.

### 5.5.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/loader/guest_process.{h,cpp}` (NEW) | state machine + thread |
| `app/src/main/cpp/core/instance.h` | `enum class GuestProcessState { CREATED, LOADING, RUNNING, ZOMBIE, REAPED }` |
| `app/src/main/cpp/core/event_loop.cpp` | `dummyGuestEntrypoint` 제거 |

### 5.5.4 세부 작업

#### B.5.a State 정의

```cpp
enum class GuestProcessState {
    CREATED,     // initInstance 직후
    LOADING,     // ELF loader / linker 실행 중
    RUNNING,     // _start 진입 후 main loop
    ZOMBIE,      // exit_group 호출됨, 부모 reap 대기
    REAPED       // 부모가 wait 한 상태
};
```

#### B.5.b 전이 규칙

```text
CREATED → LOADING : startGuest() 호출
LOADING → RUNNING : linker 가 __libc_init 진입 직후
RUNNING → ZOMBIE  : exit_group syscall 또는 SIGSEGV
ZOMBIE  → REAPED  : VmInstanceService 가 stopGuest() 또는 destroyInstance()
```

#### B.5.c thread 관리

- B.1 에서 분리한 `core/event_loop` 의 guest thread 가 새 state machine 을 따른다.
- 진입 직전 stack/aux vector 준비 → 진입 → exit 후 cleanup.
- ZOMBIE 상태에서 호출자에게 exit_code 노출.

### 5.5.5 검증 게이트

- 진단 라인:

```text
STAGE_PHASE_B_LIFECYCLE passed=true states=CREATED,LOADING,RUNNING,ZOMBIE,REAPED exit_code=0
```

- 단위: `lifecycle_test` — 전이 시퀀스가 위 순서대로 발생.

---

## Step B.6 — Single binary 실행 PoC

### 5.6.1 Background

Phase B 의 모든 step 이 합쳐졌을 때 무엇이 동작해야 하는지 명문화하는 step.

### 5.6.2 목표

- arm64 PIE binary `/system/bin/avm-hello` 가 guest 안에서 실행.
- stdout 으로 "hello" 가 host 측 instance log 에 도달.

### 5.6.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/loader/guest_process.cpp` | 외부 helper `runGuestBinary(...)` |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmNativeBridge.kt` | external `runGuestBinary` |
| `tools/create_debug_guest_fixture.sh` | 실제 arm64 PIE `avm-hello` 또는 `toybox` + `linker64` / `libc.so` / `libdl.so` 포함 |
| `app/src/debug/java/dev/jongwoo/androidvm/debug/StagePhaseBDiagnosticsReceiver.kt` (NEW) | 구동/검증 |

### 5.6.4 세부 작업

#### B.6.a JNI 추가

```kotlin
external fun runGuestBinary(
    instanceId: String,
    binaryPath: String,
    args: Array<String>,
    timeoutMillis: Long,
): Int  // exit code, -1 on timeout
```

#### B.6.b stdout 캡처

guest 의 fd 1 (stdout) 을 host 의 pipe 로 redirect → 별도 reader thread 가 instance log 에 한 줄씩 append.

#### B.6.c receiver

`StagePhaseBDiagnosticsReceiver` 가:

1. `runGuestBinary("vm1", "/system/bin/avm-hello", emptyArray(), 5000)` 실행.
2. exit code == 0 인지 확인.
3. instance log 의 마지막 entries 에서 "hello" 발견 여부 확인.
4. 결과를 라인으로 출력.

### 5.6.5 검증 게이트

- 회귀 라인:

```text
STAGE_PHASE_B_BINARY passed=true binary=/system/bin/avm-hello exit=0 stdout=hello
```

- Stage 4·5·6·7 회귀 라인 모두 통과 유지.

### 5.6.6 위험

- guest binary 가 의존하는 라이브러리 (`/system/bin/linker64`, `/system/lib64/libc.so`, `/system/lib64/libdl.so`) 가 fixture 에 포함되어야 함. 현재 script fixture 는 Phase B gate 를 통과할 수 없으므로 fixture 빌드 스크립트 갱신 필수.

---

## Step B.7 — Phase B 종합 회귀 receiver

### 5.7.1 Background

Phase A 와 동일한 패턴: phase-level summary 라인.

### 5.7.2 목표 라인

```text
STAGE_PHASE_B_RESULT passed=true modular=true elf=true linker=true syscall=true lifecycle=true binary=true stage_phase_a=true
```

### 5.7.3 작업

- `StagePhaseBDiagnosticsReceiver` 가 Phase A 라인을 먼저 emit 한 뒤 Phase B 라인 emit (회귀 강제).
- `StagePhaseBFinalGateTest` 가 출력 형식을 픽스.

---

## 6. Phase B 종료 게이트

다음을 **모두** 만족해야 Phase C 의 어떤 step 도 시작하지 않는다.

- [ ] `STAGE_PHASE_B_RESULT passed=true modular=true elf=true linker=true syscall=true lifecycle=true binary=true stage_phase_a=true` 가 emulator log 에 기록.
- [ ] 단일 PIE arm64 binary 가 host 프로세스 안에서 실행되어 stdout 한 줄을 출력.
- [ ] `StagePhaseBFinalGateTest` 통과.
- [ ] Stage 4 receiver 의 "guest binary run" 이 stub 이 아닌 실제 실행으로 통과.
- [ ] Stage 5/6/7 회귀 라인 미회귀.
- [ ] CI gate 통과.

## 7. 비목표

- 진짜 binder transaction (Phase C.1).
- ashmem / memfd shim (Phase C.2).
- zygote / system_server (Phase C.4 ~ C.5).
- bridge 실 구현 (Phase D).

## 8. 참고

- 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 4 단계 (4.1~4.6) + 권장 순서 4~5번.
- 게이트 인덱스: `docs/planning/future-roadmap.md` Phase B 절.
- 위험 요약: 동일 plan "큰 위험" 절 + `docs/planning/post-stage7-roadmap.md` 1.1~1.3.

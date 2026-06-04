# EP2 — Real Guest Execution Core (Option B) 설계/작업 노트

> 작성일: 2026-06-04
> 상위: [`production-execution-phases.md`](./production-execution-phases.md) EP2 · [`ep1-guest-arch-spike.md`](./ep1-guest-arch-spike.md)
> 상태: **핵심 메커니즘 실증** — EP2.1 완료, EP2.2 부트스트랩·EP2.3 게이트웨이 에뮬레이터 실증(API 29/36), EP2.5 TLS 분리 확인, EP2.8 ALLOW 확인. 잔여: 클린룸 게스트 ROM 부팅 통합 + EP2.4/2.6/2.7 서비싱 폭(아래 §3·§5).

## 0. 전제 — 정직한 경계

- **EP1 결정 확정**: `GUEST_ARCH_DECISION approach=B(confirmed: emulator API29/API35 arm64)`. capability probe가 API 29(Android 10 W^X 시작점)·API 35 arm64 에뮬레이터에서 `seccomp_trap && (memfd_exec || prot_exec_mmap)=true`를 확인(참조: `ep1-guest-arch-spike.md` §10.1). 따라서 EP2는 **Option B**(forked child + 진짜 linker64 + seccomp `SECCOMP_RET_TRAP`/SIGSYS) 위에서 진행한다. 물리 기기 재확인은 권장 잔여 항목.
- **연구급·실기기 의존**: EP2의 본체(진짜 부팅까지)는 seccomp/W^X/SELinux 동작이 기기에서만 검증되는 다개월 작업이다. macOS·무기기 환경에서는 **설계·정책·시뮬레이션 격리**까지만 검증 가능하다. `G1_RESULT`는 정직하게 거짓으로 둔다.

## 1. Option B 게이트웨이 아키텍처

```
host process (avm_host, :vmN)
  └─ fork() ──► guest child process
                 ├─ 자기 주소공간/TPIDR/시그널/스레드 (분리로 TLS·시그널·clone 자연 해소)
                 ├─ prctl(PR_SET_NO_NEW_PRIVS) + seccomp BPF (GuestSyscallPolicy에서 생성)
                 │    ├─ ALLOW   → 커널 직접
                 │    └─ TRAP    → SIGSYS
                 ├─ SIGSYS 핸들러 → host로 라우팅(VFS/property/binder/network)
                 └─ 진짜 /system/bin/linker64 진입(aux vector) → libc → app_process64 → ART → onCreate
```

- 코드 로딩은 **execve 회피** — 기존 ELF 로더(`loader/elf_loader.cpp`, memfd fallback)로 매핑. 비루트 W^X 대응.
- syscall 분류는 [`GuestSyscallPolicy`](../../app/src/main/java/dev/jongwoo/androidvm/vm/GuestSyscallPolicy.kt)가 단일 출처 — BPF 필터와 SIGSYS 라우팅이 같은 표를 따른다.

## 2. 이번 턴에 완료한 증분

### EP2.1 — 시뮬레이션 부팅 격리 ✅
- `phaseBGuestRuntimeEntrypoint`의 합성 부팅에 **명시 라벨** 추가: `bootstrapStatus`에 `runtime_mode=simulated;` prepend + `avm.runtime.simulated=1` 프로퍼티. (`jni/vm_native_bridge.cpp`)
- 기존 debug Phase A–E 진단(`.contains(...)` 기반)은 영향 없음(prepend라 substring 보존).
- 판정 oracle [`GuestBootStatus`](../../app/src/main/java/dev/jongwoo/androidvm/vm/GuestBootStatus.kt): `isRealGuestBoot = 마커 전부 존재 && !simulated`. 현재 유일한 부팅이 simulated라 false → product gate `boot=` 정직하게 거짓 유지. EP2.2+가 라벨을 제거하면 true가 된다.
- 검증: `GuestBootStatusTest`(JVM), 네이티브 컴파일.

### EP2.2 — 진짜 linker64 부트스트랩 메커니즘 🟡 (에뮬레이터 실증)
- 초기 프로세스 스택/aux vector 빌더 [`loader/initial_stack.cpp`](../../app/src/main/cpp/loader/initial_stack.cpp): 커널 ABI 레이아웃(argc/argv/envp/auxv + AT_RANDOM/AT_PLATFORM/AT_EXECFN, 16바이트 정렬) + arm64/x86_64/arm 점프 트램펄린(`jumpToGuestEntry`).
- 메커니즘 프로브 [`spike/linker_bootstrap_probe.cpp`](../../app/src/main/cpp/spike/linker_bootstrap_probe.cpp) + `LinkerBootstrapProbe` + debug `LinkerBootstrapProbeReceiver`: fork child에서 실제 PIE+그 PT_INTERP linker를 `mapElf64`로 매핑→스택 구성→링커 진입점 점프, child stdout/stderr 캡처.
- **실증 결과 (2026-06-04, 에뮬레이터 자신의 `/system/bin/{app_process64,linker64}`를 프록시로):**

  | 타깃 | exec_mapped | linker_mapped | linker_ran | child | output |
  | --- | --- | --- | --- | --- | --- |
  | API 29 arm64 | ✅ | ✅ | ✅ | SIGABRT | `ANDROID_DATA environment variable unset` |
  | API 36 arm64 | ✅ | ✅ | ✅ | SIGABRT | `ANDROID_DATA environment variable unset` |

  `ANDROID_DATA...`는 링커가 아니라 **`app_process64`/AndroidRuntime**이 낸 메시지 → 실제 bionic linker64가 자기 재배치 + `app_process64`와 의존 라이브러리 링킹 + 실행 파일 entry 점프 + 런타임 진입까지 도달했음을 의미(스택/auxv/트램펄린 정확성 실증). SIGABRT는 빈 환경으로 실행한 app_process의 정상 abort.
- **한계(정직)**: ① 프록시는 에뮬레이터 자신의 Android 바이너리 — 클린룸 게스트 ROM이 아님. 제품 부팅은 사용자 ROM의 linker를 동일 코드로 돌리고 VFS/seccomp 게이트웨이를 얹어야 함(EP2.3+). ② seccomp 미설치 상태(syscall이 호스트 커널 직접) — EP2.2는 부트스트랩만, 서비싱은 EP2.3.
- 검증: 네이티브 컴파일(3 ABI, 심볼 링크), 에뮬레이터 실행(API 29/36).

### EP2.3 — syscall 서비싱: 정책 + seccomp SIGSYS 게이트웨이 🟡 (에뮬레이터 실증)
- 정책 spec [`GuestSyscallPolicy`](../../app/src/main/java/dev/jongwoo/androidvm/vm/GuestSyscallPolicy.kt): ALLOW/HOST_SERVICED/DENY + 미분류 fail-closed. 검증: `GuestSyscallPolicyTest`(JVM).
- 게이트웨이 [`spike/syscall_gateway.cpp`](../../app/src/main/cpp/spike/syscall_gateway.cpp): `PR_SET_NO_NEW_PRIVS` + seccomp BPF(TRAP=host-serviced(데모: `uname`), ERRNO=forbidden(데모: `ptrace`), ALLOW=나머지) + SIGSYS 핸들러(ucontext 레지스터로 인자 읽고 합성 서비싱 후 반환값 설정, 재귀 없음).
- **실증 결과 (2026-06-04, API 29 arm64):**
  - (a) uname 서비싱: `gateway_installed=true uname_ret=0 sysname=Linux machine=aarch64 serviced=1 child_exit=0` → **trap→검사→합성 서비싱→반환** 루프 정상(크래시·재귀 없음).
  - (b) 게이트웨이+부트스트랩: `gateway=true linker_ran=true` (app_process가 AndroidRuntime 진입) → **ALLOW-list가 실제 링커/게스트 실행과 양립**.
- 한계: 데모는 `uname`(합성) + `ptrace`(EPERM). 실제 서비싱 폭(openat 경로 재작성, binder ioctl, brk/mmap 등)은 게스트 ROM + APK corpus와 함께 확장(EP2.4/2.6/2.7, P2). hot-path는 ALLOW로 두어 비용 회피.

## 3. 하위 단계 상태표

| Step | 내용 | 상태 | 검증 수단 |
| --- | --- | --- | --- |
| EP2.1 | 시뮬레이션 부팅 격리 | ✅ 완료 | JVM + 네이티브 컴파일 |
| EP2.2 | 진짜 linker64 부트스트랩 + aux vector | 🟡 메커니즘 실증(에뮬레이터 API29/36: 실제 linker가 PIE 링킹·런타임 진입). 클린룸 게스트 ROM 부팅은 EP2.3+ 게이트웨이 후 | on-device ✅ |
| EP2.3 | syscall 실서비싱 | 🟡 정책 + seccomp SIGSYS 게이트웨이 메커니즘 실증(uname 서비싱 + 부트스트랩 양립, API 29). 서비싱 폭은 ROM/corpus와 확장 | JVM(정책) + on-device ✅ |
| EP2.4 | heap/mmap 실제화(가짜 brk 제거) | ⬜ 게이트웨이 ALLOW(커널 직접) 또는 핸들러 서비싱으로 처리 — ROM 통합 시 | on-device |
| EP2.5 | TLS/시그널 경계(프로세스 분리로 해소) | ✅ 실증 — 실제 linker가 fork child에서 host TLS 손상 없이 실행(EP2.2), 게이트웨이는 ucontext 기반 | on-device ✅ |
| EP2.6 | VFS 경로 재작성 + fd_table 실구현 | ⬜ SIGSYS 핸들러의 openat 서비싱으로 — ROM 통합 시 | 일부 JVM oracle |
| EP2.7 | /proc·/sys 최소 가상화 | ⬜ 핸들러 서비싱 — ROM 통합 시 | on-device |
| EP2.8 | clone(thread) 지원 | 🟡 정책상 ALLOW(커널 직접) — capability probe `clone_thread=true`로 가용 확인 | on-device ✅ |

## 4. 실기기 선행 작업 (EP2 본체 착수 조건)

1. ~~**EP1 probe 실행** → `option_b_viable=true` 확인.~~ ✅ 완료 — API 29/35 arm64 에뮬레이터에서 확인(`ep1-guest-arch-spike.md` §10.1).
2. (권장 잔여) 물리 arm64 기기에서 동일 probe 1회 재확인.
3. EP2.2(linker 부트스트랩) → seccomp 게이트웨이(`GuestSyscallPolicy`에서 BPF 생성) → EP2.3 동작 → 순차. **착수 가능.**

## 5. 출구 게이트 — 현재 상태(정직)

```text
G1_RESULT passed=false linker_real=mechanism-validated(emulator) reloc_applied=true(proxy) syscalls_real=gateway-validated(emulator) heap_real=allow tls_safe=true(process-isolation) vfs_mapped=pending(rom) thread_create=allow-validated synthetic=isolated
```

- `linker_real=mechanism-validated`: 실제 bionic linker64가 우리 스택/auxv/트램펄린으로 실제 PIE를 링킹·실행(EP2.2, API29/36). 게스트 ROM 부팅 시 `true`.
- `syscalls_real=gateway-validated`: seccomp SIGSYS 게이트웨이가 실제 syscall을 trap→서비싱→반환(EP2.3, uname). 서비싱 폭은 ROM/corpus와 확장.
- `tls_safe=true(process-isolation)`: fork child 분리로 host/guest TLS 충돌 없음 — 실제 linker 실행으로 확인(EP2.2/2.5).
- `thread_create=allow-validated`: 정책상 ALLOW + capability probe `clone_thread=true`.
- `vfs_mapped=pending(rom)`: openat 경로 재작성은 게스트 ROM 통합 시(EP2.6).
- `synthetic=isolated`: 합성 부팅 라벨 격리(EP2.1). `synthetic=0`은 실제 게스트 부팅 대체 시.
- **`passed=false` 유지**: 핵심 메커니즘(부트스트랩·게이트웨이)은 에뮬레이터에서 실증됐으나, **클린룸 게스트 ROM 부팅 end-to-end는 미달성**(ROM 부재 + VmInstanceService 부팅 경로 통합 잔여). canned 통과 금지.

## 6. 변경 파일(이번 턴)

- `app/src/main/cpp/jni/vm_native_bridge.cpp` — 시뮬레이션 부팅 라벨링
- `app/src/main/java/.../vm/GuestBootStatus.kt` (+ test)
- `app/src/main/java/.../vm/GuestSyscallPolicy.kt` (+ test)
- 본 문서

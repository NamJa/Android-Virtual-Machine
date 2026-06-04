# EP2 — Real Guest Execution Core (Option B) 설계/작업 노트

> 작성일: 2026-06-04
> 상위: [`production-execution-phases.md`](./production-execution-phases.md) EP2 · [`ep1-guest-arch-spike.md`](./ep1-guest-arch-spike.md)
> 상태: **착수(증분)** — EP2.1 완료, EP2.3 정책 기반 완료. 나머지는 실기기 의존(아래 §4).

## 0. 전제 — 정직한 경계

- **EP1 게이트 미확정**: `GUEST_ARCH_DECISION passed=false`. capability probe를 실기기에서 아직 안 돌렸다. EP2는 **잠정 Option B**(forked child + 진짜 linker64 + seccomp `SECCOMP_RET_TRAP`/SIGSYS) 위에서 진행하되, probe가 `seccomp_trap && (memfd_exec || prot_exec_mmap)`를 확인하기 전에는 게이트웨이 본체를 확정 빌드하지 않는다.
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

### EP2.3 — syscall 서비싱 정책 기반 ✅
- [`GuestSyscallPolicy`](../../app/src/main/java/dev/jongwoo/androidvm/vm/GuestSyscallPolicy.kt): ALLOW/HOST_SERVICED/DENY 분류 + 미분류 fail-closed(DENY). BPF 필터 생성·SIGSYS 라우팅의 spec.
- 검증: `GuestSyscallPolicyTest`(JVM) — 대표 분류 + allow/trap 비중첩 + fail-closed.

## 3. 하위 단계 상태표

| Step | 내용 | 상태 | 검증 수단 |
| --- | --- | --- | --- |
| EP2.1 | 시뮬레이션 부팅 격리 | ✅ 완료 | JVM + 네이티브 컴파일 |
| EP2.2 | 진짜 linker64 부트스트랩 + aux vector | ⛔ 실기기 의존(probe 후) | on-device |
| EP2.3 | syscall 실서비싱 | 🟡 정책 spec 완료, 네이티브 게이트웨이 미구현 | JVM(정책) / on-device(동작) |
| EP2.4 | heap/mmap 실제화(가짜 brk 제거) | ⬜ 미착수 | on-device |
| EP2.5 | TLS/시그널 경계(프로세스 분리로 해소) | ⬜ 설계 확정(§1) | on-device |
| EP2.6 | VFS 경로 재작성 + fd_table 실구현 | ⬜ 미착수 | 일부 JVM oracle 가능 |
| EP2.7 | /proc·/sys 최소 가상화 | ⬜ 미착수 | on-device |
| EP2.8 | clone(thread) 지원 | ⬜ 미착수(정책상 ALLOW) | on-device |

## 4. 실기기 선행 작업 (EP2 본체 착수 조건)

1. **EP1 probe 실행** → `option_b_viable=true` 확인. (`adb ... RUN_GUEST_EXEC_PROBE`)
2. 거짓이면: B′(ptrace) 평가 또는 타깃 범위 재정의 — EP2 본체 착수 보류.
3. 참이면: EP2.2(linker 부트스트랩) → seccomp 게이트웨이(`GuestSyscallPolicy`에서 BPF 생성) → EP2.3 동작 → 순차.

## 5. 출구 게이트 — 현재 상태(정직)

```text
G1_RESULT passed=false linker_real=false reloc_applied=false syscalls_real=false heap_real=false tls_safe=false vfs_mapped=false thread_create=false synthetic=isolated
```

- `synthetic=isolated`: 합성 부팅이 명시 라벨(`runtime_mode=simulated`)로 격리됨 — 제품 경로가 실제로 인정하지 않음(EP2.1). 단 `synthetic=0`(완전 제거)은 EP2.2가 실제 부팅으로 대체할 때 달성.
- 그 외 전부 false: 실제 실행 코어가 아직 없음. canned 통과 금지 원칙에 따라 거짓 유지.

## 6. 변경 파일(이번 턴)

- `app/src/main/cpp/jni/vm_native_bridge.cpp` — 시뮬레이션 부팅 라벨링
- `app/src/main/java/.../vm/GuestBootStatus.kt` (+ test)
- `app/src/main/java/.../vm/GuestSyscallPolicy.kt` (+ test)
- 본 문서

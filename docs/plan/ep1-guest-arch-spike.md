# EP1 — Guest Architecture Decision (Spike)

> 작성일: 2026-06-04
> 상위: [`production-execution-phases.md`](./production-execution-phases.md) EP1
> 목적: G-track(EP2–EP4) 착수 전, 게스트 실행 아키텍처를 근거로 결정한다. 이 spike의 산출물은 **결정 + 실증 계획**이며, 출구 게이트는 정직하게 실기기 실증 후에만 닫힌다.

## 1. 출발점 — 기존 코드의 실제 실행 메커니즘

조사 결과(파일:라인 인용은 §10), 현재 "in-process 실행"으로 불리던 경로의 실제 동작:

- `runGuestBinary` → `phase_b_bridge.cpp`가 ELF의 PT_LOAD를 host에 매핑한 뒤 **`fork()`** 하고(`phase_b_bridge.cpp:371`), child에서 게스트 entry로 함수포인터 점프(`entry(&api)`, L384).
- 게스트는 진짜 `svc #0` syscall을 내지 않고 **host가 제공한 shim(`GuestHostApi`: writeStdout/exitGroup)** 을 호출하도록 **특수 빌드**돼 있어야 한다.
- 진짜 `/system/bin/linker64`는 **호출되지 않는다**(존재만 확인). 매핑은 단일 static/PIE 바이너리의 PT_LOAD뿐.
- syscall 대부분 stub: `brk`/`futex`/`set_tid_address`=가짜 0, `rt_sigaction`/`tgkill`=no-op, `clone`=ENOSYS.
- TLS(TPIDR_EL0) 격리·시그널 전달·스레드 생성 **전무**.
- **실증된 천장**: `/system/bin/avm-hello`(static PIE)가 `"hello"`를 stdout에 찍고 종료. (`StagePhaseBDiagnostics`: `libcInit && stdout=="hello"`)

→ 현 방식은 **협조적(cooperative)·계측된(instrumented) 게스트**만 실행한다. 실제 Android 바이너리(`app_process64`, `libc.so`)는 진짜 syscall을 내고 동적 링크·TLS·시그널·스레드를 요구하므로 **현 방식으로는 절대 못 돈다.**

## 2. 핵심 재정의 — 진짜 의사결정 축

"in-process vs 분리 프로세스"는 핵심이 아니다(코드는 이미 fork로 분리한다). 진짜 축은 두 가지다.

1. **unmodified 게스트의 syscall을 어떻게 서비싱하는가** — 게스트를 고쳐 shim을 부르게 할 수는 없다(클린룸 + 실제 ROM). 진짜 `svc`를 가로채야 한다.
2. **비루트 Android에서 게스트 코드를 어떻게 실행 가능하게 만드는가** — Android 10+(API 29)부터 `untrusted_app`은 **W^X**: 쓰기 가능 저장소의 파일을 `execve`/`PROT_EXEC` 할 수 없다(SELinux `execute`/`execmem` 거부). 이것이 A·B 모두를 좌우하는 **지배 제약**이다.

## 3. 옵션 정의

### Option A — Cooperative / instrumented guest (현 방식 연장)
게스트 libc의 syscall stub을 빌드/패치해 host shim을 부르게 한다.
- 장점: 트랩 불요, 단순. `avm-hello`로 이미 동작.
- 치명적 단점: **실제 Android 바이너리를 못 돈다**(우리가 만든 바이너리만). 클린룸 제품 목표(사용자 ROM의 app_process64 실행)와 **양립 불가**. → **dead end.**

### Option B — Forked child + 진짜 linker64 + seccomp `SECCOMP_RET_TRAP`(SIGSYS) syscall 서비싱
host가 fork한 child에서 진짜 게스트 `linker64`를 실행하고, child에 seccomp-bpf 필터를 깔아 게스트 syscall을 `SIGSYS`로 트랩 → host 핸들러(VFS/property/binder)로 라우팅. **execve를 피하고** 코드는 ELF 로더가 매핑(기존 memfd fallback 활용).
- 장점: unmodified 게스트 실행. child가 자기 TPIDR/시그널/스레드를 자유롭게 — **TLS/시그널/clone 문제가 프로세스 분리로 자연 해소**. seccomp는 앱이 스스로 설치 가능(Android 자체가 사용).
- 리스크: ① 비루트에서 `PROT_EXEC` 매핑/실행 가용성(W^X) ② SIGSYS 핸들러에서 syscall 재실행 비용/정확성 ③ ART의 자체 seccomp 설치와 충돌.

### Option B′ — Separate `execve` 프로세스 + ptrace(`PTRACE_SYSEMU`)
게스트를 별도 프로세스로 `execve`하고 ptrace로 syscall 에뮬레이트.
- 장점: 가장 "정통". 격리 최상.
- 리스크: **비루트 Android W^X가 `execve`를 막을 가능성 높음**(앱 데이터 파일 execute 거부). ptrace 성능(매 syscall context switch). `untrusted_app`의 ptrace SELinux 허용 범위.

### Option C — root / 중첩 가상화 / 사전 컨테이너 런타임
- 범위 밖: 제품 전제(비루트 일반 앱)와 충돌. 기록만 하고 배제.

## 4. 지배 제약과 실증 필요성

A/B/B′의 우열은 **실기기에서만 답할 수 있는** 다음 능력에 달려 있다. 데스크 분석으로는 확정 불가 → **capability probe로 실증**한다(§5).

| 능력 | 왜 중요한가 | A | B | B′ |
| --- | --- | --- | --- | --- |
| 익명 `PROT_EXEC` mmap 실행 | 게스트 코드 실행의 최소 조건 | 필요 | 필요 | — |
| `memfd_create` + 매핑 실행 | execve 회피 코드 로딩 | — | 핵심 | — |
| seccomp 필터 설치 + SIGSYS 전달 | unmodified syscall 트랩 | — | 핵심 | — |
| 자식 프로세스 ptrace | syscall 에뮬레이트 | — | — | 핵심 |
| 앱 데이터 파일 `execve`/`execveat` | 정통 분리 실행 | — | — | 핵심 |
| `clone`(thread) | ART 멀티스레드 | (후속) | 필요 | 필요 |

## 5. Capability Probe — 실증 도구

`spike/guest_exec_probe.cpp`(+ `GuestExecProbe` Kotlin + `GuestExecProbeReceiver` debug)로 **타깃 기기/에뮬레이터의 `untrusted_app` 도메인에서** 위 능력을 경험적으로 측정한다. 위험한 실행 테스트는 자식 프로세스에서 수행해 probe 자신이 죽지 않게 한다.

측정 항목(각각 boolean + errno):
- `protExecMmap`: `mmap(PROT_READ|WRITE)` → 명령어 기록 → `mprotect(PROT_EXEC)` → 호출 성공?
- `memfdExec`: `memfd_create` → write code → `mmap(PROT_EXEC)` → 호출 성공?
- `seccompTrap`: `seccomp(SECCOMP_MODE_FILTER, SECCOMP_RET_TRAP)` 설치 + SIGSYS 핸들러로 한 syscall 가로채기 성공?
- `ptraceChild`: fork child(`PTRACE_TRACEME`) + parent가 syscall-stop 관측 성공?
- `cloneThread`: `pthread_create` 동등 동작?
- `execveMemfd`: `execveat(memfd, "", AT_EMPTY_PATH)` 성공? (대개 비루트에서 거부 예상)

실행:
```sh
adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_GUEST_EXEC_PROBE \
    -n dev.jongwoo.androidvm/.debug.GuestExecProbeReceiver
adb logcat -s AVM.GuestExecProbe
# GUEST_EXEC_PROBE arch=... prot_exec_mmap=... memfd_exec=... seccomp_trap=... ptrace_child=... clone_thread=... execve_memfd=...
```

해석 규칙:
- `seccomp_trap && (memfd_exec || prot_exec_mmap)` → **Option B 가능**(권고안 성립).
- `!memfd_exec && !prot_exec_mmap` → 게스트 코드 실행 자체가 봉쇄 → 제품 범위/타깃 재정의 필요(예: 특정 OEM/ABI 한정, 또는 개발자모드 전제).
- `ptrace_child && execve_memfd` → Option B′도 가능(비교 평가).

## 6. 결정 매트릭스

| 기준 | A | B (권고 후보) | B′ |
| --- | --- | --- | --- |
| unmodified 실제 ROM 실행 | ✗ | ✓ | ✓ |
| TLS/시그널/clone | ✗ | ✓(프로세스 분리) | ✓ |
| 비루트 W^X 적합성 | n/a | △(memfd 매핑 실행 가용성에 의존) | ✗(execve 거부 가능성 높음) |
| syscall 비용 | 최저 | 중(SIGSYS) | 고(ptrace) |
| 구현 난이도 | 최저 | 상 | 상 |
| 클린룸 적합 | ✓ | ✓ | ✓ |
| 제품 목표 도달 | ✗ | ✓ | ✓ |

## 7. 잠정 권고

**Option B**(forked child + 진짜 linker64 + seccomp `SECCOMP_RET_TRAP` syscall 서비싱, execve 회피·memfd/PROT_EXEC 매핑)를 **잠정 채택**한다.
- 근거: unmodified 게스트 실행 + 프로세스 분리로 TLS/시그널/clone 자연 해소 + 비루트에서 execve 의존 회피.
- **확정 조건**: §5 probe에서 `seccomp_trap=true && (memfd_exec || prot_exec_mmap)=true`. 거짓이면 B′ 또는 범위 재정의로 분기.
- A는 배제(제품 목표와 양립 불가), C는 범위 밖.

확정 시 G-track 매핑:
- EP2: 진짜 linker64 부트스트랩 + seccomp SIGSYS dispatch + 코어 syscall 실서비싱 + memfd 코드 로딩.
- EP3/EP4: 그 위에 servicemanager/zygote/system_server/PMS → onCreate.

## 8. 리스크 레지스터 (EP1 한정)

| # | 리스크 | 영향 | 완화 |
| --- | --- | --- | --- |
| E1-1 | 비루트 W^X가 모든 게스트 코드 실행 봉쇄 | 제품 불가 | probe 우선 실행. 봉쇄 시 타깃(개발자모드/특정 OEM) 또는 아키텍처 재정의 |
| E1-2 | SIGSYS 핸들러에서 syscall 재실행 비용 과다(ART GC 루프) | UX 저하 | hot-path syscall 화이트리스트(SECCOMP_RET_ALLOW) + 핵심만 트랩 |
| E1-3 | 게스트 ART가 자체 seccomp 설치 → 우리 필터와 충돌 | 부팅 실패 | 필터 합성/계층화 전략 PoC, ART seccomp 비활성 옵션 |
| E1-4 | probe를 실기기에서 못 돌림(디바이스 부재) | 결정 지연 | 에뮬레이터(x86_64)+실기기(arm64) 2종 매트릭스, CI nightly 연계 |
| E1-5 | 권고가 틀려 B′로 재작업 | 일정 손실 | probe 결과로 조기 분기, EP2 착수 전 게이트 강제 |

## 9. On-device 검증 절차 → `spike_oncreate_reached`

1. probe 실행 → 능력 매트릭스 확정(§5).
2. B 성립 시: 최소 PoC — fork child에서 진짜 `linker64`로 `libc.so` 링크 후 `__libc_init` 진입을 SIGSYS dispatch로 관측(= onCreate 경로 *첫 관문*).
3. (확장) `app_process64 --help` 또는 미니멀 dex가 `Activity.onCreate` 진입.
4. 위 도달 시 `spike_oncreate_reached=true`.

## 10. 출구 게이트 — 현재 상태(정직)

```text
GUEST_ARCH_DECISION passed=false approach=B(provisional) spike_oncreate_reached=false risks_logged=true
```

- `risks_logged=true`: §8 완료.
- `approach=B(provisional)`: §7. probe 결과로 확정.
- `passed=false` / `spike_oncreate_reached=false`: probe가 실기기에서 미실행이고 onCreate 미도달. **canned 통과 금지 원칙**에 따라 거짓으로 표기. 실기기 실증 후 갱신.

## 11. 인용 (코드 근거)

- fork 기반 실행: `app/src/main/cpp/jni/phase_b_bridge.cpp:362–446`(`fork()` L371, `entry(&api)` L384)
- ELF 매핑(PT_LOAD만, memfd fallback): `loader/elf_loader.cpp:172–290`(memfd L244–268)
- 링커 미호출(존재만 확인): `phase_b_bridge.cpp:129–151, 504–514`; `loader/linker_bridge.cpp:22–65`(미사용)
- syscall stub: `phase_b_bridge.cpp:238–266`; `syscall/signal.cpp:13–50`; `syscall/futex.cpp`
- TLS 무처리: cpp 트리에 TPIDR/__set_tls 0건
- 천장(avm-hello): `phase_b_bridge.cpp:540`; `StagePhaseBDiagnostics.kt:59`

# 실제 사용 가능한 제품 구현 Plan (Production Implementation Plan)

> 작성일: 2026-06-02
> 기준 커밋: `0be02de Add product readiness plan` 이후 작업 트리
> 상태: **진단 게이트(Stage 1–7, Phase A–E) 완료 / 실제 제품 미완성**
> 목적: 현재 "진단/시뮬레이션 기반으로 게이트를 통과한" 코드베이스를, **사용자가 합법 ROM을 가져와 VM을 만들고, 실제 Android 게스트를 부팅해 실제 APK를 설치·실행하고, 실패에서 복구하고, 프라이버시/보안 경계를 신뢰할 수 있는** 제품으로 격상하기 위한 정밀 실행 계획.

---

## 0. 이 문서의 위치와 읽는 법

### 0.1 기존 문서와의 관계

| 문서 | 역할 | 이 문서와의 관계 |
| --- | --- | --- |
| `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` | 장기 클린룸 설계 근거 | 상위 설계 — 변경 없음 |
| `docs/planning/stage-01..07-*.md` | 초기 빌드아웃 단계 | 완료된 과거 — 참조용 |
| `docs/planning/phase-a..e-*.md` | 진단 게이트 정의 | 완료된 진단 기준 — 본 문서의 검증 출발점 |
| `docs/planning/product-readiness-plan.md` | 제품화 P0–P8 + 5개 PRODUCT 게이트 | **본 문서가 보강/구체화하는 대상** |
| **`docs/plan/production-implementation-plan.md` (본 문서)** | 실제 제품 구현 실행 계획(전략) | product-readiness-plan을 **대체하지 않고 확장** — 특히 "게스트 실제 실행" 트랙을 추가 |
| `docs/plan/production-execution-phases.md` | 본 문서의 모든 작업을 실행 Phase(EP0–EP11) + Step-by-Step으로 변환한 실행 문서 | 본 문서의 **실행 동반 문서** — "무엇을 어떤 순서로 어떻게" |

**핵심 차이**: `product-readiness-plan.md`는 게스트 런타임이 "이미 앱을 실행하고 있고 굳히기(hardening)만 남았다"는 가정에 가깝다. 그러나 §1의 정밀 진단대로 게스트는 **아직 실제 코드를 실행하지 않는다**. 따라서 본 문서는 P-track(제품 표면) 앞에 **G-track(게스트 실제 실행)** 을 명시적으로 추가한다.

### 0.2 본 문서의 구성

- §1 현재 상태 정밀 진단 (ground truth)
- §2 제품 정의 / 완료 게이트
- §3 두 트랙 마일스톤 개요와 의존 그래프
- §4 G-track 상세 (게스트 실제 실행) — **최대 난관**
- §5 P-track 상세 (제품 표면) — product-readiness P0–P8 매핑/구체화
- §6 통합 검증 하네스 (release-equivalent runner)
- §7 APK corpus 정의
- §8 리스크 레지스터
- §9 권장 실행 순서 / 규모 추정
- §10 Release Candidate 체크리스트

---

## 1. 현재 상태 정밀 진단 (Ground Truth)

> 이 절은 코드를 직접 읽어 확인한 사실만 기록한다. 제품화 의사결정의 전제가 되므로, 낙관적 해석을 배제하고 "실제로 무엇이 실행되는가"만 본다.

### 1.1 호스트 / 오케스트레이션 레이어 (Kotlin) — 상당 부분 실제 구현됨

| 영역 | 성숙도 | 근거 (대표) |
| --- | --- | --- |
| ROM 파이프라인 | 실제(부분) | `storage/RomInstaller.kt` — 스테이징·검증·health check·atomic commit 실제 구현 |
| ROM 추출 | 부분 | `storage/RomArchiveReader.kt` — zip 실제, `tar.zst`는 미구현(`Unsupported`) |
| ROM 서명 | 부분/stub | `storage/RomUpdateChannel.kt` — `StubSha256SignatureVerifier` 사용 중, `Ed25519SignatureVerifier`는 구현됐으나 **미연결** |
| APK 스테이징/설치 파이프라인(호스트측) | 실제 | `apk/ApkStager.kt`, `apk/ApkInstallPipeline.kt`, `apk/ApkInstaller.kt` — magic 검증·digest·메타데이터·dispatch 실제 |
| VM 생명주기/IPC | 실제 | `vm/VmInstanceService.kt`, `vm/VmManagerService.kt`, `vm/VmIpcContract.kt`, `vm/MultiInstanceController.kt`, `vm/VmProcessSlots.kt` |
| 부팅 preflight | 실제 | `vm/RuntimePreflightCheck.kt` — rootfs/health/config 검사 후 거부 가능 |
| 브리지 프레임워크 | 실제 | `bridge/BridgeDispatcher.kt`, `DefaultPermissionBroker.kt`, `PermissionRequestGateway.kt`, `BridgeAuditLog.kt`, `BridgePolicyStore.kt` |
| 브리지(개별): Clipboard/Location/Camera/Microphone/DeviceProfile/File | 실제(호스트측) | 각 `bridge/*Bridge.kt` — host API 연동 + 권한 게이트 |
| 브리지(개별): Network/AudioOutput/Vibration | stub | `bridge/NetworkBridge.kt`(정책 체크만), `bridge/VmVpnService.kt`(선언만), `AudioOutputBridge.kt`(`NoopAudioSink`), `bridge/VibrationBridge.kt`(`NoopHostVibrator`) |
| 스냅샷/백업 | 실제(미연결) | `storage/SnapshotManager.kt`(overlay CoW), `storage/InstanceBackup.kt`(ZIP export) — **UI 미연결** |
| 복구/진단 | 부분 | `diag/BootHealthMonitor.kt`(repair는 callback), `diag/CrashReportStore.kt`(실제) |
| UI | 부분 | `ui/MainActivity.kt`, `ui/BridgeSettingsScreen.kt` — ROM/APK/브리지 흐름 실제, **인스턴스 관리 UI 없음 / 상세 오류·복구 UX 없음** |

**요약**: 호스트 레이어는 "사용자가 ROM을 넣고 APK를 고르면, JNI 경계까지 실제로 데이터를 운반"하는 수준까지 구현돼 있다. 즉 **제품의 외피와 배선은 상당히 진짜다.**

### 1.2 네이티브 게스트 런타임 (C++) — 실행 경로는 시뮬레이션

| 서브시스템 | 성숙도 | 근거 |
| --- | --- | --- |
| ELF64 로더 (`loader/elf_loader.cpp`) | 실제(Phase B 한정) | PT_LOAD 매핑·권한·memfd fallback 실제. **단, 격리된 PoC 테스트 바이너리 전용** |
| 동적 링커 호출 (`loader/linker_bridge.cpp`) | 미연결 | 실제 `/system/bin/linker64`를 호출해 심볼/relocation을 푸는 경로 없음 |
| syscall 서비싱 (`syscall/*`) | 부분(30%) | I/O 일부는 host 패스스루. `brk`는 가짜 정적 break, `futex`/`exit_group`/`nanosleep` 등은 no-op/0 반환. 신호 전달 미구현 |
| Binder (`binder/service_manager.cpp`) | stub | 서비스 = "이름→정수 핸들" 맵. 실제 서비스 객체/트랜잭션 라우팅 없음 |
| Property service (`property/`) | 실제 | mmap property area, 게스트 libc가 읽기 가능 |
| VFS (`vfs/`) | 부분 | 경로 재작성/패스스루 일부. `fd_table.cpp`는 placeholder |
| Graphics (`device/gralloc.cpp`, `device/composer.cpp`) | stub | gralloc=ashmem 버퍼, composer=최상위 레이어 1장 복사. 실제 합성/HWC 없음 |
| **게스트 부팅 진입점** (`jni/vm_native_bridge.cpp` `phaseBGuestRuntimeEntrypoint`) | **시뮬레이션** | 실제 `init`/`zygote`/`system_server` 바이너리를 로드·실행하지 않음 |

**결정적 증거** — `jni/vm_native_bridge.cpp`의 `phaseBGuestRuntimeEntrypoint`(대략 L1258–L1309):

1. `runSyscallSmoke()`로 `/system/build.prop` 읽고 테스트 파일 쓰기(호스트 FS 패스스루)
2. `registerPhaseCSystemServices()`로 **하드코딩된 서비스 이름 목록** 등록
3. property 맵에 `init.svc.zygote=running`, `ro.zygote=zygote64`, `sys.boot_completed=1` 등을 **직접 대입**
4. `bootstrapStatus`에 canned 문자열 세팅:
   `"virtual_init=ok;property_service=ok;servicemanager=ok;zygote=main_loop;zygote_socket=accepting;system_server=boot_completed;surfaceflinger=first_frame;boot_completed=1"`
5. 가짜 로그(`"SystemServer: Entered the Android system server!"` 등) append
6. `guestProcessRunning.store(false)` — **스레드 즉시 종료**

즉 `startGuest()`는 실제 게스트를 구동하는 것이 아니라 **"부팅 완료처럼 보이는 상태 문자열"을 만든 뒤 끝난다.** 진단 프로브(`PhaseDiagnosticProbes.verifyCrossProcessState` 등)는 이 canned 상태(`virtual_init=ok`, `servicemanager=ok`)를 읽어 `passed=true`를 보고한다.

### 1.3 두 진단의 화해 (왜 "APK 실행=실제"와 "게스트=시뮬레이션"이 동시에 참인가)

- 호스트 진단(§1.1)이 말하는 "APK 설치/실행 실제"는 **JNI 경계까지의 호스트 파이프라인이 실제로 동작**한다는 의미다. `ApkInstallPipeline`은 SAF→스테이징→검증→`VmNativeBridge.importApk()`/`launchPackage()` 호출까지 진짜 수행한다.
- 네이티브 진단(§1.2)이 말하는 "게스트 시뮬레이션"은 **그 JNI 호출을 받은 native가 실제 dex를 실행하지 않는다**는 의미다. 패키지 인덱스는 갱신되지만 `Activity.onCreate`에 진입하는 실제 게스트 프로세스는 없다.

**결론(이 문서의 출발 전제):**
> 제품화 작업량의 대부분은 호스트 UI/배선이 아니라 **"게스트가 실제 Android 코드를 실행하게 만드는 것"** 에 있다. 호스트 레이어는 60–70% 제품 수준이지만, **게스트 실행 코어는 0에 가깝다(시뮬레이션)**. 따라서 본 문서는 G-track을 최우선·최대 리스크로 둔다.

---

## 2. 제품 정의와 완료 게이트

### 2.1 MVP 제품 정의 (product-readiness-plan §2.1 계승)

첫 제품 릴리스는 다음을 모두 만족한다.

- 사용자가 합법 보유한 Android 게스트 이미지를 앱으로 가져온다(외부 import 포함).
- VM 인스턴스를 만들고/시작/중지/삭제한다.
- Android 7.1.2 arm64 게스트에서 **대표 APK 10개 이상이 실제로 설치되고 launcher에서 실행**된다.
- 화면/입력/clipboard/파일 import-export/network on-off가 제품 UI에서 예측 가능하게 동작한다.
- camera/microphone은 기본 off, 사용 시점 권한 요청 + audit가 명확하다.
- crash/boot 실패/bad ROM/storage full/permission denied가 복구 가능한 메시지로 표시된다.
- release 빌드에서 debug 전용 receiver/synthetic success path가 제품 성공 조건으로 쓰이지 않는다.

### 2.2 완료 게이트 (기존 5개 유지 + 신규 1개 추가)

기존 `product-readiness-plan.md`의 5개 게이트를 그대로 유지한다.

```text
PRODUCT_RUNTIME_RESULT    passed=true boot=true install=true launch=true input=true graphics=true audio=true
PRODUCT_BRIDGE_RESULT     passed=true clipboard=true file=true network=true camera_policy=true mic_policy=true audit=true
PRODUCT_RESILIENCE_RESULT passed=true snapshot=true rollback=true crash_report=true boot_repair=true data_export=true
PRODUCT_SECURITY_RESULT   passed=true permissions=minimal update=ed25519 offline=true telemetry=off secrets=none
PRODUCT_RELEASE_RESULT    passed=true debug_surface=closed signed=true store_ready=true docs=true support=true
```

**신규 추가 — 게스트 실제 실행 게이트** (G-track의 졸업 조건이자 `PRODUCT_RUNTIME_RESULT`의 선행 증거):

```text
PRODUCT_GUEST_EXEC_RESULT passed=true init_real=true linker_real=true zygote_socket=true system_server_real=true surfaceflinger_real=true apk_oncreate_real=true synthetic_runtime=0
```

- 이 게이트의 모든 `*_real` 필드는 **canned 상태 문자열이 아니라 게스트 프로세스가 생성한 신호에서만** 판정한다.
- `synthetic_runtime=0`: `phaseBGuestRuntimeEntrypoint`류의 시뮬레이션 경로가 제품 빌드에서 0개임을 강제한다.

---

## 3. 마일스톤 개요와 의존 그래프

### 3.1 두 트랙

- **G-track (Guest Runtime / 실제 실행)**: native가 실제 Android 게스트를 부팅·실행하게 만든다. 본 문서의 신규/핵심 트랙.
- **P-track (Product Surface / 제품 표면)**: product-readiness-plan의 P0–P8을 구체화한다. 호스트/UX/보안/릴리스.

### 3.2 마일스톤 목록

| ID | 이름 | 트랙 | product-readiness 매핑 | 비고 |
| --- | --- | --- | --- | --- |
| **M0** | Truth & Verification Harness | P | P0 + P1 + P8(부분) | 모든 작업의 측정 기반. 최우선 |
| **G1** | Real Guest Execution Core | G | (신규) | init/linker/syscall 실제 실행. **최대 난관** |
| **G2** | System Services Boot | G | P2(부분) | zygote/system_server/SurfaceFlinger 실제 부팅 |
| **G3** | Real APK Launch | G | P2 | dex 실행, `Activity.onCreate` 실제 진입 |
| **M4** | Graphics / Input / Media | P | P3 | composer 실제화, 입력/오디오 |
| **M5** | Bridge / Privacy 완성 | P | P4 | network/audio/vibration 실제화 |
| **M6** | Storage / Snapshot / Data Safety | P | P5 | 스냅샷/백업 UI 연결, 원자성 |
| **M7** | Security / Updates | P | P6 | Ed25519 연결, offline-only |
| **M8** | Product UX | P | P7 | onboarding/instance grid/recovery |
| **M9** | Release Engineering | P | P8 | signing/nightly/support |

### 3.3 의존 그래프

```
M0 (하네스) ──┬──────────────────────────────────────────────┐
              │                                                │
              ▼                                                ▼
            G1 ──► G2 ──► G3 ──┬──► M4 (graphics/media) ──┐    M7 (security)
                               │                          │    M6 (data safety)
                               └──► M5 (bridge 실제화) ────┤
                                                          ▼
                                                    M8 (UX) ──► M9 (release) ──► RC
```

- **M0는 다른 모든 작업의 측정 도구**다. G-track 진척을 "canned가 아닌 실제"로 판정하려면 M0의 release-equivalent runner가 먼저 있어야 한다.
- **G1→G2→G3는 엄격한 선형 의존**이다. 링커 없이는 zygote가 못 뜨고, zygote 없이는 APK가 못 뜬다.
- M4/M5는 G3 이후 의미를 가진다(실제 화면/오디오/입력이 게스트에서 나와야 측정 가능).
- M6/M7은 G-track과 비교적 독립적이라 병렬 진행 가능.

### 3.4 전략적 분기점 (G-track 시작 전 반드시 결정)

G1을 시작하기 전에 **게스트 실행 아키텍처**를 확정해야 한다. 이 결정이 전체 일정·리스크를 좌우한다.

| 옵션 | 설명 | 장점 | 단점/리스크 |
| --- | --- | --- | --- |
| **A. In-process 실제 ELF 실행** (현재 골격의 연장) | 호스트 프로세스 안에서 게스트 ELF를 매핑하고, 게스트 `linker64`/`libc`를 그대로 실행, syscall을 가로채 호스트로 위임 | 클린룸 원칙 유지, 커널 불요 | host/guest libc·TLS·시그널·seccomp 충돌이 치명적. ART/dex2oat 안정화 난이도 매우 높음. **연구성 리스크** |
| **B. 프로세스 분리 + ptrace/seccomp-bpf 게이트웨이** | 게스트를 별도 프로세스로 fork/exec하고 syscall을 trap하여 VFS/property/binder로 라우팅 | TLS/시그널 충돌 회피, 격리 우수 | ptrace 성능/배터리, Android 비루트 환경의 seccomp 제약, 구현 복잡 |
| **C. 기존 사용자공간 컨테이너 기법 차용** | 잘 알려진 user-space Android 실행 패턴(네임스페이스/바인드마운트 유사 구조) 적용 | 검증된 경로 | 비루트 Android에서 가용 범위 제한, 클린룸 경계 재검토 필요 |

> **권고**: M0 직후 **2주 spike**로 옵션 A vs B를 PoC 비교(가장 단순한 `app_process64 --help` 또는 미니멀 dex 하나가 `Activity.onCreate`까지 도달하는지)하여 G1 착수 전에 의사결정 게이트를 통과한다. 본 문서의 G1–G3 task는 옵션 A/B 어느 쪽이든 공통으로 필요한 항목 위주로 기술하되, 분기 지점을 명시한다.

```text
GUEST_ARCH_DECISION passed=true approach={A|B|C} spike_oncreate_reached=true risks_logged=true
```

---

## 4. G-track 상세 — 게스트 실제 실행

> 이 트랙이 "실제 사용 가능한 제품"의 90%를 좌우한다. 각 마일스톤은 (목표 / 선행 / 작업 task / 대상 파일 / 검증 게이트 / 리스크)로 기술한다.

### G1 — Real Guest Execution Core

**목표**: 게스트의 실제 동적 링커(`linker64`)가 실제 ELF(`app_process64` 또는 최소 정적 PoC)를 로드·재배치·실행하고, 그 실행이 호스트로 위임된 syscall로 진짜 부수효과(파일/메모리/시계)를 일으킨다. 더 이상 canned 상태 없음.

**선행**: M0 완료, §3.4 `GUEST_ARCH_DECISION` 통과.

**작업 task**

- G1.1 — `phaseBGuestRuntimeEntrypoint`의 시뮬레이션 경로 제거/격리. 제품 빌드에서 canned `bootstrapStatus` 세팅 금지. (`jni/vm_native_bridge.cpp`)
- G1.2 — 실제 링커 부트스트랩: 게스트 `linker64`를 ELF 로더로 매핑하고 aux vector(`AT_PHDR/AT_PHENT/AT_PHNUM/AT_ENTRY/AT_RANDOM/AT_PAGESZ/AT_SYSINFO_EHDR` 등)를 정확히 구성해 링커 진입점으로 점프. (`loader/elf_loader.cpp`, `loader/aux_vector.cpp`, `loader/linker_bridge.cpp`)
- G1.3 — syscall dispatch 테이블을 "스모크용 패스스루"에서 "실제 서비싱"으로 확장. 최소 셋: `openat/read/write/close/lseek/mmap/mprotect/munmap/brk/mremap/futex/clock_gettime/gettimeofday/rt_sigaction/rt_sigprocmask/tgkill/set_tid_address/prctl/getrandom/nanosleep/clone(thread)`. 각각 호스트 위임 또는 정확한 가상화. (`syscall/*`)
- G1.4 — `brk`/heap 실제화(현재 가짜 정적 break 제거), `mmap` 익명/파일 매핑 정확화. (`syscall/mem.cpp`)
- G1.5 — TLS/시그널 경계 처리: host/guest TLS 충돌 회피 전략 구현(옵션 A면 TLS 슬롯 분리/세이브-복원, 옵션 B면 프로세스 분리로 자연 해소). `rt_sigaction` 실제 등록·전달. (`syscall/signal.*`)
- G1.6 — VFS 경로 재작성 실측: 게스트가 보는 `/system`, `/vendor`, `/data`, `/dev`, `/proc/self/*`가 rootfs/instance 경로로 정확히 매핑. `fd_table.cpp` placeholder 실구현. (`vfs/path_resolver.cpp`, `vfs/fd_table.cpp`)
- G1.7 — `/proc`·`/sys` 최소 가상화(게스트 libc/linker가 요구하는 항목: `/proc/self/maps`, `/proc/self/exe`, `/proc/cpuinfo`, `/sys/devices/system/cpu/*` 등).
- G1.8 — 스레드 생성(`clone`) 지원: zygote/ART가 스레드를 만들 수 있어야 함. `process.cpp`의 "tid==pid 단일 스레드" 가정 제거.

**검증 게이트**

```text
G1_RESULT passed=true linker_real=true reloc_applied=true syscalls_real=true heap_real=true tls_safe=true vfs_mapped=true thread_create=true synthetic=0
```

- 합격 증거: 게스트 `linker64`가 최소 PoC 바이너리를 로드해 `main`까지 도달하고, 게스트가 쓴 파일이 instance `/data`에 실제로 나타나며, `getrandom`/`clock_gettime`이 실제 값을 반환한다. canned 문자열로는 절대 통과 불가.

**리스크**: 최상(연구성). host/guest libc·TLS·seccomp 충돌이 가장 큰 위험. 비루트 Android에서 `mmap(PROT_EXEC)`/`memfd` 정책 변화 대응 필요. → §3.4 spike로 조기 검증.

---

### G2 — System Services Boot

**목표**: 실제 `init`(또는 미니멀 init 대체) → `servicemanager` → `zygote64` → `system_server` → `SurfaceFlinger`가 **실제 프로세스/스레드로 부팅**되고, binder 트랜잭션이 실제로 오간다.

**선행**: G1 완료.

**작업 task**

- G2.1 — 실제 binder 트랜잭션 라우팅: 현재 "이름→핸들" 맵을 넘어 실제 parcel in/out, `BR_TRANSACTION`/`BC_REPLY` 흐름, strong/weak ref 처리. (`binder/transaction.cpp`, `binder/service_manager.cpp`, `binder/binder_device.cpp`)
- G2.2 — `/dev/binder` 가상 디바이스가 게스트 libbinder의 ioctl(`BINDER_WRITE_READ`, `BINDER_SET_MAX_THREADS` 등)을 실제 처리.
- G2.3 — `servicemanager` 실제 부팅: 게스트 servicemanager 바이너리가 binder를 통해 자신을 컨텍스트 매니저로 등록(`BINDER_SET_CONTEXT_MGR`)하고 add/get service를 실제 처리.
- G2.4 — `zygote64` 실제 기동: `/dev/socket/zygote` 유닉스 도메인 소켓을 실제로 listen하고, fork 요청을 받아 앱 프로세스를 spawn. 현재 `zygoteAccepting=true` 불리언 제거. (`jni/vm_native_bridge.cpp`, socket 경로)
- G2.5 — `system_server` 실제 기동: AMS/PMS/WMS 등 핵심 시스템 서비스가 실제 객체로 등록되고 binder로 응답. (게스트 framework 코드가 도는 것이 목표 — 우리가 다시 구현하는 것이 아님)
- G2.6 — `SurfaceFlinger` 실제 기동 + gralloc/composer 연결: SF가 게스트 gralloc로 버퍼를 할당하고 composer로 present. (`device/gralloc.cpp`, `device/composer.cpp`) — 단, 합성 품질은 M4에서 productize.
- G2.7 — 부팅 마커는 **게스트 출처 신호로만** 판정(예: 게스트 logcat 파이프/property가 게스트 프로세스에 의해 set). 호스트가 직접 set하는 부팅 마커 전면 금지.

**검증 게이트**

```text
G2_RESULT passed=true servicemanager_real=true binder_tx_real=true zygote_socket_listen=true system_server_real=true surfaceflinger_first_frame=true boot_completed_guest_origin=true synthetic=0
```

**리스크**: 높음. ART 초기화, dex2oat/quicken, framework가 요구하는 binder 트랜잭션 커버리지 폭이 큼. → "지원 안 되는 트랜잭션은 crash 대신 typed failure 반환"(P2 항목) 원칙 적용.

---

### G3 — Real APK Launch

**목표**: 사용자가 import한 일반 APK가 **실제 PMS로 설치(dexopt 포함)** 되고, launcher에서 아이콘을 눌러 **실제 `Activity.onCreate`** 에 진입하며, 화면에 자기 UI를 그린다.

**선행**: G2 완료.

**작업 task**

- G3.1 — PMS 실제 설치 경로: 호스트 `ApkInstaller`/`PmsInstallCoordinator`가 게스트 PMS binder 트랜잭션으로 설치를 트리거하고, `pm list packages`에 실제로 노출. synthetic fallback 제거. (`apk/PmsInstallCoordinator.kt`, `apk/PackageOperations.kt` ↔ 게스트 PMS)
- G3.2 — dexopt/실행 경로 검증: ART가 dex를 quicken/AOT 또는 interpret로 실행. 불안정 시 `--compiler-filter=quicken`/dex2oat 비활성 옵션을 제품 설정으로 노출(Phase D 비목표 허용 범위).
- G3.3 — launcher 부팅: Launcher3 또는 미니멀 launcher가 SF 위에 떠서 설치 패키지를 나열. (`apk/GuestActivityManager.kt` ↔ 게스트 AMS)
- G3.4 — 앱 실행 dispatch: 호스트 `launchPackage()`가 게스트 AMS `startActivity` 트랜잭션으로 이어져 실제 프로세스가 zygote에서 fork되고 `Activity.onCreate` 진입.
- G3.5 — 입력 라우팅 1차: touch/back/home/recent가 게스트 InputFlinger까지 전달(품질은 M4).
- G3.6 — 안정성: 1개 대표 앱 기준 start→interact→stop 100회 반복에서 crash 0.

**검증 게이트**

```text
G3_RESULT passed=true pms_install_real=true dexopt_ok=true launcher_real=true app_oncreate_real=true input_to_guest=true loop100_crashes=0 synthetic_runtime=0
```

- **이 게이트 통과 = §2.2 `PRODUCT_GUEST_EXEC_RESULT`의 실질 충족.** 제품화의 분수령.

**리스크**: 높음. 앱별 호환성 편차. → §7 APK corpus로 범위 고정, 실패 taxonomy 작성.

---

## 5. P-track 상세 — 제품 표면

> product-readiness-plan.md의 P0–P8을 그대로 계승하되, §1 진단에 맞춰 "무엇이 이미 있고 무엇이 비었는지"를 구체화한다. 체크리스트 원문은 product-readiness-plan §3–§11 참조.

### M0 — Truth & Verification Harness (P0 + P1 + P8 부분) — **최우선**

**목표**: 진척을 "canned가 아닌 실제"로 측정할 수 있는 release-equivalent 자동 검증 기반 확보. 이것 없이는 G-track의 "실제" 주장을 검증할 수 없다.

**작업 task**

- M0.1 — `product`(또는 `qa`) build variant 추가. debug receiver 미포함, 그러나 product gate runner 포함. (`app/build.gradle.kts`)
- M0.2 — debug-only receiver와 product gate runner를 코드/매니페스트 수준에서 분리. `ProductReleaseSurfaceGuardTest` 확장으로 회귀 방지.
- M0.3 — `ProductReadinessDiagnostics`의 5개 probe를 **실제 on-device 측정**에 연결(현재 기본 false fail-closed 골격). (`vm/ProductReadinessDiagnostics.kt`)
- M0.4 — ROM import → VM boot → APK 설치 → launch까지의 end-to-end on-device 테스트 러너. 실패 시 logcat/tombstone/guest log/instance state를 번들로 수집.
- M0.5 — CI 분리: JVM fast gate(기존) + nightly device/product gate(신규, 에뮬레이터/디바이스).
- M0.6 — 문서: debug receiver gate와 release product gate의 차이, "제품으로 인정되는 on-device 시나리오" 목록 고정. (P0 잔여 체크리스트)

**검증 게이트** (product-readiness-plan과 동일)

```text
PRODUCT_P0_DOC_TRUTH    passed=true stale_docs=0 product_plan_linked=true release_status_clear=true
PRODUCT_P1_VERIFICATION passed=true devices>=2 apk_corpus>=10 release_equivalent=true artifacts=collected
```

**리스크**: 중. 에뮬레이터에서 arm64 게스트 가용성(중첩 가상화/호스트 ABI) 확인 필요.

---

### M4 — Graphics / Input / Media (P3)

**선행**: G3.
**목표**: 화면/입력/오디오/카메라/마이크가 제품 품질.

**작업 task**

- M4.1 — composer/gralloc stub 경계를 실제 BufferQueue 계약으로 좁힘. (`device/composer.cpp`, `device/gralloc.cpp`)
- M4.2 — 소프트웨어 프레임버퍼 프레임 페이싱 측정, p50 ≥ 24fps 보장. orientation/density/resize/multi-window 검증.
- M4.3 — touch/keyboard/back/home/recent 입력을 게스트 생명주기와 정합, p95 입력 지연 ≤ 80ms.
- M4.4 — GLES/Virgl/Venus를 제품 UI에서 "supported/unsupported/experimental"로 명시(미지원 호스트 graceful degrade). (Phase E.5–E.7 계승)
- M4.5 — 오디오 출력 underrun/xrun 카운터를 제품 진단에 노출. `AudioOutputBridge`의 `NoopAudioSink`를 실제 AAudio sink로 교체.
- M4.6 — 마이크를 `AudioRecord` 프로덕션 소스에 연결, 카메라를 CameraX 프로덕션 소스에 연결. `FixedPcmSource`/`FixedCameraSource`는 test-only(이미 debug 소스셋 분리됨 — release 0 보장 회귀 테스트 유지).

**검증 게이트**

```text
PRODUCT_P3_MEDIA passed=true fps_p50>=24 input_latency_ms_p95<=80 audio_xruns=0 fixed_sources_release=0
```

---

### M5 — Bridge / Privacy 완성 (P4)

**선행**: G3(브리지가 실제 게스트와 통신해야 의미).
**목표**: 프라이버시/권한 경계를 제품 안전 기준으로 완성.

**작업 task**

- M5.1 — **NetworkBridge 실제화**: `VmVpnService`를 실제 VpnService로 구현(가상 인터페이스 egress 분리, host NAT/disabled/VPN-isolated 모드, 실제 소켓 경로, DNS proxy 선택). 현재 정책 체크 stub 대체. (`bridge/NetworkBridge.kt`, `bridge/VmVpnService.kt`)
- M5.2 — **VibrationBridge 실제화**: `NoopHostVibrator` → 실제 host Vibrator.
- M5.3 — AudioOutput 실제화는 M4.5와 공유.
- M5.4 — camera/mic/location은 사용 시점 권한 요청만 허용. off/unsupported 경로가 host API를 호출하지 않음을 **release gate에서 검증**.
- M5.5 — 인스턴스별 audit log 보존 + 사용자 export/delete UI(M8 연계).
- M5.6 — File bridge SAF import/export, MIME, size, path traversal 방어 검증.
- M5.7 — DeviceProfile은 synthetic 신원만 반환, host 식별자 노출 0 검증.
- M5.8 — forbidden permission guard를 release manifest에 적용(`ManifestPermissionGuardTest` 유지/확장).

**검증 게이트**

```text
PRODUCT_P4_PRIVACY passed=true host_permission_on_use=true audit_export=true forbidden_permissions=0 host_id_leaks=0
```

---

### M6 — Storage / Snapshot / Data Safety (P5)

**선행**: G2(rootfs overlay가 실제 부팅에 쓰여야 검증 의미).
**목표**: 사용자 데이터 무손실 + 복구.

**작업 task**

- M6.1 — base/overlay/snapshot 레이아웃 마이그레이션을 실제 설치 base로 검증. (`storage/SnapshotManager.kt`, `LayeredRootfsPaths`)
- M6.2 — VM running/stopped 상태별 snapshot create/rollback/delete 정의, 전원 손실/앱 kill에 대한 원자성 검증.
- M6.3 — `InstanceBackup` export/import를 제품 UI에 연결(현재 미연결).
- M6.4 — storage 압박 하에서 install/boot/snapshot 실패 메시지 검증.
- M6.5 — corrupt manifest/rootfs/runtime-state repair 흐름을 제품 UI에 연결(`BootHealthMonitor.repairAction` 배선).
- M6.6 — canonical path 테스트를 release gate에 추가: 데이터 삭제가 절대 instance root를 벗어나지 않음(path escape 0).

**검증 게이트**

```text
PRODUCT_P5_DATA passed=true snapshot_atomic=true backup_restore=true corrupt_repair=true path_escape=0
```

---

### M7 — Security / Updates (P6)

**선행**: M0(부분). G-track과 비교적 독립 → 병렬 가능.
**목표**: 클린룸 원칙 유지하며 ROM/업데이트/보안 경계 완성.

**작업 task**

- M7.1 — 제품 경로에서 `StubSha256SignatureVerifier` 제거. (`storage/RomUpdateChannel.kt`)
- M7.2 — `Ed25519SignatureVerifier`를 실제 offline manifest import 흐름에 연결(서명 검증 후에만 rootfs commit).
- M7.3 — `RomArchiveReader`의 `tar.zst` 추출 실구현(native zstd/tar). (`storage/RomArchiveReader.kt`)
- M7.4 — 외부 ROM import 경로 추가: 현재 `RomInstaller.bundledCandidates()` 전용 → SAF/file picker로 사용자 ROM 가져오기. (`storage/RomInstaller.kt`, `ui/MainActivity.kt`)
- M7.5 — update manifest schema versioning + rollback 정책.
- M7.6 — release gate에서 검증: network fetch/background polling/telemetry/silent auto-update 0. (offline-only, Phase E.9 계승)
- M7.7 — 3rd-party/proprietary 바이너리 번들 인벤토리 감사, license/OSS attribution/clean-room provenance 문서.
- M7.8 — crash report local-only 기본, 명시적 opt-in 없이는 외부 전송 금지.

**검증 게이트**

```text
PRODUCT_P6_SECURITY passed=true ed25519=true telemetry=off bundled_proprietary=0 license_docs=true
```

---

### M8 — Product UX (P7)

**선행**: G3, M4–M7(기능이 있어야 UX로 노출).
**목표**: 비개발자도 VM을 만들고 관리할 수 있는 앱 경험.

**작업 task**

- M8.1 — 첫 실행 onboarding: ROM 준비/권한 설명/저장공간 안내.
- M8.2 — 인스턴스 그리드: create/start/stop/delete/snapshot/backup 액션(**현재 없음** — `ui/MainActivity.kt`는 단일 인스턴스 컨트롤 위주).
- M8.3 — VM 디스플레이 화면: 상태/부팅 진행/오류 복구/입력 컨트롤.
- M8.4 — APK import 흐름: 설치 진행/실패 사유/launcher 단축.
- M8.5 — 브리지 설정: 모드 설명/audit 히스토리/인스턴스별 정책(기존 `BridgeSettingsScreen` 확장).
- M8.6 — 진단 화면: health/logs/storage/FPS/memory/bridge activity.
- M8.7 — 사용자용 오류 taxonomy(복구 가능 메시지).
- M8.8 — 접근성/dynamic type/가로세로 레이아웃 검증.

**검증 게이트**

```text
PRODUCT_P7_UX passed=true onboarding=true recovery=true diagnostics=true accessibility=true
```

---

### M9 — Release Engineering (P8)

**선행**: 전 마일스톤.
**목표**: 반복 가능한 배포/회귀 방어/지원 체계.

**작업 task**

- M9.1 — Java 17 toolchain 고정(완료: `kotlin { jvmToolchain(17) }`). 유지.
- M9.2 — canonical release gate 유지: `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease`.
- M9.3 — nightly device/product gate 추가(M0.5와 통합).
- M9.4 — release signing/versioning/changelog/artifact retention.
- M9.5 — release APK에 debug receiver/debug asset/test-only fixed source 미포함 검증.
- M9.6 — crash/log 번들 redaction.
- M9.7 — beta rollout 체크리스트 + rollback plan.
- M9.8 — support 템플릿: ROM import/boot/APK install/bridge permission 이슈.

**검증 게이트**

```text
PRODUCT_P8_RELEASE passed=true jdk=17 release_signed=true debug_surface=0 nightly_green=true rollback_plan=true
```

---

## 6. 통합 검증 하네스 (Release-Equivalent Runner)

- 최종 판정은 **release(또는 product) variant APK**를 실기기/release-equivalent 에뮬레이터에 올려 5개 PRODUCT 게이트 + `PRODUCT_GUEST_EXEC_RESULT`를 모두 `passed=true`로 만드는 것.
- 모든 `*_real`/`synthetic_runtime=0` 필드는 **게스트 출처 신호로만** 판정. 호스트가 set한 canned 상태는 자동 실패 처리.
- 실패 시 아티팩트 번들(logcat/tombstone/guest log/instance state/스크린샷) 자동 수집.
- runner는 `ProductReadinessDiagnostics`(probe 실연결, M0.3)를 통해 한 번에 5+1 라인을 emit.

```text
PRODUCT_GUEST_EXEC_RESULT passed=true ...
PRODUCT_RUNTIME_RESULT    passed=true ...
PRODUCT_BRIDGE_RESULT     passed=true ...
PRODUCT_RESILIENCE_RESULT passed=true ...
PRODUCT_SECURITY_RESULT   passed=true ...
PRODUCT_RELEASE_RESULT    passed=true ...
```

---

## 7. 대표 APK Corpus (제품 합격 ≥ 90%)

product-readiness-plan §4 계승. 카테고리별 최소 10종, 실패 taxonomy 작성.

1. 네이티브 미사용 단순 앱
2. WebView 앱
3. 파일 picker 사용 앱
4. 오디오 출력 앱
5. 네트워크 앱
6. 클립보드 앱
7. 카메라 권한 요청 앱
8. 마이크 권한 요청 앱
9. 백그라운드 서비스 앱
10. 대용량 APK 설치 stress 앱

판정: corpus install/launch 성공률 ≥ 0.9, 8시간 idle soak + 2시간 foreground soak에서 crash 0.

```text
PRODUCT_P2_RUNTIME passed=true corpus_launch_rate>=0.9 soak_hours>=8 crashes=0 synthetic_runtime=0
```

---

## 8. 리스크 레지스터

| # | 리스크 | 영향 | 완화 |
| --- | --- | --- | --- |
| R1 | **게스트 실제 실행이 연구성 난제**(host/guest libc·TLS·seccomp 충돌) | G-track 좌초 시 제품 불가 | §3.4 spike로 아키텍처 조기 결정, G1을 가장 먼저·작게 검증 |
| R2 | 진단 게이트와 실제 제품 동작의 괴리(false confidence) | 잘못된 완료 판단 | M0 release-equivalent runner 최우선, `synthetic_runtime=0` 강제 |
| R3 | 비루트 Android의 `mmap(PROT_EXEC)`/`memfd`/seccomp 정책 변화 | 실행 경로 차단 | memfd fallback 유지, OS 버전별 가용성 매트릭스 |
| R4 | corpus 외 실제 APK 호환성 저조 | 제품 retention 저조 | corpus 카테고리 확장 + 실패 taxonomy |
| R5 | GPU 가속 호스트별 편차 | UX 분산 | capability matrix 노출, software fallback 안정화 |
| R6 | camera/mic 프라이버시 회귀 | 신뢰 치명 | off-path host API 호출 금지 게이트 + audit export(M5) |
| R7 | ROM/업데이트 법적 리스크 | 배포 불가 | clean-room provenance, 사용자 제공 ROM, offline signed import만 |
| R8 | snapshot/데이터 손실 | 사용자 데이터 손실 | M6 원자성/backup-restore/corrupt repair를 release blocker로 |
| R9 | dexopt/ART 불안정 | 앱 실행 불가/충돌 | quicken/dex2oat 비활성 옵션, tombstone triage(P2) |

---

## 9. 권장 실행 순서 / 규모 추정

### 9.1 순서

```
1) M0 (하네스)                          ← 측정 기반, 최우선
2) §3.4 GUEST_ARCH_DECISION spike       ← 2주, A/B PoC 비교
3) G1 → G2 → G3                          ← 선형, 최대 난관
   (병렬) M7(security), M6(data) 일부     ← G-track과 독립적 부분
4) M4 (graphics/media), M5 (bridge)      ← G3 이후
5) M8 (UX)
6) M9 (release) → Release Candidate
```

### 9.2 규모/난이도 (정성적, 캘린더 아님)

| 마일스톤 | 난이도 | 리스크 | 비고 |
| --- | --- | --- | --- |
| M0 | 중 | 중 | 명확, 기존 골격 확장 |
| G1 | **최상** | **최상** | 연구성. 전체 일정의 지배 변수 |
| G2 | 상 | 상 | binder/ART 커버리지 |
| G3 | 상 | 상 | 앱 호환성 |
| M4 | 중 | 중 | 측정·튜닝 |
| M5 | 중 | 중 | VpnService 실제화가 핵심 |
| M6 | 중 | 중 | 코드 상당수 존재, 연결/검증 위주 |
| M7 | 중하 | 중 | Ed25519 구현 존재, 연결 위주 |
| M8 | 중 | 하 | UI 작업량 |
| M9 | 하 | 하 | 엔지니어링 정형화 |

> **정직한 경고**: 호스트 레이어는 제품의 60–70%처럼 보이지만, 전체 난이도의 대부분은 **G1**에 집중돼 있다. G1의 spike 결과에 따라 전체 제품 실현 가능성과 일정이 결정되므로, **다른 어떤 P-track 작업보다 G1 spike를 먼저** 수행할 것을 강하게 권고한다.

---

## 10. Release Candidate 체크리스트

RC는 다음을 **모두** 만족할 때만 생성한다. (product-readiness-plan §15 + 본 문서 신규 항목)

- [ ] `PRODUCT_GUEST_EXEC_RESULT passed=true` (신규, 게스트 출처 신호로만 판정)
- [ ] 5개 `PRODUCT_*_RESULT passed=true` 전부 통과
- [ ] 대표 APK corpus install/launch 성공률 ≥ 90%
- [ ] 8시간 idle soak + 2시간 foreground soak, crash 0
- [ ] release APK에 debug receiver/debug fixture/test-only fixed source 0
- [ ] forbidden permission 0
- [ ] telemetry/background network update 0
- [ ] ROM update 서명 검증이 Ed25519 product path 사용
- [ ] 사용자 문서: ROM import / instance lifecycle / bridge permission / backup-restore / troubleshooting
- [ ] beta rollback plan + issue triage 템플릿 준비

---

## 부록 A. 본 문서가 추가/변경하는 게이트 라인 요약

| 게이트 | 출처 | 상태 |
| --- | --- | --- |
| `GUEST_ARCH_DECISION` | 본 문서 §3.4 | 신규 |
| `G1_RESULT` / `G2_RESULT` / `G3_RESULT` | 본 문서 §4 | 신규 |
| `PRODUCT_GUEST_EXEC_RESULT` | 본 문서 §2.2 | 신규 (RC blocker) |
| `PRODUCT_P0..P8` 및 5개 `PRODUCT_*_RESULT` | product-readiness-plan.md | 계승 |

## 부록 B. 진단에서 인용한 핵심 코드 위치

- 시뮬레이션 부팅 진입점: `app/src/main/cpp/jni/vm_native_bridge.cpp` `phaseBGuestRuntimeEntrypoint` (canned `bootstrapStatus`, 스레드 즉시 종료)
- ELF 로더(실제, PoC 한정): `app/src/main/cpp/loader/elf_loader.cpp`
- syscall stub: `app/src/main/cpp/syscall/mem.cpp`(가짜 brk), `syscall/*`(futex/exit_group no-op)
- binder stub: `app/src/main/cpp/binder/service_manager.cpp`
- graphics stub: `app/src/main/cpp/device/gralloc.cpp`, `device/composer.cpp`
- ROM 서명 stub/미연결: `app/src/main/java/dev/jongwoo/androidvm/storage/RomUpdateChannel.kt`
- ROM 외부 import 부재: `storage/RomInstaller.kt` (`bundledCandidates()` 전용)
- network/audio/vibration stub: `bridge/NetworkBridge.kt`, `bridge/VmVpnService.kt`, `bridge/AudioOutputBridge.kt`, `bridge/VibrationBridge.kt`
- 스냅샷/백업(실제, UI 미연결): `storage/SnapshotManager.kt`, `storage/InstanceBackup.kt`
- product 게이트 골격(probe 미연결): `app/src/main/java/dev/jongwoo/androidvm/vm/ProductReadinessDiagnostics.kt`

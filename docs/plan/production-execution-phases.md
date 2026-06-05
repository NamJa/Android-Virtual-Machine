# 실제 제품 구현 — 실행 Phase 체계 (Step-by-Step)

> 작성일: 2026-06-02 · **갱신: 2026-06-05** (진행 현황 + 잔여 재정립 — §1.5)
> 상위 문서: [`production-implementation-plan.md`](./production-implementation-plan.md)
> 관련: [`guest-rom-acquisition-strategy.md`](./guest-rom-acquisition-strategy.md) · [`vm-boot-integration-design.md`](./vm-boot-integration-design.md)
> 목적: 상위 plan의 **모든 작업**(M0 / GUEST_ARCH_DECISION / G1–G3 / M4–M9 / 통합 검증)을 **실행 Phase(Execution Phase, EP)** 단위로 변환하고, 각 Phase를 순서가 있는 Step-by-Step으로 상세화한다. 이 문서는 "무엇을 어떤 순서로 어떻게 하고 무엇으로 완료를 판정하는가"를 다룬다.

---

## 0. 네이밍과 읽는 법

이 레포에는 이미 두 종류의 "단계"가 있다. 혼동을 막기 위해 본 문서의 실행 단위는 **EP(Execution Phase)** 로 부른다.

| 체계 | 의미 | 출처 |
| --- | --- | --- |
| Phase A–E | 진단/회귀 게이트 (완료됨) | `docs/planning/phase-*.md` |
| P0–P8 | product-readiness 제품화 섹션 | `docs/planning/product-readiness-plan.md` |
| M0/G1–G3/M4–M9 | 상위 plan의 마일스톤 | `docs/plan/production-implementation-plan.md` |
| **EP0–EP11** | **본 문서의 실행 단위 (Step 포함)** | 본 문서 |

### Step 표기 규약

- 각 Step은 `EP{n}.{m}` 로 번호를 매긴다. 세부 행동은 a/b/c… 로 쪼갠다.
- 각 Step은 다음 4요소를 가진다:
  - **작업**: 무엇을 하는가 (행동)
  - **대상**: 건드리는 파일/모듈
  - **검증**: 어떻게 확인하는가 (테스트/브로드캐스트/runner/logcat)
  - **DoD(Definition of Done)**: 이 Step이 끝났다고 인정하는 조건
- 각 Phase 끝에는 **Phase Exit Gate**(게이트 라인)와 **리스크**를 둔다.
- 게이트의 `*_real` / `synthetic*=0` 필드는 **게스트 출처 신호로만** 판정한다(호스트가 set한 canned 상태는 자동 실패).

---

## 1. EP 전체 개요와 의존 관계

> 현황(2026-06-05): ✅완료 🟡부분 ⬜미착수 — 상세는 §1.5.

| EP | 이름 | 현황 | 매핑 | 트랙 | 선행 | 병렬 가능 |
| --- | --- | --- | --- | --- | --- | --- |
| **EP0** | Verification Harness & Truth Reset | ✅ | M0 (P0+P1+P8부분) | P | — | — |
| **EP1** | Guest Architecture Decision (Spike) | ✅ | GUEST_ARCH_DECISION | G | EP0 | — |
| **EP2** | Real Guest Execution Core | 🟡 | G1 | G | EP1 | EP8·EP7 일부 |
| **EP3** | System Services Boot | ⬜ | G2 | G | EP2 | EP8·EP7 일부 |
| **EP4** | Real APK Launch | ⬜ | G3 | G | EP3 | — |
| **EP5** | Graphics / Input / Media | ⬜ | M4 (P3) | P | EP4 | EP6 |
| **EP6** | Bridge / Privacy 완성 | ⬜ | M5 (P4) | P | EP4 | EP5 |
| **EP7** | Storage / Snapshot / Data Safety | ⬜ | M6 (P5) | P | EP3 | EP2~EP4와 병렬 |
| **EP8** | Security / Updates | 🟡 | M7 (P6) | P | EP0 | EP2~EP4와 병렬 |
| **EP9** | Product UX | ⬜ | M8 (P7) | P | EP4~EP8 | — |
| **EP10** | Release Engineering | ⬜ | M9 (P8) | P | EP9 | — |
| **EP11** | Integration & Release Candidate | ⬜ | 통합 검증 + RC | — | EP1~EP10 | — |

```
EP0 ──► EP1 ──► EP2 ──► EP3 ──► EP4 ──┬──► EP5 ─┐
                  │       │            └──► EP6 ─┤
                  │       └─────────────────────┤
                  └──(병렬)──► EP7, EP8 ─────────┤
                                                 ▼
                                          EP9 ──► EP10 ──► EP11(RC)
```

> **핵심**: EP0(측정 기반)와 EP1(아키텍처 결정)이 모든 것의 전제다. 난이도는 EP2에 집중된다. EP7/EP8은 G-track과 독립적이므로 인력이 있으면 병렬로 진행한다.

---

## 1.5 진행 현황 및 잔여 재정립 (2026-06-05)

> 범례: ✅ 완료 · 🟡 부분/메커니즘 실증(제품 게이트 미충족) · ⬜ 미착수. 모든 실증은 에뮬레이터(arm64 API 29/36) + JVM 테스트 기준이며, 실기기/ROM 의존 항목은 별도 표기.

### 1.5.1 EP별 현황

| EP | 상태 | 근거(커밋/검증) |
| --- | --- | --- |
| EP0 하네스/진실성 | ✅ | product variant·`ProductGateRunner`(fail-closed)·`ProductE2eRunner`·`FailureBundle`·CI fast+nightly·`ep0-gate-semantics` (`53fa7d1`) |
| EP1 아키텍처 결정 | ✅ | **Option B 확정** — probe `option_b_viable=true` (API 29/35) (`ed0bd57`, `df8ccc2`) |
| EP2 게스트 실행 코어 | 🟡 메커니즘 전부 실증 / **G1 미완** | 2.1✅ 2.2✅ 2.3✅ 2.4✅ 2.5✅ 2.6✅ 2.7🟡 2.8✅ (`2ca5dda`,`1d1a0b1`,`13d3663`,`661c5ae`,`ade2074`) — 단 **클린룸 ROM 실부팅 통합 미완** |
| EP8 보안/업데이트 | 🟡 부분 | 8.2✅(Ed25519 게이트, API<33 후속) 8.3✅(tar.zst + NDK libzstd, on-device) (`9fcb7d4`,`d63e7f9`,`b544489`) / 8.1·8.4·8.5·8.6·8.7·8.8 ⬜ |
| EP3 시스템 부팅 | ⬜ | 게이트웨이 위에서 시작(EP2 통합 후) |
| EP4 APK 실행 | ⬜ | |
| EP5 그래픽/입력/미디어 | ⬜ | (브리지 일부는 EP6에서 실제화) |
| EP6 브리지/프라이버시 | ⬜ | |
| EP7 데이터 안전 | ⬜ | (호스트 측 — G-track과 병렬 가능) |
| EP9 UX / EP10 릴리스 / EP11 통합 | ⬜ | |

### 1.5.2 세션 중 추가된(원 plan 외) 산출물 — 모두 **G1 선결/접합부**

| 산출물 | 역할 | 커밋 |
| --- | --- | --- |
| `RootfsHealthCheck.bootReady` | 실부팅 전제(linker64/libc/app_process64가 arm64 ELF) 신호 | `8ce1df5` |
| `tools/build_aosp_guest_rom.sh` | 클린룸 AOSP arm64 ROM → tar.zst+manifest 빌더 | `03eac86` |
| `GuestBootPolicy` + startRuntime 배선 | bootReady+flag 기반 boot-mode 게이트(on-device 실증) | `a97561f` |
| `guest/{syscall_gateway,guest_boot}` + `bootGuestViaLinker` | spike→production 승격(REAL 부팅 코어) | `ade2074` |
| `guest-rom-acquisition-strategy.md`, `vm-boot-integration-design.md` | ROM 확보 + 실부팅 통합 설계 | — |

### 1.5.3 잔여 재정립 — G1(`passed=true`)까지의 임계 경로

EP2의 "메커니즘"은 모두 실증됐다. G1의 잔여는 **메커니즘이 아니라 "클린룸 ROM이 end-to-end로 실제 부팅"** 이다. 이를 닫는 임계 경로:

1. **(선결) 클린룸 AOSP arm64 ROM 생성** — `build_aosp_guest_rom.sh`로 빌드(도구 준비 완료) → `bootReady=true`. *유일하게 도구 밖 인프라(Docker AOSP 빌드)가 필요한 항목.*
2. **EP2.9 (신규) — REAL 부팅 wiring** (아래 EP2에 추가): `initInstance` config에 `bootMode` 전달 → native가 REAL이면 `phaseBGuestRuntimeEntrypoint`(시뮬레이션) 대신 `bootGuestViaLinker(rootfs, .../linker64, GatewayMode::VFS, …)` 호출 → `synthetic=0`, 게스트 출처 마커. **코어(`bootGuestViaLinker`)는 승격 완료, native 분기 + JNI 계약만 잔여.**
3. **`REAL_GUEST_BOOT_ENABLED=true`** (`GuestBootPolicy`) — flag 1개.
4. EP2.7(/proc·/sys 폭) + 서비싱 폭(binder ioctl/property/socket)은 **EP3와 함께 점증** — G1 최소 부팅엔 linker→libc→app_process 도달이면 충분, 전체 서비싱은 EP3/4에서 확장.

→ 위 1–3이 G1을 닫고, 그 위에서 **EP3(시스템 서비스 부팅) → EP4(APK 실행)** 가 선형으로 이어진다.

### 1.5.4 권장 다음 순서

- **G-track**: EP2.9 wiring(flag off, 구조 완성) → 클린룸 ROM 생성 → flag on + 실부팅 실증(**G1**) → EP3 → EP4.
- **병렬(호스트 측, ROM 무관)**: EP8 잔여(8.4 외부 ROM import / 8.1 stub 제거 / 8.6 offline-only 검증) + EP7(데이터 안전) — 인력 있으면 G-track과 동시 진행.
- EP5/EP6/EP9는 EP4(실제 APK 실행) 이후 의미가 생기므로 그 전까지는 설계/스캐폴딩만.

---

## EP0 — Verification Harness & Truth Reset ✅ (완료)

**Phase 목표**: 진척을 "canned가 아닌 실제"로 측정할 수 있는 release-equivalent 검증 기반을 갖추고, 문서/상태의 진실성을 정렬한다. 이후 모든 Phase의 합격 판정 도구.

**선행**: 없음. **산출물**: `product` variant, on-device E2E runner, nightly CI, 진실성 문서.

### Steps

- **EP0.1 — product/qa build variant 추가**
  - 작업: debug 전용 receiver를 제외하되 product gate runner는 포함하는 `product`(또는 `qa`) variant 정의. release와 동일 최적화/축소 설정.
  - 대상: `app/build.gradle.kts`, `app/src/product/`(신규 소스셋), `app/src/product/AndroidManifest.xml`.
  - 검증: `:app:assembleProduct` 성공, 산출 APK에 `Stage*`/`StagePhase*`/`*DiagnosticsReceiver` 미포함.
  - DoD: product variant가 빌드되고, 매니페스트에 debug receiver 0.

- **EP0.2 — debug receiver와 product gate runner 분리**
  - 작업: 진단 브로드캐스트 receiver(debug 전용)와 제품 게이트 실행기를 코드/매니페스트에서 분리. release/product 표면에서 receiver 차단.
  - 대상: `app/src/debug/...`, `vm/ProductReadinessDiagnostics.kt`, 신규 `vm/ProductGateRunner.kt`.
  - 검증: `ProductReleaseSurfaceGuardTest` 확장 — product variant 매니페스트/소스셋 대상 추가.
  - DoD: 가드 테스트가 product variant까지 커버하고 green.

- **EP0.3 — ProductReadinessDiagnostics probe 실연결**
  - 작업: 현재 기본 `false`(fail-closed) probe 5종을 실제 on-device 측정에 연결하는 인터페이스 정의 및 product runner 배선.
  - 대상: `vm/ProductReadinessDiagnostics.kt`(probe 주입 지점), `vm/ProductGateRunner.kt`.
  - 검증: runner가 5(+1)개 `PRODUCT_*_RESULT` 라인을 실제 측정값으로 emit.
  - DoD: 실측 probe가 연결되고, 미구현 항목은 `passed=false`로 정직하게 보고.

- **EP0.4 — On-device E2E 러너(스켈레톤)**
  - 작업: ROM import → VM boot → APK install → launch 흐름을 기기/에뮬레이터에서 자동 실행하는 러너. 이 시점에는 게스트가 시뮬레이션이므로 대부분 `false`가 정상.
  - 대상: 신규 `app/src/androidTest/...ProductE2eRunner` 또는 product variant 내 instrumented 진입점.
  - 검증: 러너 실행 시 단계별 결과와 아티팩트(logcat/guest log/instance state) 수집.
  - DoD: 러너가 end-to-end로 돌고 실패 지점을 아티팩트와 함께 보고.

- **EP0.5 — 실패 아티팩트 번들러**
  - 작업: 실패 시 logcat, tombstone, guest log, instance state(JSON), 스크린샷을 한 ZIP으로 수집.
  - 대상: 신규 `diag/FailureBundleCollector.kt`, `diag/CrashReportStore.kt` 연계.
  - 검증: 임의 실패 주입 시 번들 ZIP 생성 및 매니페스트(sha256) 포함.
  - DoD: 번들이 생성되고 민감정보 미포함(redaction 적용).

- **EP0.6 — CI 분리(fast JVM + nightly device)**
  - 작업: 기존 JVM fast gate 유지, nightly device/product gate 잡 추가.
  - 대상: `.github/workflows/ci.yml`(또는 nightly 워크플로 신규).
  - 검증: PR은 fast gate, nightly는 device gate 실행.
  - DoD: 두 워크플로가 각각 green.

- **EP0.7 — 진실성 문서/시나리오 고정 (P0 잔여)**
  - 작업: debug receiver gate와 release product gate의 차이를 문서화. "제품으로 인정되는 on-device 시나리오" 목록 고정. Phase A–E 문서의 "잔여 Step" 표를 완료/제품화 잔여로 분리.
  - 대상: `docs/planning/phase-*.md`, `docs/plan/production-implementation-plan.md`, README.
  - 검증: stale 문서 0(상호 참조 일치).
  - DoD: `PRODUCT_P0_DOC_TRUTH` 충족.

**Phase Exit Gate**

```text
PRODUCT_P0_DOC_TRUTH    passed=true stale_docs=0 product_plan_linked=true release_status_clear=true
PRODUCT_P1_VERIFICATION passed=true devices>=2 apk_corpus>=10 release_equivalent=true artifacts=collected
```
> 주: 이 시점 `PRODUCT_P1_VERIFICATION`의 `apk_corpus`/실측 결과는 게스트가 아직 시뮬레이션이라 통과 못 할 수 있다. EP0 졸업 기준은 **러너/하네스/variant가 동작**하는 것이며, corpus 실측 통과는 EP4 이후 재평가한다. (하네스 완비 = EP0 done)

**리스크**: 에뮬레이터의 arm64 게스트 가용성(중첩 가상화/호스트 ABI). → 디바이스 1대 + 에뮬레이터 1대 매트릭스 확보.

---

## EP1 — Guest Architecture Decision (Spike) ✅ (Option B 확정)

> 현황: probe가 API 29/35 arm64 에뮬레이터에서 `option_b_viable=true`(seccomp_trap + memfd/PROT_EXEC) → **Option B 채택**. `spike_oncreate_reached`는 EP2.2 부트스트랩(실제 linker가 app_process를 AndroidRuntime까지)으로 충족. 물리 기기 재확인은 권장 잔여.

**Phase 목표**: G-track 착수 전, 게스트 실행 아키텍처를 PoC로 비교·결정한다. 이 결정이 전체 일정·리스크를 좌우하는 단일 변수.

**선행**: EP0. **산출물**: 아키텍처 결정문서 + PoC 결과 + 리스크 로그.

### Steps

- **EP1.1 — 옵션 A PoC: In-process 실제 ELF 실행**
  - 작업: 호스트 프로세스 내에서 게스트 `linker64`로 최소 PoC 바이너리(또는 `app_process64 --help`)를 실제 로드·재배치·실행. host/guest libc·TLS 충돌 발생 여부 기록.
  - 대상: `loader/elf_loader.cpp`, `loader/linker_bridge.cpp`, `loader/aux_vector.cpp`(임시 브랜치).
  - 검증: 게스트 코드가 실제 `main`에 도달하고 호스트로 위임된 syscall이 실제 부수효과를 냄.
  - DoD: A의 도달 한계와 충돌 지점이 문서화됨.

- **EP1.2 — 옵션 B PoC: 프로세스 분리 + ptrace/seccomp 게이트웨이**
  - 작업: 게스트를 별도 프로세스로 fork/exec하고 syscall을 trap해 VFS/property/binder로 라우팅. 비루트 Android의 ptrace/seccomp 제약·성능 측정.
  - 대상: 신규 spike 모듈(`cpp/spike/ptrace_gateway.cpp`).
  - 검증: 미니멀 게스트가 trap된 syscall로 파일/시계 접근.
  - DoD: B의 도달 한계와 성능/배터리 비용이 문서화됨.

- **EP1.3 — (선택) 옵션 C 평가**
  - 작업: user-space 컨테이너 기법의 비루트 가용 범위·클린룸 경계 적합성을 데스크 리서치로 평가.
  - 대상: 문서.
  - DoD: C 채택 여부 근거 기록.

- **EP1.4 — 의사결정 및 기준선 확정**
  - 작업: A/B/C 비교표를 근거로 접근법 확정. G2–G4의 구현 분기를 본 문서에 반영.
  - 대상: `docs/plan/production-implementation-plan.md` §3.4, 본 문서.
  - DoD: 접근법 1개 확정, 미채택안의 폐기 사유 기록.

**Phase Exit Gate**

```text
GUEST_ARCH_DECISION passed=true approach={A|B|C} spike_oncreate_reached=true risks_logged=true
```
> `spike_oncreate_reached`는 spike 수준에서 최소 dex 하나가 `Activity.onCreate` 경로의 *첫 관문*(예: linker→libc_init)까지 갔는지를 의미한다. 완전한 onCreate는 EP4 목표.

**리스크**: 최상. A의 TLS/seccomp 충돌, B의 비루트 ptrace 제약. → spike 결과가 부정적이면 범위/목표 재정의(예: 지원 게스트 축소)를 EP1에서 결정.

---

## EP2 — Real Guest Execution Core (G1) 🟡 (메커니즘 실증 / 통합 잔여)

> 현황: EP2.1~2.8의 **메커니즘이 모두 에뮬레이터에서 실증**됐다(2.7만 부분). 다만 G1의 정의는 "클린룸 ROM end-to-end 실부팅"이며, 그 통합(**EP2.9**)과 ROM 확보가 잔여 → `G1_RESULT passed=false` 유지. 검증/제약은 [`ep2-guest-core-design.md`](./ep2-guest-core-design.md)·[`vm-boot-integration-design.md`](./vm-boot-integration-design.md).

**Phase 목표**: 실제 동적 링커가 실제 ELF를 로드·재배치·실행하고, 호스트로 위임된 syscall이 진짜 부수효과를 낸다. **canned 상태 전면 제거.** 최대 난관.

**선행**: EP1 결정. **산출물**: 실제 실행되는 게스트 코어.

> 세부 진행: 2.1✅(시뮬레이션 라벨 격리) 2.2✅(실제 linker 부트스트랩, API29/36) 2.3✅(seccomp SIGSYS 게이트웨이) 2.4✅(실제 mmap) 2.5✅(TLS=프로세스 분리) 2.6✅(VFS openat IP-allow 서비싱) 2.7🟡(/proc 라우팅+self/exe) 2.8✅(clone ALLOW). 코어는 `guest/{syscall_gateway,guest_boot}`로 승격(`bootGuestViaLinker`).

### Steps

- **EP2.1 — 시뮬레이션 경로 제거/격리**
  - 작업: `phaseBGuestRuntimeEntrypoint`의 canned `bootstrapStatus`/property 직접 대입/가짜 로그를 제품·product 빌드에서 제거. debug 전용으로만 남기거나 삭제.
  - 대상: `jni/vm_native_bridge.cpp`.
  - 검증: product 빌드에서 canned 부팅 문자열 grep 0. `synthetic_runtime=0` 측정.
  - DoD: 제품 경로에 시뮬레이션 부팅 0.

- **EP2.2 — 실제 링커 부트스트랩**
  - 작업: 게스트 `linker64`를 ELF 로더로 매핑하고 aux vector를 정확히 구성(`AT_PHDR/AT_PHENT/AT_PHNUM/AT_ENTRY/AT_BASE/AT_RANDOM/AT_PAGESZ/AT_SYSINFO_EHDR/AT_SECURE/AT_HWCAP/AT_HWCAP2`)해 링커 진입점으로 점프.
  - 대상: `loader/elf_loader.cpp`, `loader/aux_vector.cpp`, `loader/linker_bridge.cpp`.
  - 검증: 링커가 의존 라이브러리(`libc.so` 등)를 매핑하고 relocation 적용 후 실행 흐름이 게스트 entry로 전달.
  - DoD: 링커가 PoC 바이너리를 실제 링크해 실행.

- **EP2.3 — syscall dispatch 실제 서비싱(코어 셋)**
  - 작업: 스모크 패스스루를 실제 서비싱으로 확장. 코어: `openat/read/write/close/lseek/pread64/pwrite64/mmap/mprotect/munmap/mremap/madvise/brk/futex/clock_gettime/gettimeofday/rt_sigaction/rt_sigprocmask/rt_sigreturn/tgkill/set_tid_address/prctl/getrandom/nanosleep/readlinkat/faccessat/fstatat/getdents64`.
  - 대상: `syscall/io.cpp`, `syscall/mem.cpp`, `syscall/signal.cpp`, `syscall/process.cpp`, `syscall/time.cpp` 등.
  - 검증: 각 syscall 단위 검증 + 게스트가 실제 파일을 쓰면 instance `/data`에 반영.
  - DoD: 코어 셋 전부 실제 동작(또는 정확한 가상화), no-op stub 제거.

- **EP2.4 — heap/mmap 실제화**
  - 작업: 가짜 정적 `brk` 제거, 실제 heap 영역 관리. 익명/파일 `mmap`·`mremap` 정확화.
  - 대상: `syscall/mem.cpp`.
  - 검증: 게스트 malloc/free 반복에서 메모리 정합, 누수/충돌 없음.
  - DoD: 실제 heap로 게스트 libc 할당이 동작.

- **EP2.5 — TLS/시그널 경계 처리**
  - 작업: host/guest TLS 충돌 회피(옵션 A: TLS 슬롯 분리·save/restore / 옵션 B: 프로세스 분리로 해소). `rt_sigaction` 실제 등록·전달, 시그널 스택 처리.
  - 대상: `syscall/signal.cpp`, TLS 관리부.
  - 검증: 게스트가 시그널 핸들러 등록·수신, 멀티스레드에서 TLS 정합.
  - DoD: TLS/시그널 충돌 0(soak에서 재확인).

- **EP2.6 — VFS 경로 재작성 실측 + fd_table 실구현**
  - 작업: `/system`,`/vendor`,`/data`,`/dev`,`/proc/self/*`가 rootfs/instance 경로로 정확 매핑. `fd_table.cpp` placeholder 실구현.
  - 대상: `vfs/path_resolver.cpp`, `vfs/fd_table.cpp`.
  - 검증: 게스트가 보는 경로와 실제 호스트 경로 매핑 단위 테스트.
  - DoD: 경로 escape 0, fd 테이블 정상.

- **EP2.7 — /proc·/sys 최소 가상화**
  - 작업: linker/libc가 요구하는 `/proc/self/maps`,`/proc/self/exe`,`/proc/cpuinfo`,`/sys/devices/system/cpu/*` 등 제공.
  - 대상: `vfs/`(proc 가상화 모듈 신규).
  - 검증: 게스트가 해당 경로 읽기 시 일관된 값.
  - DoD: linker/libc 초기화가 요구하는 proc 항목 충족.

- **EP2.8 — 스레드 생성(clone) 지원** ✅
  - 작업: `clone`(thread variant) 지원, "tid==pid 단일 스레드" 가정 제거.
  - 대상: `syscall/process.cpp`.
  - 검증: 게스트가 pthread 생성·조인. (probe `clone_thread=true` + 정책상 ALLOW)
  - DoD: 멀티스레드 게스트 코드 동작.

- **EP2.9 — REAL 부팅 wiring (신규, G1 마무리)** ⬜
  - 작업: 시뮬레이션 `phaseBGuestRuntimeEntrypoint`를 boot-mode 분기로 대체 — REAL이면 `bootGuestViaLinker(rootfs, rootfs+"/system/bin/app_process64", rootfs+"/system/bin/linker64", GatewayMode::VFS, …)` 호출. `initInstance` config JSON에 `bootMode`(SIMULATED|REAL) 추가, native가 파싱·분기. flag(`REAL_GUEST_BOOT_ENABLED`)는 ROM+wiring 완비 후 on.
  - 대상: `jni/vm_native_bridge.cpp`, `vm/VmConfig.kt`(bootMode 직렬화), `vm/VmInstanceService.kt`(이미 `GuestBootPolicy.select` 배선), `guest/guest_boot.*`(코어 준비됨).
  - 선결: 클린룸 ROM(`bootReady=true`, `build_aosp_guest_rom.sh`).
  - 검증: bootReady ROM + flag on에서 게스트 출처 부팅 마커 도달(`GuestBootStatus.isRealGuestBoot()`), `synthetic=0`.
  - DoD: 실제 게스트가 linker→libc→app_process까지 실부팅(게스트 출처).

**Phase Exit Gate**

```text
G1_RESULT passed=true linker_real=true reloc_applied=true syscalls_real=true heap_real=true tls_safe=true vfs_mapped=true thread_create=true synthetic=0
```
> 현재: `passed=false` — 메커니즘(2.1~2.8)은 실증됐으나 EP2.9(REAL 부팅 wiring) + 클린룸 ROM이 잔여. flag를 켜 bootReady ROM이 게스트 출처 마커에 도달할 때 `passed=true`.

**리스크**: 최상(연구성). → EP2.5/EP2.3을 가장 먼저 작은 PoC로 굳히고, 비루트 정책(memfd/PROT_EXEC) 매트릭스 유지. EP2.9는 코어가 승격돼 리스크 하향, 단 ROM 확보가 외부 인프라 의존.

---

## EP3 — System Services Boot (G2)

**Phase 목표**: 실제 `init`(또는 미니멀 대체) → `servicemanager` → `zygote64` → `system_server` → `SurfaceFlinger`가 실제 프로세스/스레드로 부팅되고 binder 트랜잭션이 실제로 오간다.

**선행**: EP2. **산출물**: 실제 부팅되는 게스트 시스템.

### Steps

- **EP3.1 — binder 트랜잭션 실제 라우팅**
  - 작업: "이름→핸들" 맵을 넘어 실제 parcel in/out, `BR_TRANSACTION`/`BC_REPLY`, strong/weak ref, death notification 처리.
  - 대상: `binder/transaction.cpp`, `binder/service_manager.cpp`, `binder/parcel.cpp`.
  - 검증: 두 게스트 컴포넌트 간 실제 트랜잭션 왕복.
  - DoD: binder 트랜잭션 실제 동작(stub 응답 제거).

- **EP3.2 — /dev/binder ioctl 실제 처리**
  - 작업: `BINDER_WRITE_READ`,`BINDER_SET_MAX_THREADS`,`BINDER_SET_CONTEXT_MGR`,`BINDER_VERSION` 등 게스트 libbinder ioctl 처리.
  - 대상: `binder/binder_device.cpp`.
  - 검증: 게스트 libbinder가 디바이스와 정상 핸드셰이크.
  - DoD: libbinder ioctl 경로 충족.

- **EP3.3 — servicemanager 실제 부팅**
  - 작업: 게스트 servicemanager가 컨텍스트 매니저로 등록하고 add/get service를 binder로 실제 처리.
  - 대상: 게스트 바이너리 실행 + `binder/service_manager.cpp` 연동.
  - 검증: add 후 get으로 동일 서비스 핸들 회수.
  - DoD: servicemanager 게스트 출처로 동작.

- **EP3.4 — zygote64 실제 기동(소켓 listen)**
  - 작업: `/dev/socket/zygote` 유닉스 도메인 소켓을 실제 listen, fork 요청으로 앱 프로세스 spawn. `zygoteAccepting` 불리언 제거.
  - 대상: `jni/vm_native_bridge.cpp`, socket 모듈, zygote 게스트 실행.
  - 검증: 외부에서 소켓 연결 시 실제 accept + fork.
  - DoD: zygote 소켓이 실제 listen/accept.

- **EP3.5 — system_server 실제 기동**
  - 작업: 게스트 framework의 AMS/PMS/WMS 등 핵심 서비스가 실제 객체로 등록·응답하도록 부팅(우리가 재구현하는 것이 아니라 게스트 코드가 동작).
  - 대상: 게스트 부팅 시퀀스, binder 커버리지.
  - 검증: `service list`에 핵심 서비스가 게스트 출처로 등장.
  - DoD: system_server가 실제로 핵심 서비스 게시.

- **EP3.6 — SurfaceFlinger + gralloc/composer 연결**
  - 작업: SF가 게스트 gralloc로 버퍼 할당, composer로 present. (품질은 EP5)
  - 대상: `device/gralloc.cpp`, `device/composer.cpp`, SF 게스트 실행.
  - 검증: 첫 프레임이 호스트 `ANativeWindow`에 실제 출력.
  - DoD: SurfaceFlinger first frame이 게스트 출처.

- **EP3.7 — 부팅 마커를 게스트 출처로만 판정**
  - 작업: 호스트가 직접 set하던 부팅 property/로그 마커 제거. 게스트 logcat/property를 게스트가 set한 경우만 인정.
  - 대상: `jni/vm_native_bridge.cpp`, 부팅 진단 probe.
  - 검증: 마커가 게스트 프로세스 출력에서 파생됨을 확인.
  - DoD: `boot_completed_guest_origin=true`, synthetic 0.

- **EP3.8 — 미지원 트랜잭션 typed failure(crash 방지)**
  - 작업: 지원 안 되는 binder 트랜잭션은 crash 대신 typed failure 반환. API level별 binder/binderfs 경로 검증.
  - 대상: `binder/*`.
  - 검증: 미지원 호출 주입 시 graceful 실패.
  - DoD: 미지원 호출로 인한 crash 0.

**Phase Exit Gate**

```text
G2_RESULT passed=true servicemanager_real=true binder_tx_real=true zygote_socket_listen=true system_server_real=true surfaceflinger_first_frame=true boot_completed_guest_origin=true synthetic=0
```

**리스크**: 상. ART 초기화·binder 커버리지. → EP3.8 typed failure 원칙으로 부분 미지원을 흡수.

---

## EP4 — Real APK Launch (G3)

**Phase 목표**: 사용자 import APK가 실제 PMS로 설치(dexopt 포함)되고 launcher에서 실제 `Activity.onCreate`에 진입해 자기 UI를 그린다. **제품화의 분수령.**

**선행**: EP3. **산출물**: 실제 APK 실행.

### Steps

- **EP4.1 — PMS 실제 설치 경로**
  - 작업: 호스트 `ApkInstaller`/`PmsInstallCoordinator`가 게스트 PMS binder로 설치를 트리거, `pm list packages`에 실제 노출. synthetic fallback 제거.
  - 대상: `apk/PmsInstallCoordinator.kt`, `apk/PackageOperations.kt` ↔ 게스트 PMS.
  - 검증: 설치 후 게스트 `pm list packages`에 등장.
  - DoD: 실제 설치, synthetic 0.

- **EP4.2 — dexopt/실행 경로**
  - 작업: ART가 dex를 quicken/AOT/interpret로 실행. 불안정 시 `--compiler-filter=quicken`/dex2oat 비활성 옵션을 제품 설정으로 노출.
  - 대상: 게스트 ART 연동, `vm/VmConfig.kt`(옵션), `ui/`(설정 노출).
  - 검증: 설치 패키지의 dex가 실제 실행.
  - DoD: 대표 앱 dex 실행 성공.

- **EP4.3 — launcher 부팅**
  - 작업: Launcher3 또는 미니멀 launcher가 SF 위에 떠 설치 패키지 나열.
  - 대상: `apk/GuestActivityManager.kt` ↔ 게스트 AMS.
  - 검증: launcher에 설치 앱 아이콘 표시.
  - DoD: launcher 실제 부팅·나열.

- **EP4.4 — 앱 실행 dispatch → onCreate**
  - 작업: 호스트 `launchPackage()`가 게스트 AMS `startActivity`로 이어져 zygote fork → `Activity.onCreate` 진입.
  - 대상: `vm/VmNativeBridge.launchPackage`, 게스트 AMS.
  - 검증: 대표 앱이 onCreate 진입 후 화면 렌더.
  - DoD: `app_oncreate_real=true`.

- **EP4.5 — 입력 라우팅 1차**
  - 작업: touch/back/home/recent를 게스트 InputFlinger까지 전달(품질 튜닝은 EP5).
  - 대상: `device/input_device.cpp`, 입력 큐.
  - 검증: 탭/뒤로가기가 게스트 앱에 반영.
  - DoD: 기본 입력 게스트 도달.

- **EP4.6 — 안정성 루프**
  - 작업: 대표 앱 start→interact→stop 100회 반복.
  - 대상: E2E 러너(EP0.4) 시나리오.
  - 검증: 100회 crash 0.
  - DoD: `loop100_crashes=0`.

**Phase Exit Gate**

```text
G3_RESULT passed=true pms_install_real=true dexopt_ok=true launcher_real=true app_oncreate_real=true input_to_guest=true loop100_crashes=0 synthetic_runtime=0
PRODUCT_GUEST_EXEC_RESULT passed=true init_real=true linker_real=true zygote_socket=true system_server_real=true surfaceflinger_real=true apk_oncreate_real=true synthetic_runtime=0
```

**리스크**: 상. 앱별 호환성. → EP11의 corpus로 범위 고정 + 실패 taxonomy.

---

## EP5 — Graphics / Input / Media (M4 / P3)

**Phase 목표**: 화면/입력/오디오/카메라/마이크 제품 품질화.

**선행**: EP4. **병렬**: EP6.

### Steps

- **EP5.1 — composer/gralloc 실제 BufferQueue 계약화** — 대상 `device/composer.cpp`,`device/gralloc.cpp`. 검증: 다중 레이어 합성 정확. DoD: stub 경계 제거.
- **EP5.2 — 프레임 페이싱 측정/보장** — p50 ≥ 24fps. 검증: 프레임 타임 히스토그램. DoD: `fps_p50>=24`.
- **EP5.3 — orientation/density/resize/multi-window 검증** — 대상 SF/WMS 연동. DoD: 4상태 정상.
- **EP5.4 — 입력 정합/지연** — touch/keyboard/back/home/recent를 게스트 생명주기와 정합, p95 ≤ 80ms. DoD: `input_latency_ms_p95<=80`.
- **EP5.5 — GLES/Virgl/Venus 능력 표기** — 제품 UI에 supported/unsupported/experimental, 미지원 graceful degrade. DoD: capability matrix 노출.
- **EP5.6 — 오디오 출력 실제화** — `AudioOutputBridge`의 `NoopAudioSink` → 실제 AAudio sink, xrun 카운터 노출. 대상 `bridge/AudioOutputBridge.kt`, `device/`(audio). DoD: `audio_xruns=0`(정상 부하).
- **EP5.7 — 마이크/카메라 프로덕션 소스** — `AudioRecord`/CameraX 연결, `FixedPcmSource`/`FixedCameraSource`는 test-only 유지(release 0 회귀). 대상 `bridge/MicrophoneBridge.kt`,`bridge/CameraBridge.kt`. DoD: `fixed_sources_release=0`.

**Phase Exit Gate**

```text
PRODUCT_P3_MEDIA passed=true fps_p50>=24 input_latency_ms_p95<=80 audio_xruns=0 fixed_sources_release=0
```

---

## EP6 — Bridge / Privacy 완성 (M5 / P4)

**Phase 목표**: 프라이버시/권한 경계 제품 안전 기준 완성, stub 브리지 실제화.

**선행**: EP4. **병렬**: EP5.

### Steps

- **EP6.1 — NetworkBridge/VmVpnService 실제화** — 가상 인터페이스 egress 분리, host NAT/disabled/VPN-isolated 모드, 실제 소켓 경로, DNS proxy(선택). 대상 `bridge/NetworkBridge.kt`,`bridge/VmVpnService.kt`. 검증: 모드별 실제 트래픽 경로. DoD: 3모드 실제 동작.
- **EP6.2 — VibrationBridge 실제화** — `NoopHostVibrator` → 실제 Vibrator. 대상 `bridge/VibrationBridge.kt`. DoD: 게스트 진동 요청이 host에 반영.
- **EP6.3 — on-use 권한만 허용 + off-path host API 미호출 검증** — camera/mic/location은 사용 시점에만 권한 요청. off/unsupported가 host API를 호출하지 않음을 release gate에서 검증. 대상 `bridge/DefaultPermissionBroker.kt`,`PermissionRequestGateway.kt`. DoD: off-path host call 0.
- **EP6.4 — audit export/delete** — 인스턴스별 audit 보존 + 사용자 export/delete(UI는 EP9). 대상 `bridge/BridgeAuditLog.kt`. DoD: `audit_export=true`.
- **EP6.5 — File bridge 방어 검증** — SAF import/export, MIME, size, path traversal 방어. 대상 `bridge/FileBridge.kt`. DoD: traversal/escape 0.
- **EP6.6 — DeviceProfile host 식별자 0** — synthetic 신원만 반환. 대상 `bridge/DeviceProfileBridge.kt`. DoD: `host_id_leaks=0`.
- **EP6.7 — forbidden permission guard(release manifest)** — `ManifestPermissionGuardTest` 유지/확장. DoD: `forbidden_permissions=0`.

**Phase Exit Gate**

```text
PRODUCT_P4_PRIVACY passed=true host_permission_on_use=true audit_export=true forbidden_permissions=0 host_id_leaks=0
```

---

## EP7 — Storage / Snapshot / Data Safety (M6 / P5)

**Phase 목표**: 사용자 데이터 무손실 + 복구. **EP2~EP4와 병렬 가능.**

**선행**: EP3(overlay가 실제 부팅에 쓰이면 검증 강화).

### Steps

- **EP7.1 — base/overlay/snapshot 레이아웃 마이그레이션** — 실제 설치 base로 검증. 대상 `storage/SnapshotManager.kt`, `LayeredRootfsPaths`. DoD: 마이그레이션 무손실.
- **EP7.2 — snapshot create/rollback/delete 상태별 정의** — VM running/stopped 별. 대상 `storage/SnapshotManager.kt`. DoD: 상태별 동작 정의·검증.
- **EP7.3 — 원자성(전원손실/앱kill)** — 중단 주입 테스트. DoD: `snapshot_atomic=true`.
- **EP7.4 — backup/export/import UI 연결** — `InstanceBackup` ZIP을 제품 UI에 연결(UI는 EP9 연계). 대상 `storage/InstanceBackup.kt`. DoD: `backup_restore=true`.
- **EP7.5 — storage 압박 실패 메시지** — install/boot/snapshot 실패를 복구 가능 메시지로. DoD: 압박 시 graceful.
- **EP7.6 — corrupt repair 흐름 UI 연결** — manifest/rootfs/runtime-state repair를 `BootHealthMonitor.repairAction`에 배선. 대상 `diag/BootHealthMonitor.kt`. DoD: `corrupt_repair=true`.
- **EP7.7 — canonical path 가드(release gate)** — 데이터 삭제가 instance root를 절대 벗어나지 않음. 대상 신규 테스트. DoD: `path_escape=0`.

**Phase Exit Gate**

```text
PRODUCT_P5_DATA passed=true snapshot_atomic=true backup_restore=true corrupt_repair=true path_escape=0
```

---

## EP8 — Security / Updates (M7 / P6) 🟡 (부분 — 8.2/8.3 완료)

**Phase 목표**: 클린룸 원칙 유지하며 ROM/업데이트/보안 경계 완성. **EP2~EP4와 병렬 가능.**

**선행**: EP0.

> 현황: 8.2·8.3 완료. ROM 확보 트랙(`RootfsHealthCheck.bootReady`, `build_aosp_guest_rom.sh`)도 이 영역에서 파생됨 — [`guest-rom-acquisition-strategy.md`](./guest-rom-acquisition-strategy.md) 참조. 8.1·8.4·8.5·8.6·8.7·8.8은 호스트 측이라 G-track과 병렬 진행 권장.

### Steps

- **EP8.1 — StubSha256SignatureVerifier 제품 경로 제거** ⬜ — 대상 `storage/RomUpdateChannel.kt`. DoD: 제품 경로에 stub 0. (Stub은 현재 import 게이트에서 미사용 — `RomSignaturePolicy`가 Ed25519만 anchor; 코드 잔존 제거 잔여.)
- **EP8.2 — Ed25519 실제 연결** ✅ — `RomSignaturePolicy`를 `RomInstaller.install()`에 배선(서명 검증 후에만 commit, 미서명 dev 허용, 서명+anchor 없으면 fail-closed). 대상 `storage/RomUpdateChannel.kt`,`RomInstaller.kt`. ⚠️ API<33 번들 Ed25519 후속. (`9fcb7d4`)
- **EP8.3 — tar.zst 추출 실구현** ✅ — commons-compress tar(symlink·권한·traversal) + **NDK libzstd** on-device. 대상 `storage/RomArchiveReader.kt`,`Zstd.kt`,`jni/zstd_bridge.cpp`. (`d63e7f9`,`b544489`)
- **EP8.4 — 외부 ROM import 경로** ⬜ — `bundledCandidates()` 전용 → SAF/file picker로 사용자 ROM import + `RomSignaturePolicy.ed25519Import`. 대상 `storage/RomInstaller.kt`,`ui/MainActivity.kt`. DoD: 외부 ROM import 동작.
- **EP8.5 — update manifest schema versioning + rollback 정책** 🟡 — patch level 단조 증가는 `RomUpdateChannel`에 구현됨; rollback 정책 문서화 잔여. 대상 update 채널.
- **EP8.6 — offline-only 검증(release gate)** ⬜ — network fetch/background polling/telemetry/silent auto-update 0. DoD: `telemetry=off`, offline-only.
- **EP8.7 — proprietary 바이너리 인벤토리 + license/provenance 문서** ⬜ — 대상 문서/감사. DoD: `bundled_proprietary=0`, `license_docs=true`.
- **EP8.8 — crash report local-only** ⬜ — opt-in 없이 외부 전송 금지. 대상 `diag/CrashReportStore.kt`. DoD: 기본 외부 전송 0.

**Phase Exit Gate**

```text
PRODUCT_P6_SECURITY passed=true ed25519=true telemetry=off bundled_proprietary=0 license_docs=true
```
> 현재: `ed25519=true`(8.2) 충족, tar.zst·bootReady·ROM 빌더 완료. 잔여 = 8.1(stub 잔존 제거)·8.4(외부 import)·8.6·8.7·8.8.

---

## EP9 — Product UX (M8 / P7)

**Phase 목표**: 비개발자도 VM을 만들고 관리할 수 있는 앱 경험.

**선행**: EP4~EP8(기능이 있어야 UX로 노출).

### Steps

- **EP9.1 — 첫 실행 onboarding** — ROM 준비/권한 설명/저장공간 안내. 대상 `ui/`(신규 onboarding). DoD: `onboarding=true`.
- **EP9.2 — 인스턴스 그리드** — create/start/stop/delete/snapshot/backup 액션(현재 없음). 대상 `ui/`(신규 InstanceGridScreen), `vm/MultiInstanceController.kt` 연동. DoD: 다중 인스턴스 관리 UI.
- **EP9.3 — VM 디스플레이 화면** — 상태/부팅 진행/오류 복구/입력 컨트롤. 대상 `vm/VmNativeActivity.kt`, `ui/`. DoD: `recovery=true`.
- **EP9.4 — APK import 흐름** — 설치 진행/실패 사유/launcher 단축. 대상 `ui/MainActivity.kt`, `apk/`. DoD: 설치 UX 완비.
- **EP9.5 — 브리지 설정 확장** — 모드 설명/audit 히스토리/인스턴스별 정책. 대상 `ui/BridgeSettingsScreen.kt`. DoD: 브리지 UX 완비.
- **EP9.6 — 진단 화면** — health/logs/storage/FPS/memory/bridge activity. 대상 `ui/`(신규 DiagnosticsScreen), `diag/`. DoD: `diagnostics=true`.
- **EP9.7 — 오류 taxonomy** — 사용자 이해 가능 메시지 체계. DoD: 오류 코드↔메시지 매핑.
- **EP9.8 — 접근성/dynamic type/회전** — 검증. DoD: `accessibility=true`.

**Phase Exit Gate**

```text
PRODUCT_P7_UX passed=true onboarding=true recovery=true diagnostics=true accessibility=true
```

---

## EP10 — Release Engineering (M9 / P8)

**Phase 목표**: 반복 가능한 배포/회귀 방어/지원 체계.

**선행**: EP9.

### Steps

- **EP10.1 — Java 17 toolchain 유지** — 완료(`kotlin { jvmToolchain(17) }`). 회귀 방지 확인. DoD: `jdk=17`.
- **EP10.2 — canonical release gate 유지** — `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease`. DoD: 4잡 green.
- **EP10.3 — nightly device/product gate** — EP0.6과 통합. DoD: `nightly_green=true`.
- **EP10.4 — release signing/versioning/changelog/artifact retention** — 대상 `app/build.gradle.kts`, CI. DoD: `release_signed=true`.
- **EP10.5 — debug surface 미포함 검증** — receiver/asset/test-only fixed source 0. 대상 `ProductReleaseSurfaceGuardTest`. DoD: `debug_surface=0`.
- **EP10.6 — crash/log redaction** — 번들 redaction. 대상 `diag/FailureBundleCollector.kt`. DoD: 민감정보 0.
- **EP10.7 — beta rollout 체크리스트 + rollback plan** — 문서. DoD: `rollback_plan=true`.
- **EP10.8 — support 템플릿** — ROM import/boot/APK install/bridge permission 이슈 템플릿. DoD: 템플릿 4종.

**Phase Exit Gate**

```text
PRODUCT_P8_RELEASE passed=true jdk=17 release_signed=true debug_surface=0 nightly_green=true rollback_plan=true
```

---

## EP11 — Integration & Release Candidate

**Phase 목표**: release/product variant로 모든 게이트를 실측 통과시키고 RC를 만든다.

**선행**: EP1~EP10.

### Steps

- **EP11.1 — APK corpus 확정(≥10종)** — 카테고리: 단순 / WebView / 파일 picker / 오디오 / 네트워크 / 클립보드 / 카메라 / 마이크 / 백그라운드 서비스 / 대용량 stress. 대상 corpus 픽스처 전략. DoD: 10종 확보.
- **EP11.2 — corpus install/launch 실측** — 성공률 ≥ 90%, 실패 taxonomy 작성. 검증: E2E 러너. DoD: `corpus_launch_rate>=0.9`.
- **EP11.3 — soak 테스트** — 8h idle + 2h foreground, crash 0. DoD: `soak_hours>=8 crashes=0`.
- **EP11.4 — 통합 게이트 실행** — product runner로 6개 라인을 release-equivalent로 emit, 전부 `passed=true`(게스트 출처 판정).
- **EP11.5 — 사용자 문서** — ROM import/instance lifecycle/bridge permission/backup-restore/troubleshooting.
- **EP11.6 — RC 체크리스트 충족 → RC 빌드.**

**Phase Exit Gate (통합)**

```text
PRODUCT_GUEST_EXEC_RESULT passed=true ...
PRODUCT_RUNTIME_RESULT    passed=true boot=true install=true launch=true input=true graphics=true audio=true
PRODUCT_BRIDGE_RESULT     passed=true clipboard=true file=true network=true camera_policy=true mic_policy=true audit=true
PRODUCT_RESILIENCE_RESULT passed=true snapshot=true rollback=true crash_report=true boot_repair=true data_export=true
PRODUCT_SECURITY_RESULT   passed=true permissions=minimal update=ed25519 offline=true telemetry=off secrets=none
PRODUCT_RELEASE_RESULT    passed=true debug_surface=closed signed=true store_ready=true docs=true support=true
PRODUCT_P2_RUNTIME        passed=true corpus_launch_rate>=0.9 soak_hours>=8 crashes=0 synthetic_runtime=0
```

---

## 부록 A. Phase ↔ 게이트 ↔ 마일스톤 매핑

| EP | Exit Gate | 마일스톤 | product-readiness |
| --- | --- | --- | --- |
| EP0 | `PRODUCT_P0_DOC_TRUTH`, `PRODUCT_P1_VERIFICATION` | M0 | P0, P1, P8부분 |
| EP1 | `GUEST_ARCH_DECISION` | (신규) | — |
| EP2 | `G1_RESULT` | G1 | (신규) |
| EP3 | `G2_RESULT` | G2 | P2부분 |
| EP4 | `G3_RESULT`, `PRODUCT_GUEST_EXEC_RESULT` | G3 | P2 |
| EP5 | `PRODUCT_P3_MEDIA` | M4 | P3 |
| EP6 | `PRODUCT_P4_PRIVACY` | M5 | P4 |
| EP7 | `PRODUCT_P5_DATA` | M6 | P5 |
| EP8 | `PRODUCT_P6_SECURITY` | M7 | P6 |
| EP9 | `PRODUCT_P7_UX` | M8 | P7 |
| EP10 | `PRODUCT_P8_RELEASE` | M9 | P8 |
| EP11 | 6개 `PRODUCT_*_RESULT` + `PRODUCT_P2_RUNTIME` | 통합 | §15 RC |

## 부록 B. 실행 원칙(전 Phase 공통)

1. **Step 단위로 PR**: 각 Step(또는 묶음)은 검증 가능한 단위로 분리해 머지한다. CLAUDE.md 규약(브랜치 후 작업, 영문 커밋/식별자) 준수.
2. **canned 금지**: 어떤 Step도 게이트 통과를 위해 호스트가 상태를 위조하지 않는다. `synthetic*=0` 위반 시 자동 실패.
3. **선검증 후진행**: Step의 DoD를 만족하기 전 다음 Step으로 넘어가지 않는다(특히 EP2–EP4 선형 구간).
4. **병렬 트랙 관리**: EP7/EP8은 G-track과 병렬 가능하나, 최종 통합(EP11) 전 rebase/정합 필수.
5. **회귀 방어**: 새 동작마다 JVM 테스트 또는 device 진단을 추가하고, CI fast/nightly에 편입한다.

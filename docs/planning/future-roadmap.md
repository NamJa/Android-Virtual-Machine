# Long-term Phase Roadmap (index)

이 문서는 `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 의 "장기 로드맵" 절(Phase A~E)을 현재 저장소 코드 위에 펼친 **Phase 별 step plan 의 인덱스** 다. 각 Phase 의 세부 step·검증 게이트·코드 touchpoint 는 별도 문서로 분리되어 있다.

## 1. 게이트 체인

다음 순서로만 진행한다. 이전 Phase 의 종료 게이트가 통과되기 전까지 다음 Phase 의 어떤 step 도 시작하지 않는다.

```
Phase A 종료 (STAGE_PHASE_A_RESULT passed=true)
  ↓ entry
Phase B 종료 (STAGE_PHASE_B_RESULT passed=true)
  ↓ entry
Phase C 종료 (STAGE_PHASE_C_RESULT passed=true)
  ↓ entry
Phase D 종료 (STAGE_PHASE_D_RESULT passed=true)
  ↓ entry
Phase E 종료 (STAGE_PHASE_E_RESULT passed=true)
```

각 Phase 가 자신의 종료 게이트 라인을 출력하기 직전, 이전 모든 Phase 의 게이트 라인이 회귀로 통과해야 한다. 이를 위해 각 Phase 의 receiver 가 이전 Phase 라인을 먼저 emit 한다.

## 2. Phase 별 문서

| Phase | 문서 | 한 줄 요약 |
|---|---|---|
| **A — Host Shell** | [`phase-a-host-shell.md`](./phase-a-host-shell.md) | 단일 인스턴스 host APK 골격 + ROM 파이프라인 + permission 경계. 잔여: VmManagerService 격상, multi-instance ready API, 양 프로세스 IPC 신뢰 경계, CI gate. |
| **B — Guest Runtime PoC** | [`phase-b-guest-runtime-poc.md`](./phase-b-guest-runtime-poc.md) | 단일 PIE arm64 binary 한 개를 host 프로세스 안에서 실제로 실행. 핵심: 코드 모듈화, ELF loader, bionic linker, syscall dispatch, process state machine. |
| **C — Android Boot** | [`phase-c-android-boot.md`](./phase-c-android-boot.md) | `init`→`zygote64`→`system_server`→`SurfaceFlinger` 부팅. 핵심: 실 binder transport, ashmem/memfd, property service 격상, zygote, system_server, SurfaceFlinger commit. |
| **D — Usable VM** | [`phase-d-usable-vm.md`](./phase-d-usable-vm.md) | 사용자가 import 한 일반 APK 의 `Activity.onCreate` 진입 + 모든 bridge 실 연동. 핵심: PMS install, launcher, real dex 실행, Camera/Mic/VPN bridge, file import/export. |
| **E — Compatibility** | [`phase-e-compatibility.md`](./phase-e-compatibility.md) | multi-instance / snapshot / Android 10·12 / GLES·Virgl·Venus / (옵션) 32-bit·x86 translation / (옵션) GMS compatibility profile. |

## 3. Phase 별 Step 요약 표

각 Phase 의 step 수와 핵심 산출물.

### Phase A — Host Shell (5 steps)

| Step | 제목 | 결과물 |
|---|---|---|
| A.1 | `VmManagerService` orchestrator 격상 | binder API + 상태 영속화 |
| A.2 | Multi-instance ready API surface | hard-coded `"vm1"` 제거 |
| A.3 | 양 프로세스 IPC 신뢰 경계 | `VmIpcContract` + Messenger |
| A.4 | Canonical CI gate | GitHub Actions 자동화 |
| A.5 | Phase A 종합 회귀 receiver | `STAGE_PHASE_A_RESULT` 라인 |

### Phase B — Guest Runtime PoC (7 steps)

| Step | 제목 | 결과물 |
|---|---|---|
| B.1 | Native runtime 모듈화 | `cpp/{core,loader,syscall,vfs,binder,property,device,jni}/` |
| B.2 | Linux ELF64 loader skeleton | `loadElf64()` + aux vector |
| B.3 | Bionic linker 통합 | `__libc_init` 도달 |
| B.4 | Syscall dispatch table | ~25 개 syscall handler |
| B.5 | Process state machine | `CREATED → LOADING → RUNNING → ZOMBIE → REAPED` |
| B.6 | Single binary 실행 PoC | stdout "hello" 캡처 |
| B.7 | Phase B 종합 회귀 receiver | `STAGE_PHASE_B_RESULT` 라인 |

### Phase C — Android Boot (7 steps)

| Step | 제목 | 결과물 |
|---|---|---|
| C.1 | 실제 binder transport | parcel marshal + BC_TRANSACTION/BR_REPLY |
| C.2 | ashmem / memfd shim | cross-process shared memory |
| C.3 | Virtual init / property service 격상 | `__system_property_*` 동작 |
| C.4 | Zygote 부팅 | socket accepting |
| C.5 | system_server 부팅 | `boot_completed=1` |
| C.6 | SurfaceFlinger → host Surface | host Surface 위 첫 frame |
| C.7 | Phase C 종합 회귀 receiver | `STAGE_PHASE_C_RESULT` 라인 |

### Phase D — Usable VM (10 steps)

| Step | 제목 | 결과물 |
|---|---|---|
| D.1 | PackageManagerService 와 실 install 연동 | `pm list packages` 등장 |
| D.2 | Launcher 부팅 | Launcher 가 SurfaceFlinger 위에 그려짐 |
| D.3 | Real APK dex 실행 | `Activity.onCreate` 진입 |
| D.4 | Bridge 실 연동 (Clipboard/Audio/Vibration/Network) | guest framework 와 연결 |
| D.5 | Camera bridge 실 구현 | YUV420 frame 전달 |
| D.6 | Microphone bridge 실 구현 | AudioRecord PCM 전달 |
| D.7 | VpnService per-instance network isolation | virtual interface egress 분리 |
| D.8 | File import / export, SAF | host ↔ guest 일반 파일 |
| D.9 | Operational maturity (crash report / boot rollback / perf budget / data backup) | guest 안정성 가드 |
| D.10 | Phase D 종합 회귀 receiver | `STAGE_PHASE_D_RESULT` 라인 |

### Phase E — Compatibility (10 steps)

| Step | 제목 | 결과물 |
|---|---|---|
| E.1 | Multi-instance host (정적 N개) | `:vm1`~`:vmN` 동시 lifecycle |
| E.2 | Snapshot / overlay layer | base+overlay+snapshot 계층화 |
| E.3 | Android 10 호환 | 10 guest 부팅 + launcher |
| E.4 | Android 12 호환 | 12 guest 부팅 + launcher |
| E.5 | GLES passthrough | host EGL/GLES 노출 |
| E.6 | Virglrenderer | 3D acceleration |
| E.7 | Venus / Vulkan | Vulkan API forward |
| E.8 | 32-bit / x86 translation (옵션) | arm32 / x86 guest 실행 (core gate 와 분리) |
| E.9 | ROM 보안 업데이트 / 수동 import 채널 | offline-only signed manifest + 사용자 동의 CVE 패치 |
| E.10 | Phase E 종합 회귀 receiver | `STAGE_PHASE_E_RESULT` 라인 |

## 4. 진척 현황 (요약)

| Phase | 상태 |
|---|---|
| A | ✅ 종료 게이트 통과 (`STAGE_PHASE_A_RESULT passed=true`) |
| B | ✅ 종료 게이트 통과 (`STAGE_PHASE_B_RESULT passed=true`) |
| C | ✅ 종료 게이트 통과 (`STAGE_PHASE_C_RESULT passed=true`, emulator-5556 / 2026-04-30) |
| D | ✅ 종료 게이트 통과 (`STAGE_PHASE_D_RESULT passed=true`, emulator-5556 / 2026-04-30) |
| E | ✅ 종료 게이트 통과 (`STAGE_PHASE_E_RESULT passed=true`, emulator-5556 / 2026-04-30) |

자세한 현황은 `docs/planning/post-stage7-roadmap.md` "현재 완료 상태 요약" 표 참고.

## 5. 비목표 (모든 Phase 공통)

`docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 1단계 "제외 항목" 과 7단계 "금지할 기본 동작" 을 그대로 유지한다.

- VPhoneOS 의 native binary 재사용 / 광고 SDK / 통계 SDK / 외부 telemetry.
- 256 인스턴스용 manifest static component (E.1 은 정적 4개 한도).
- root 권한 상승 / 호스트 설정 무단 변경 / 권한 우회.
- `QUERY_ALL_PACKAGES`, `READ_PHONE_STATE`, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, 광고 ID manifest 선언 (`ManifestPermissionGuardTest` 가 모든 Phase 에서 강제).
- ROM 보안 업데이트는 offline signed import 로만 처리한다. host 앱의 외부 업데이트 서버 접속, background polling, telemetry, silent auto-update 는 금지한다.
- Optional GMS compatibility profile 은 core gate 밖의 license-gated 확장으로만 다루며 proprietary GMS package 를 번들링하지 않는다.

## 6. 부록 — 현재 stub 위치와 Phase 매핑

| 현재 코드 stub | 제거되는 Phase / Step |
|---|---|
| ~~`vm_native_bridge.cpp:1208` `system_server blocked: ELF loader is not implemented yet`~~ | ✅ B.2/B.3 resolved |
| ~~`vm_native_bridge.cpp:1204` `zygote=attempted`~~ | ✅ C.4 resolved (`zygote=main_loop;zygote_socket=accepting`) |
| `vm_native_bridge.cpp` `graphicsAccelerationMode = "surfaceflinger_composer"` 이후 GLES/Virgl/Venus 미구현 | ✅ E.5/E.6/E.7 gate resolved via supported-host probe or explicit graceful degradation |
| ~~`bridge/UnsupportedMediaBridge.kt` `${bridge}_unsupported_stage7_mvp`~~ | ✅ D.5/D.6 resolved |
| ~~`bridge/NetworkBridge.kt` enable/disable only~~ | ✅ D.4/D.7 resolved |
| ~~`bridge/LegacyBridgeKind.kt` 전체 파일~~ | ✅ A.2 resolved |
| ~~`vm/VmManagerService.kt` 단순 state holder~~ | ✅ A.1 resolved |

## 7. 참고

- 원본 plan: [`VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md`](./VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md).
- Stage 1~7 상세 plan: `stage-01-mvp-scope.md` ~ `stage-07-permission-bridges.md`.
- 단기 후속 작업 정리: [`post-stage7-roadmap.md`](./post-stage7-roadmap.md).

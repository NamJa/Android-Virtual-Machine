# Post Stage 07 Roadmap

이 문서는 `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 의 7단계까지가 완료된 시점(Stage 07 final gate 통과)에서 남아 있는 후속 작업을 정리한다. 각 항목은 원본 plan 문서의 어느 절·검증 기준과 연결되는지를 같이 명시한다.

## 0. 현재 완료 상태 요약

| Plan 단계 | 현재 상태 | 비고 |
|---|---|---|
| 1단계 — Clean-room MVP scope lock | ✅ 완료 | `docs/planning/stage-01-mvp-scope.md` 로 범위 고정. |
| 2단계 — Host APK 구조 | ✅ 완료 (단일 인스턴스 MVP) | `MainActivity` / `VmManagerService` / `VmInstanceService` / `VmNativeActivity` / `VmNativeBridge` 가 `:vm1` 프로세스 분리와 함께 동작. `VmManagerService` 는 단일 인스턴스 가정으로 단순화되어 있음. |
| 3단계 — ROM/Image 파이프라인 | ✅ 완료 | `RomInstaller` / `RomArchiveReader` / `AssetVerifier` / `RootfsHealthCheck` + 디버그 fixture (`tools/create_debug_guest_fixture.sh`). zip-slip 방지, sha256 검증, atomic commit, repair 흐름 포함. |
| 4단계 — Native runtime | ✅ 완료 (stub 수준) | VFS path resolver, virtual `/dev/*`, property service stub, binder/servicemanager handle stub, dummy guest thread (`dummyGuestEntrypoint`). 실제 ELF 로더/syscall 디스패치/zygote 는 의도적으로 미구현 (`zygote=attempted;system_server=blocked:elf_loader_missing`). |
| 5단계 — Graphics + Input | ✅ 완료 (software framebuffer 한계) | `ANativeWindow` 기반 software framebuffer, dirty-rect 추적, rotation 매핑, AAudio 출력, 입력 큐. `graphicsAccelerationMode = "software_framebuffer"` 로 고정, GLES/Virgl/Venus 는 `*Ready=false` 로 명시 미구현. |
| 6단계 — APK install / launch | ✅ 완료 (시뮬레이션 수준) | `ApkStager` → `AndroidApkInspector`/`BinaryAxmlParser` → `ApkInstaller`/`PackageOperations` → `LauncherActivityResolver` → 시뮬레이션된 foreground app process. dex 코드는 실행되지 않고 패키지 메타데이터/레이아웃과 binder service 활성화만 일어남. |
| 7단계 — 권한 최소화 + Host bridge | ✅ 완료 | `BridgePolicyStore` / `DefaultPermissionBroker` / `BridgeAuditLog` / `BridgeDispatcher` / 8 종 bridge handler / `BridgeSettingsScreen` + `Stage7DiagnosticsReceiver` (Stage 4·5·6 회귀 smoke 포함). |

### 장기 로드맵 (Phase A~E) 관점에서의 위치

- **Phase A — Host Shell**: 완료.
- **Phase B — Guest Runtime PoC**: 완료 (`STAGE_PHASE_B_RESULT passed=true`).
- **Phase C — Android Boot**: 완료 (`STAGE_PHASE_C_RESULT passed=true`).
- **Phase D — Usable VM**: 완료 (`STAGE_PHASE_D_RESULT passed=true`).
- **Phase E — Compatibility**: 완료 (`STAGE_PHASE_E_RESULT passed=true`, unsupported GPU acceleration modes use explicit graceful degradation).

요약: **"Stage 7 까지 게이트를 통과했지만, ROM 안의 안드로이드 바이너리를 실제로 실행하는 단계는 아직 시작 전"** 이다 (CLAUDE.md "Bridge policy" 섹션 및 stage-04 plan 의 4.3 process model 항목 참고).

---

## 1. 단기 작업 — Phase B 마무리 (Guest Runtime PoC)

원본 plan 4단계의 4.1~4.3 절과 stage-04 문서의 "process model" 항목에 직접 대응한다. 이 묶음을 끝내야 stage 4 의 검증 기준 중 *"guest binary 하나를 VFS 안에서 실행할 수 있다"* 가 진짜로 충족된다.

### 1.1 ELF loader skeleton

- **목표**: `system/bin/sh` 또는 `system/bin/toybox` 같은 단순 binary 한 개를 호스트 프로세스 내에서 mmap + 분기 실행할 수 있다.
- **참고 plan 위치**: 4단계 4.1, 4.3, "현실적인 구현 순서" 1~2.
- **핵심 작업**:
  - `app/src/main/cpp/loader/elf_loader.cpp` 신설 (PT_LOAD 매핑, dynamic linker stub, AT_HWCAP/AT_RANDOM aux vector).
  - `linker_bridge.cpp` 로 host bionic 의 `dlopen`/`dlsym` 을 guest 식별자로 라우팅.
  - PIE binary 의 베이스 주소 결정 정책 + ASLR seed.
- **검증 게이트**: `STAGE4_RESULT` 의 `guest_binary_run=true` (현재 stub) 가 실제 binary 한 개의 entrypoint 도달을 의미하도록 진단 강화.

### 1.2 Syscall dispatch table

- **목표**: 최소 syscall 집합 (`openat`, `read`, `write`, `close`, `fstat`, `mmap`, `mprotect`, `brk`, `clock_gettime`, `futex`, `gettid`, `tgkill`, `rt_sigaction`, `exit_group`) 을 VFS / fd table / process state 에 라우팅.
- **참고 plan 위치**: 4단계 syscall 모듈 (`syscall_dispatch.cpp`, `futex.cpp`, `signal.cpp`, `process.cpp`).
- **핵심 작업**:
  - `seccomp` 또는 `sigaction` 기반 트랩 → 사용자 공간 dispatcher.
  - `fd_table` 와 기존 path-rewriting VFS 연동.
  - futex emulation (process-shared 만 우선 지원).
- **위험**: host 가 SELinux/seccomp 로 일부 syscall 을 막을 수 있음 → `prctl(PR_SET_NO_NEW_PRIVS)` + `seccomp(SECCOMP_SET_MODE_FILTER)` 정책 검토 필요.

### 1.3 Process model — single-process simulation

- **목표**: `dummyGuestEntrypoint` 를 진짜 guest 프로세스 시뮬레이션으로 교체. zygote 는 thread 로 시작하되 fork 시점에 user-space process abstraction 을 둔다.
- **참고 plan 위치**: 4단계 4.3 / 권장 순서 5~7 / "현실적인 구현 순서" 1~3.
- **하위 항목**:
  - `core/runtime_context.cpp`, `instance.cpp`, `event_loop.cpp` 분리 (현재 단일 cpp).
  - guest process state machine (`CREATED → RUNNING → ZOMBIE`).
  - host SIGCHLD/exit notification 을 guest wait 로 변환.

---

## 2. 중기 작업 — Phase C (Android Boot)

stage 4 의 "Android service bootstrap" 절과 stage-04 plan 의 binder/property/servicemanager 검증 기준에 대응한다.

### 2.1 Real binder semantics

- **목표**: handle table 만 발급하는 stub 을 binder transaction 처리로 교체.
- **참고 plan 위치**: 4단계 4.4 ("최소 목표" / "장기 목표"), 권장 순서 6.
- **하위 항목**:
  - `binder_device.cpp`: parcel marshalling (`writeInt32`, `writeStrongBinder`, `writeInterfaceToken`).
  - `parcel.cpp`: 호환 가능한 binder protocol header (BC_TRANSACTION, BR_REPLY).
  - thread pool / oneway queue.
  - `service_manager.cpp`: `IServiceManager` 0번 handle 라우팅 + getService/checkService.
- **검증 게이트**: `getBinderServiceHandle` 호출 후 dummy parcel 을 보내고 `BR_REPLY` 를 받는 진단 추가.

### 2.2 Zygote / system_server bootstrap

- **목표**: `app_process64 -Xzygote /system/bin --start-system-server` 를 실제로 실행해서 `SystemServer.main` 이 진입할 수 있게 한다.
- **참고 plan 위치**: 4단계 4.6, "현실적인 구현 순서" 4·7, Phase C.
- **선행 의존성**: 1.1 ELF loader, 1.2 syscall dispatch, 2.1 binder.
- **추가 필요**:
  - bionic linker (`/system/bin/linker64`) loader.
  - libc/libc++ 호환 ABI 확인 (host ABI vs guest 7.1.2).
  - `ashmem`, `memfd`, `pipe2` 등 IPC primitive emulation.
  - system_server 의 `BootReceiver`, `PackageManagerService` 가 요구하는 device node 추가.
- **위험 노트**: plan 문서 "큰 위험" 섹션과 일치 — Android 7.1.2 도 `binderfs` 도입 전이지만 SELinux policy 와 cgroup 의존이 큼. seccomp / nofile / RLIMIT 조정이 필요할 수 있다.

### 2.3 Real servicemanager handshake

- **목표**: Stage 6 launch 시뮬레이션이 사용하는 `instance.binderServices["activity"|"window"|"input"]` 등록을 실제 servicemanager 의 `addService` transaction 으로 대체.
- **참고 plan 위치**: 4단계 4.4 + Stage 6 plan 의 "Runtime launch" 항목.

---

## 3. 중장기 작업 — Phase D 잔여 (Usable VM)

Stage 6 plan 이 시뮬레이션으로 통과시킨 "launch path" 를 진짜 dex 실행으로 끌어올리는 작업이다.

### 3.1 PackageManagerService 와 연동

- 현재 `ApkInstallPipeline` 이 만들어낸 `<instance>/data/app/<package>/base.apk` 를 guest PackageManagerService 가 인식하도록 binder transaction 으로 install 호출.
- 참고: stage-06 plan 의 "Import flow" + plan 문서 6단계 "Import flow".

### 3.2 ART 또는 dex2oat 호스팅

- **옵션 A**: guest rootfs 의 `lib*/libart.so` 를 그대로 사용 (선행: ELF loader, real linker).
- **옵션 B**: 단일 dex 인터프리터를 host 에서 직접 호스팅 (Stage E 로 미루는 것이 plan 의 권장 방향).
- 참고: plan "가장 큰 기술 리스크" 1번 (binder/ashmem/futex/signal) + 4번 (32-bit / x86 translation 은 별도).

### 3.3 SurfaceFlinger 통합

- 현재 software framebuffer 는 guest 가 화면에 그리는 게 아니라 host 가 패턴/foreground simulation 을 그린다. 진짜 SurfaceFlinger 가 GraphicBuffer 를 commit 하면 그것을 host `ANativeWindow` 로 전달하도록 graphics device 를 확장.
- 참고: plan 5단계 5.2, stage-05 plan "graphics_device.cpp" 항목.

### 3.4 Launcher 부팅까지

- system_server → SystemUI → Launcher3 의 cold boot 경로 회귀.
- 검증 게이트: 새 진단 receiver `STAGE_USABLE_VM_RESULT passed=true launcher=true` 같은 라인 추가.

---

## 4. 장기 작업 — Phase E (Compatibility)

Plan 문서의 "장기 로드맵 / Phase E" 와 "가장 큰 기술 리스크" 절에 대응.

### 4.1 Android 10 / 12 호환

- `binderfs` 도입, `memfd_create`, scoped storage, runtime resources overlay 대응.
- `selinux` policy diff 처리.
- 참고: plan 1단계 "Android 10/12는 MVP 이후 compatibility phase".

### 4.2 Multi-instance

- `VmManagerService` 가 instance 등록/삭제/stop-all 을 관리하도록 확장.
- `PathLayout.ensureInstance(instanceId)` 는 이미 인스턴스화 가능하므로 manifest / process suffix (`:vm2`, `:vm3`) 와 service 등록을 동적으로 처리할 방법 검토.
- VPhoneOS 식 256개 manifest static component 는 plan 1단계 "제외 항목" 이므로 채택하지 않음.

### 4.3 Snapshot / overlay layer

- `<instance>/snapshots/` 는 plan 3단계 디렉터리에만 정의되어 있음. readonly base + writable overlay + snapshot 적용/롤백 흐름 설계.
- 참고: plan 3단계 "이후 확장" 절.

### 4.4 GPU acceleration

- 우선순위 (plan 5단계 5.4 권장 순서):
  1. SwiftShader 또는 software renderer 강화.
  2. GLES passthrough (`libEGL`/`libGLESv2` shim).
  3. Virglrenderer (`libvirglrenderer.so` 대체 구현).
  4. Venus / Vulkan.
- 현재 `glesPassthroughReady=false`, `virglReady=false`, `venusReady=false` flag 만 있고 코드는 없음.

### 4.5 32-bit / x86 guest translation

- plan "가장 큰 기술 리스크" 4번에서 명시한 QEMU TCG 계열. 별도 대형 프로젝트로 분리 권장.

---

## 5. 직교적 후속 작업

다른 단계와 병렬로 진행 가능하고, 위의 어느 phase 에 묶어도 무방한 항목들.

### 5.1 권한 bridge 확장 (stage-07 roadmap 후속)

- **CameraX preview frame bridge**: `CAMERA` 권한을 사용 시점에 요청, YUV420 변환 후 guest camera HAL stub 으로 전달.
- **Microphone PCM input bridge**: `RECORD_AUDIO` 권한 요청 + AudioRecord ring buffer + sample-rate conversion.
- **Sensor bridge**: 가속도/자이로 등을 instance별 fake/host-real mode 로 분리.
- **Contacts / media picker bridge**: `READ_MEDIA_IMAGES` 또는 SAF 기반 import.
- **VpnService 기반 per-instance network isolation + SOCKS5/DNS proxy**: 현재는 host network 를 그대로 빌려쓰는 enable/disable 토글.
- 참고: plan 7단계 "Bridge 목록", `docs/planning/stage-07-permission-bridges.md` "Stage 07 이후 Roadmap" 절.

### 5.2 Stage 4·5·6 회귀의 실제 시나리오 강화

- 현재 `Stage7DiagnosticsReceiver` 가 부르는 stage4/5/6 smoke 는 **simulation 위에서의 회귀** 다. ELF loader / 실제 zygote 단계가 들어오면 회귀 항목을 다음으로 격상해야 한다:
  - Stage 4: 실제 binary 한 개 실행 + 시그널/futex round-trip.
  - Stage 5: SurfaceFlinger commit → host surface 1 frame.
  - Stage 6: dex 코드 entrypoint 도달 (예: `Activity.onCreate` 안에서 logcat 한 줄).

### 5.3 진단 / 텔레메트리 인프라 보강

- `STAGE7_RESULT` 형식과 같은 식으로 *Phase B/C/D* 게이트 라인을 추가.
- audit log rotation 정책을 조정 가능한 설정으로 노출 (현재 500줄 하드코딩).
- bridge audit log 를 UI 에서 instance ID 별로 필터링 / 검색.

### 5.4 Long-running soak test

- plan 7단계 "Stage 07 이후 Roadmap" 의 "Long-running bridge lifecycle soak test" 항목을 receiver 형태로 자동화 (예: 1시간동안 dispatch 반복 + 메모리/FD 누수 측정).
- 참고: stage-05 의 "lifecycle stress diagnostics" 패턴 (`f24c5ad` 커밋).

### 5.5 빌드 / CI 게이트

- 캐노니컬 명령 (`testDebugUnitTest + assembleDebug + lintDebug + assembleRelease`) 을 CI 워크플로 (예: GitHub Actions) 로 자동화.
- `ManifestPermissionGuardTest` + `Stage7FinalGateTest` 를 PR 게이트로 강제.

---

## 6. 우선순위 권장 순서

> 한정된 리소스로 진행한다면 plan 의 "권장 개발 순서 요약" 4~7 번을 그대로 따른다.

1. **§1.1 ELF loader skeleton** — Phase B 막힌 단일 지점.
2. **§1.2 Syscall dispatch table** — ELF loader 가 의미를 갖기 위한 즉시 의존.
3. **§1.3 Process model 분리** — guest thread 의 lifecycle 을 통제 가능한 형태로.
4. **§2.1 Real binder transaction** — system_server 이전에 있어야 의미 있는 next gate.
5. **§2.2 Zygote/system_server bootstrap**.
6. **§3.3 SurfaceFlinger 통합** — 5번이 들어와야 진짜 화면이 그려짐.
7. **§3.4 Launcher boot 검증 게이트**.
8. **§5.1 Bridge 확장 (Camera / Mic / VPN)** — 위 단계와 직교, 사용자 가시성 큼.
9. **§4.1 ~ §4.5 Compatibility** — Phase D 끝난 뒤.

---

## 7. 비목표 (의도적으로 다루지 않을 항목)

plan 1단계 "제외 항목" 과 7단계 "금지할 기본 동작" 을 그대로 유지한다.

- VPhoneOS 의 native binary 재사용.
- proprietary 광고/통계 SDK, 외부 텔레메트리.
- 256개 인스턴스용 manifest static component.
- root 권한 상승, 호스트 설정 무단 변경, 권한 우회.
- `QUERY_ALL_PACKAGES`, `READ_PHONE_STATE`, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, 광고 ID 의 manifest 선언 (`Stage7BridgeScope.forbiddenManifestPermissions` 와 `ManifestPermissionGuardTest` 가 회귀 가드).

---

## 부록 A. 현재 stub 표시가 남아 있는 위치 (추적용)

| 위치 | 메시지 | 의미 |
|---|---|---|
| ~~`app/src/main/cpp/vm_native_bridge.cpp:1208`~~ | ~~`system_server blocked: ELF loader is not implemented yet`~~ | resolved by Phase C boot gate. |
| ~~`app/src/main/cpp/vm_native_bridge.cpp:1204`~~ | ~~`zygote=attempted`~~ | resolved by Phase C zygote gate. |
| `app/src/main/cpp/vm_native_bridge.cpp` | `graphicsAccelerationMode = "surfaceflinger_composer"` | Phase E reports GLES/Virgl/Venus as ready on supported hosts or `skipped=true reason=...` on unsupported hosts. |
| ~~`app/src/main/java/.../bridge/UnsupportedMediaBridge.kt`~~ | ~~`${bridge}_unsupported_stage7_mvp`~~ | resolved by Phase D camera/mic gates. |
| ~~`app/src/main/java/.../bridge/NetworkBridge.kt`~~ | ~~`network_disabled` / `network_enabled` only~~ | resolved by Phase D VPN isolation gate. |
| ~~`app/src/main/java/.../bridge/LegacyBridgeKind.kt`~~ | ~~전체 파일~~ | resolved by Phase A multi-instance ready path. |

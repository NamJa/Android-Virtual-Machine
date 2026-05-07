# Product Readiness Plan

> 작성일: 2026-05-07
> 기준 커밋: `f7ef4fc Complete Phase E compatibility gate`
> 목적: Phase A-E gate 이후, 진단/스모크 기반 구현을 실제 사용자가 신뢰할 수 있는 완성 제품으로 격상하기 위한 검증 결과와 후속 작업 계획.

## 1. 현재 검증 결과

### 1.1 저장소 상태

- 브랜치: `main`
- 원격 동기화: `main...origin/main`
- 작업 트리: clean
- 최신 완료 커밋:
  - `f2c950b Complete Phase A host shell gates`
  - `23366ff Implement Phase B guest runtime PoC (#3)`
  - `663266a Complete Phase C Android boot gate`
  - `e05695c Complete Phase D usable VM gate`
  - `f7ef4fc Complete Phase E compatibility gate`

### 1.2 로컬 검증 명령

로컬 기본 JDK가 OpenJDK 25.0.2일 때 Gradle/Kotlin parser가 Java version 문자열을 처리하지 못해 실패한다. CI와 동일하게 Java 17을 명시하면 현재 JVM test와 debug build가 통과한다.

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:testDebugUnitTest
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:assembleDebug
```

결과:

- `:app:testDebugUnitTest`: passed
- `:app:assembleDebug`: passed
- 참고 warning: CMake 단계에서 Android SDK XML version 4 warning이 출력되지만 build는 성공한다.

### 1.3 현재 완료로 인정되는 것

현재 코드와 문서의 최신 기준은 `docs/planning/future-roadmap.md`와 `StagePhase*Diagnostics` 계열이다. 이 기준에서 Phase A-E gate는 모두 완료 상태다.

- Phase A: host shell, `VmManagerService`, multi-instance ready API, IPC contract, CI gate.
- Phase B: native runtime module split, ELF/linker/syscall/process PoC, single binary diagnostic.
- Phase C: binder/ashmem/property/zygote/system_server/SurfaceFlinger boot gate.
- Phase D: PMS/launcher/app-run/bridge/camera/mic/network/file/ops maturity gate.
- Phase E: multi-instance slots, snapshot, Android 10/12 profiles, GPU acceleration capability matrix, optional translation, offline ROM update channel gate.

### 1.4 제품화 관점의 검증 경계

Phase A-E는 중요한 기반이지만, 아직 "완전한 product"의 증거는 아니다. 현재 gate에는 다음 성격이 섞여 있다.

- JVM harness와 deterministic probe가 많다.
- Debug receiver가 실제 device probe를 주입하지만, release runtime과 동일한 product flow를 강제하지는 않는다.
- 일부 기능은 graceful degradation 또는 optional skip을 gate 통과로 인정한다.
- Camera/microphone diagnostics는 fixed source를 사용할 수 있다.
- ROM update channel은 production hook 설명이 있으나 placeholder verifier가 남아 있다.
- native graphics path에는 gralloc/composer stub 경계가 남아 있다.
- 문서 일부, 특히 `post-stage7-roadmap.md`와 오래된 phase 문서 본문에는 과거 상태가 남아 최신 완료 상태와 충돌한다.

따라서 다음 목표는 "gate 통과"가 아니라 "사용자가 ROM을 넣고, 앱을 설치하고, 반복 사용하고, 문제가 생겨도 복구할 수 있는 제품"이다.

## 2. Product Definition

### 2.1 MVP Product 목표

첫 product release는 다음을 모두 만족해야 한다.

- 사용자가 합법적으로 보유한 Android guest image를 앱 안으로 가져올 수 있다.
- VM instance를 만들고, 시작하고, 중지하고, 삭제할 수 있다.
- Android 7.1.2 arm64 guest에서 최소 10개 대표 APK가 설치되고 launcher에서 실행된다.
- 화면, 입력, clipboard, 파일 import/export, network on/off가 제품 UI에서 예측 가능하게 동작한다.
- camera/microphone은 off가 기본이고, 사용 시점 권한 요청과 audit trail이 명확하다.
- crash, boot failure, bad ROM, storage full, permission denied 같은 실패가 사용자에게 복구 가능한 메시지로 표시된다.
- release build에서 debug-only receiver나 synthetic success path가 제품 성공 조건으로 쓰이지 않는다.

### 2.2 Product 완료 기준

완료 기준은 다음 5개 gate를 모두 통과하는 것이다.

```text
PRODUCT_RUNTIME_RESULT passed=true boot=true install=true launch=true input=true graphics=true audio=true
PRODUCT_BRIDGE_RESULT passed=true clipboard=true file=true network=true camera_policy=true mic_policy=true audit=true
PRODUCT_RESILIENCE_RESULT passed=true snapshot=true rollback=true crash_report=true boot_repair=true data_export=true
PRODUCT_SECURITY_RESULT passed=true permissions=minimal update=ed25519 offline=true telemetry=off secrets=none
PRODUCT_RELEASE_RESULT passed=true debug_surface=closed signed=true store_ready=true docs=true support=true
```

## 3. P0 - Truth And Documentation Reset

목표: 현재 repository의 상태를 제품화 기준으로 다시 정렬하고, 오래된 계획 문서와 실제 코드의 불일치를 제거한다.

체크리스트:

- [ ] `post-stage7-roadmap.md`를 archive 또는 rewrite 한다.
- [ ] Phase A-E 문서의 "잔여 Step" 표를 최신 완료 상태와 제품화 잔여 상태로 분리한다.
- [ ] `future-roadmap.md`에 이 문서를 공식 후속 plan으로 연결한다.
- [ ] `README.md`에 현재 product status를 `diagnostic gates complete, product release pending`으로 명시한다.
- [ ] Debug receiver gate와 release product gate의 차이를 문서화한다.
- [ ] "실제 제품으로 인정되는 on-device scenario" 목록을 고정한다.

완료 게이트:

```text
PRODUCT_P0_DOC_TRUTH passed=true stale_docs=0 product_plan_linked=true release_status_clear=true
```

## 4. P1 - Real Product Verification Harness

목표: JVM unit test와 debug receiver를 넘어서, 실제 기기/에뮬레이터에서 release-equivalent flow를 자동 검증한다.

체크리스트:

- [ ] `product` 또는 `qa` build variant를 추가한다.
- [ ] debug-only receiver와 product gate runner를 분리한다.
- [ ] release-equivalent APK에서 product gate를 실행할 test runner를 만든다.
- [ ] 실제 ROM import부터 VM boot까지 end-to-end test를 만든다.
- [ ] 대표 APK corpus를 정의한다.
  - [ ] simple native-free app
  - [ ] WebView app
  - [ ] file picker 사용 app
  - [ ] audio output app
  - [ ] network app
  - [ ] clipboard app
  - [ ] camera permission request app
  - [ ] microphone permission request app
  - [ ] background service app
  - [ ] large APK install stress app
- [ ] 실패 시 logcat, tombstone, guest log, instance state를 bundle로 수집한다.
- [ ] CI에 JVM fast gate와 nightly device gate를 분리한다.

완료 게이트:

```text
PRODUCT_P1_VERIFICATION passed=true devices>=2 apk_corpus>=10 release_equivalent=true artifacts=collected
```

## 5. P2 - Runtime Correctness Hardening

목표: Android guest가 실제 앱을 장시간 실행할 때 깨지지 않는 runtime surface를 확보한다.

체크리스트:

- [ ] Binder transaction coverage를 실제 framework 호출 기준으로 확장한다.
- [ ] unsupported binder transaction은 crash 대신 typed failure로 반환한다.
- [ ] `/dev/binder`, binderfs, service manager path를 API level별로 검증한다.
- [ ] syscall dispatch table을 실제 app corpus에서 관찰된 syscall 기준으로 확장한다.
- [ ] signal/futex/thread-local-storage 경계를 stress test한다.
- [ ] ART/libc/linker handoff에서 host/guest TLS 충돌 여부를 검증한다.
- [ ] PMS install과 dexopt path를 synthetic fallback 없이 검증한다.
- [ ] zygote/system_server/SurfaceFlinger boot markers를 guest-origin signal로만 판정한다.
- [ ] 8시간 idle, 2시간 foreground app, 100회 start/stop soak test를 추가한다.

완료 게이트:

```text
PRODUCT_P2_RUNTIME passed=true corpus_launch_rate>=0.9 soak_hours>=8 crashes=0 synthetic_runtime=0
```

## 6. P3 - Graphics, Input, And Media Productization

목표: 화면과 입력을 사용자가 제품처럼 느낄 수 있는 수준으로 만든다.

체크리스트:

- [ ] software framebuffer의 frame pacing을 측정하고 24fps 이상을 보장한다.
- [ ] orientation, density, resize, multi-window 상태를 검증한다.
- [ ] touch, keyboard, back/home/recent 같은 기본 입력을 guest lifecycle과 연결한다.
- [ ] gralloc/composer stub 경계를 실제 buffer queue contract로 좁힌다.
- [ ] GLES/Virgl/Venus는 "지원됨", "미지원", "실험적" 상태를 product UI에 표시한다.
- [ ] audio output underrun/xrun counter를 제품 diagnostics에 노출한다.
- [ ] microphone은 `AudioRecord` production source를 연결하고 fixed PCM source는 test-only로 제한한다.
- [ ] camera는 CameraX production source를 연결하고 fixed frame source는 test-only로 제한한다.

완료 게이트:

```text
PRODUCT_P3_MEDIA passed=true fps_p50>=24 input_latency_ms_p95<=80 audio_xruns=0 fixed_sources_release=0
```

## 7. P4 - Bridge, Privacy, And Permission Productization

목표: host 개인정보와 guest 권한 경계를 제품 안전 기준으로 고정한다.

체크리스트:

- [ ] bridge별 default mode와 user-facing description을 확정한다.
- [ ] camera/microphone/location은 사용 시점 권한 요청만 허용한다.
- [ ] off/unsupported path가 host API를 호출하지 않는 것을 release gate에서 검증한다.
- [ ] audit log를 instance별로 보존하고, 사용자가 export/delete 할 수 있게 한다.
- [ ] file bridge는 SAF import/export, MIME type, size limit, path traversal 방어를 검증한다.
- [ ] network bridge는 host NAT, disabled, VPN isolated mode를 실제 socket path로 검증한다.
- [ ] device profile은 synthetic identity만 반환하고 host identifiers 노출을 금지한다.
- [ ] forbidden permission guard를 release manifest에도 적용한다.

완료 게이트:

```text
PRODUCT_P4_PRIVACY passed=true host_permission_on_use=true audit_export=true forbidden_permissions=0 host_id_leaks=0
```

## 8. P5 - Storage, Snapshot, And Data Safety

목표: 사용자 데이터가 사라지지 않고, 문제가 생기면 되돌릴 수 있게 한다.

체크리스트:

- [ ] rootfs base/overlay/snapshot layout migration을 실제 install base에서 검증한다.
- [ ] snapshot create/rollback/delete를 VM running/stopped 상태별로 정의한다.
- [ ] snapshot 중 전원 종료/앱 kill에 대한 atomicity를 검증한다.
- [ ] instance backup/export/import를 제품 UI와 연결한다.
- [ ] storage pressure 상태에서 install, boot, snapshot 실패 메시지를 검증한다.
- [ ] corrupt manifest/rootfs/runtime-state 복구 flow를 제품 UI에 연결한다.
- [ ] 데이터 삭제는 instance root 밖을 절대 건드리지 않는 canonical path test를 release gate에 넣는다.

완료 게이트:

```text
PRODUCT_P5_DATA passed=true snapshot_atomic=true backup_restore=true corrupt_repair=true path_escape=0
```

## 9. P6 - Security, Updates, And Compliance

목표: clean-room 원칙과 배포 가능성을 지키면서 ROM/update/security boundary를 완성한다.

체크리스트:

- [ ] placeholder `StubSha256SignatureVerifier`를 product path에서 제거한다.
- [ ] Ed25519 signature verification을 실제 offline manifest import flow에 연결한다.
- [ ] update manifest schema versioning과 rollback policy를 확정한다.
- [ ] network fetch, background polling, telemetry, silent auto-update가 없음을 release gate에서 검증한다.
- [ ] third-party/proprietary binary bundling 여부를 inventory로 점검한다.
- [ ] license notice, OSS attribution, clean-room provenance 문서를 만든다.
- [ ] Play Store 또는 side-load 배포에 필요한 policy checklist를 작성한다.
- [ ] crash report는 local-only 기본값으로 두고, 외부 전송은 explicit opt-in 없이는 금지한다.

완료 게이트:

```text
PRODUCT_P6_SECURITY passed=true ed25519=true telemetry=off bundled_proprietary=0 license_docs=true
```

## 10. P7 - Product UX And Operations

목표: 개발자가 아닌 사용자가 VM을 만들고 관리할 수 있는 앱 경험을 완성한다.

체크리스트:

- [ ] 첫 실행 onboarding: ROM 준비, 권한 설명, storage 안내.
- [ ] instance grid: create/start/stop/delete/snapshot/backup 액션 제공.
- [ ] VM display screen: 상태, boot progress, error recovery, input controls.
- [ ] APK import flow: install progress, failure reason, launcher shortcut.
- [ ] bridge settings: mode별 설명, audit history, per-instance policy.
- [ ] diagnostics screen: health, logs, storage, FPS, memory, bridge activity.
- [ ] 사용자가 이해할 수 있는 error taxonomy를 만든다.
- [ ] accessibility, dynamic type, landscape/portrait layout을 검증한다.

완료 게이트:

```text
PRODUCT_P7_UX passed=true onboarding=true recovery=true diagnostics=true accessibility=true
```

## 11. P8 - Release Engineering

목표: 반복 가능한 배포, 회귀 방어, 지원 체계를 만든다.

체크리스트:

- [ ] Java 17 toolchain을 Gradle 또는 CI에 명시해 JDK 25 실패를 방지한다.
- [ ] `:app:testDebugUnitTest`, `:app:assembleDebug`, `:app:lintDebug`, `:app:assembleRelease`를 canonical release gate로 유지한다.
- [ ] nightly device/product gate를 추가한다.
- [ ] release signing, versioning, changelog, artifact retention을 구성한다.
- [ ] debug receiver와 debug assets가 release APK에 들어가지 않음을 검증한다.
- [ ] crash/log bundle redaction을 검증한다.
- [ ] beta rollout checklist와 rollback plan을 만든다.
- [ ] support template: ROM import issue, boot issue, APK install issue, bridge permission issue.

완료 게이트:

```text
PRODUCT_P8_RELEASE passed=true jdk=17 release_signed=true debug_surface=0 nightly_green=true rollback_plan=true
```

## 12. 권장 실행 순서

1. P0 문서 정합성 reset
2. P1 release-equivalent verification harness
3. P2 runtime correctness hardening
4. P4 privacy/permission product gate
5. P5 data safety
6. P3 media/input polish
7. P6 security/update/compliance
8. P7 UX/operations
9. P8 release engineering

P1과 P2가 제품화의 중심축이다. 실제 APK corpus와 release-equivalent runner가 없으면 이후의 UX나 release 작업은 신뢰할 수 있는 기준을 갖기 어렵다.

## 13. Immediate Next Work

가장 먼저 진행할 10개 작업:

- [ ] `future-roadmap.md`에 이 문서를 후속 product plan으로 링크한다.
- [ ] `post-stage7-roadmap.md`의 과거 상태 문구를 최신 상태와 제품화 gap 중심으로 갱신한다.
- [ ] Gradle Java toolchain 또는 wrapper 문서에 Java 17 requirement를 고정한다.
- [ ] product gate line data class를 만든다.
- [ ] product gate runner skeleton을 만든다.
- [ ] release build에 debug receiver가 포함되지 않는 테스트를 추가한다.
- [ ] APK corpus fixture 전략을 정한다.
- [ ] real-device verification matrix를 정한다.
- [ ] `StubSha256SignatureVerifier` 제거 계획과 Ed25519 wiring task를 issue화한다.
- [ ] fixed camera/mic source가 release product path에 들어가지 않는 guard test를 추가한다.

## 14. Risk Register

| Risk | Impact | Mitigation |
|---|---|---|
| Diagnostic gate와 product behavior가 다름 | 높은 false confidence | P1 release-equivalent runner를 최우선으로 만든다 |
| Real APK compatibility가 corpus 밖에서 낮음 | product retention 저하 | APK corpus를 카테고리별로 늘리고 launch failure taxonomy를 만든다 |
| Host/guest TLS 또는 libc 충돌 | hard crash | ART/linker soak test와 tombstone triage를 P2에 포함한다 |
| GPU acceleration이 host별로 다름 | UX 편차 | capability matrix를 제품 UI에 노출하고 software fallback을 안정화한다 |
| Camera/mic privacy regression | 치명적 신뢰 훼손 | off path host API call 금지 gate와 audit export를 P4에 둔다 |
| ROM/update legal risk | 배포 불가 | clean-room provenance, user-provided ROM, offline signed import만 유지한다 |
| Snapshot/data loss | 사용자 데이터 손실 | P5 atomicity, backup/restore, corrupt repair gate를 release blocker로 둔다 |

## 15. Release Candidate Checklist

Release candidate는 다음을 모두 만족할 때만 만든다.

- [ ] `PRODUCT_*_RESULT passed=true` 모든 line 통과.
- [ ] 대표 APK corpus install/launch pass rate 90% 이상.
- [ ] 8시간 idle soak와 2시간 foreground app soak에서 crash 0회.
- [ ] release APK에 debug receiver/debug fixture/test-only fixed source 없음.
- [ ] forbidden permissions 0개.
- [ ] telemetry/background network update 0개.
- [ ] ROM update signature verification은 Ed25519 product path 사용.
- [ ] 사용자 문서: ROM import, instance lifecycle, bridge permission, backup/restore, troubleshooting 포함.
- [ ] beta rollback plan과 issue triage template 준비.


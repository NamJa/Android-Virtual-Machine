# Phase A — Host Shell

> 본 문서는 `docs/planning/future-roadmap.md` 의 Phase A 절을 step 단위로 풀어 쓴 detailed plan 이다.
> 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` Phase A.
> Phase B 진입 직전까지의 모든 잔여 작업과 이미 완료된 항목 회귀 게이트를 한 곳에 모은다.

## 1. 진입 조건

Phase A 는 프로젝트 시작 시점에서부터 진행된 foundation 작업이다. **외부 진입 조건은 없다.**

## 2. 산출물

- 단일 인스턴스 + 단일 호스트 ABI 의 Android Virtual Machine 골격이 안정.
- 다음 layer 의 작업이 의존하는 인프라가 모두 자리 잡고, 회귀 가드가 자동으로 굴러간다:
  - host APK manifest / process split / service lifecycle.
  - JNI bridge (ABI 고정).
  - software framebuffer + 입력 큐 + AAudio 출력.
  - ROM 파이프라인 + instance storage layout.
  - bridge / permission / audit 인프라.
  - VmManagerService 가 인스턴스 lifecycle 의 단일 진입점.
  - canonical Gradle gate 가 CI 에서 자동 강제.

## 3. 진척 현황 요약

| 영역 | 상태 | 참고 |
|---|---|---|
| host APK & manifest | ✅ | `app/src/main/AndroidManifest.xml` |
| `:vm1` 프로세스 분리 | ✅ | `VmNativeActivity`, `VmInstanceService` 모두 `android:process=":vm1"` |
| JNI bridge surface | ✅ | `app/src/main/java/dev/jongwoo/androidvm/vm/VmNativeBridge.kt` (39 external 함수) |
| Surface lifecycle | ✅ | `VmNativeActivity` `surfaceCreated/Changed/Destroyed` |
| Software framebuffer dummy renderer | ✅ | `vm_native_bridge.cpp` `drawFrame`, `framebufferTestPattern` |
| Instance storage layout | ✅ | `PathLayout` / `InstancePaths` |
| ROM pipeline | ✅ | Stage 3, `RomInstaller` / `RomArchiveReader` |
| Permission / audit boundary | ✅ | Stage 7, `BridgePolicyStore` / `BridgeAuditLog` / `Stage7DiagnosticsReceiver` |
| `VmManagerService` 격상 | ❌ | 단순 state holder (23 줄) |
| Multi-instance ready API | ❌ | hard-coded `"vm1"` 경로 다수 |
| 양 프로세스 IPC 신뢰 경계 | ❌ | default ↔ `:vm1` 사이 messaging 일원화 안 됨 |
| Canonical CI gate | ❌ | gradle 명령은 문서화되어 있으나 자동 실행 없음 |

## 4. 잔여 Step 일람

| Step | 제목 | 의존 | 결과물 |
|---|---|---|---|
| A.1 | `VmManagerService` orchestrator 격상 | none | binder API 로 `start/stop/status/observe`, 상태 영속화 |
| A.2 | Multi-instance ready API surface | A.1 | `instanceId` 의 hard-code 제거, 신규 인스턴스 디렉터리 격리 |
| A.3 | 양 프로세스 IPC 신뢰 경계 | A.1 | default ↔ `:vm1` 양방향 메시지 규약 + JSON 직렬화 |
| A.4 | Canonical CI gate | A.1~A.3 | GitHub Actions / 동등 CI 워크플로 |
| A.5 | Phase A 종합 회귀 receiver | A.1~A.4 | `STAGE_PHASE_A_RESULT passed=true ...` |

---

## Step A.1 — `VmManagerService` orchestrator 격상

### 5.1.1 Background

현재 `app/src/main/java/dev/jongwoo/androidvm/vm/VmManagerService.kt` 는 23 줄, 본질적으로 `VmState` 를 변수에 보관하는 state holder 다. 한편 `VmInstanceService` 는 `start/stop/...` 정적 helper 를 통해 `MainActivity` 에서 직접 호출되고 있다 (단방향). 결과적으로:

- 인스턴스의 lifecycle 상태가 두 군데(`VmInstanceService.state`, `VmManagerService.state`) 에 흩어진다.
- UI (`MainActivity`) 가 상태를 polling 하지 않으면 갱신을 알 수 없다.
- 인스턴스가 늘어나면 정적 helper 호출 모델은 깨진다.

Plan 2단계 `VmManagerService` 책임:

- VM 생성/삭제/시작/중지 요청 처리.
- VM 상태 저장.
- foreground service bind 관리.
- UI 에 상태 이벤트 전달.

### 5.1.2 목표

`VmManagerService` 가 다음을 모두 수행한다:

1. `VmInstanceService` 를 bind 해 lifecycle 호출을 일원화.
2. 인스턴스별 상태(`Map<String, VmState>`) 를 보관하고 변화 시 `Flow` 로 푸시.
3. 인스턴스 상태를 디스크에 영속화 (앱 강종 후 복원).
4. UI 와 binder bridge 한 곳으로 합친다.

### 5.1.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmManagerService.kt` | 핵심 재작성 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmInstanceService.kt` | `LocalBinder` 추가, action receive |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmState.kt` | (옵션) `STARTING/RUNNING/STOPPING/STOPPED/ERROR` 외 `UNKNOWN` 유지 |
| `app/src/main/java/dev/jongwoo/androidvm/storage/PathLayout.kt` | `runtimeStateFile` 추가 (NEW filename) |
| `app/src/main/java/dev/jongwoo/androidvm/storage/InstanceStore.kt` | `list()`, `loadRuntimeState()`, `saveRuntimeState()` 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/ui/MainActivity.kt` | bind 로 상태 구독 |
| `app/src/test/java/dev/jongwoo/androidvm/vm/VmManagerServiceTest.kt` | NEW |

### 5.1.4 세부 작업

#### A.1.a `VmManagerService` 의 binder API 정의

```kotlin
// LocalBinder
fun start(instanceId: String): Boolean
fun stop(instanceId: String): Boolean
fun status(instanceId: String): VmState
fun observe(): StateFlow<Map<String, VmState>>
fun listInstances(): List<String>
```

#### A.1.b `VmInstanceService` 측 변경

- 기존 정적 helper (`start(context)`, `stop(context)`, `importApk(...)`) 는 deprecate 하지 않고 유지하되, **모두 `VmManagerService` 를 거쳐서만 호출** 되도록 `MainActivity` 가 사용 위치를 옮긴다.
- `VmInstanceService` 는 자체 상태 변경을 `LocalBroadcastManager` 또는 `Messenger` 로 default 프로세스에 푸시한다 (Step A.3 와 결합).

#### A.1.c 상태 영속화

- 파일: `<filesDir>/avm/runtime-state.json`
- 스키마:

```json
{
  "version": 1,
  "instances": {
    "vm1": { "state": "RUNNING", "lastChangedMillis": 1703980800000 }
  }
}
```

- 부팅 시: `VmManagerService.onCreate()` 에서 디스크 상태를 로드하고, 30 초 grace 내 `VmInstanceService` 가 살아 있으면 그 상태로 복원.
- 갱신 시: 상태 변경 → `runtime-state.json` 즉시 atomic write (.tmp + rename).

#### A.1.d UI 결합

`MainActivity` 가 service connect 성공 후 `LocalBinder.observe()` 를 `collectAsState()` 로 구독한다. 기존 `var state by remember { mutableStateOf(VmState.STOPPED) }` 는 제거.

#### A.1.e 단위 테스트 — `VmManagerServiceTest`

JVM 단위 테스트 (Robolectric 미사용) 가 가능하도록, manager 의 핵심 로직을 별도 helper 로 추출한다. 예:

```kotlin
class VmRuntimeStateStore(private val file: File) {
    fun load(): Map<String, VmState>
    fun save(state: Map<String, VmState>)
}
```

- 테스트 케이스:
  1. `save → load → 동일 map`.
  2. corrupt JSON → `emptyMap()` 회귀.
  3. 동시 `save` 중 partial write 가 다음 load 에서 noise 가 되지 않음 (atomic rename).
  4. 두 인스턴스 (`"vm1"`, `"vm-test"`) 가 한 파일에서 격리된 채 저장/복원.

### 5.1.5 검증 게이트

- `:app:testDebugUnitTest --tests "dev.jongwoo.androidvm.vm.VmManagerServiceTest"` 통과.
- 수동 회귀: 앱 실행 → Start → 강제 종료 (Force Stop) → 재실행 후 `VmState == RUNNING` 으로 표시.
- 회귀 라인: `STAGE_PHASE_A_VM_MANAGER passed=true persisted=true bound=true`.

### 5.1.6 위험과 롤백

- 위험: `VmInstanceService` 의 정적 helper 가 외부에서 (예: 추후 `:vm2` 프로세스에서) 호출될 가능성. 롤백을 위해 정적 helper 를 즉시 삭제하지 말고, 한 minor release 동안 두고 deprecation 로그를 남긴다.
- 위험: 영속화 파일이 잘못된 schema 로 굳어버리면 사용자 데이터가 stuck. → load 실패 시 `runtime-state.bak.<timestamp>.json` 으로 백업하고 빈 map 으로 초기화.

---

## Step A.2 — Multi-instance ready API surface

### 5.2.1 Background

현재 `instanceId` 가 `VmConfig.DEFAULT_INSTANCE_ID = "vm1"` 로 fix 된 채 다음 위치에서 직접 사용된다.

```text
app/src/main/java/dev/jongwoo/androidvm/vm/VmInstanceService.kt:34, 43, 49, 58, 69, 124
app/src/main/java/dev/jongwoo/androidvm/vm/VmNativeActivity.kt:19
app/src/debug/java/dev/jongwoo/androidvm/debug/Stage*DiagnosticsReceiver.kt
```

Phase A 에서 multi-instance 자체를 도입하지는 않지만, Phase E.1 에서 정적 N 개 인스턴스 manifest 를 추가할 때 단일 진입점만 수정하면 되도록 **확장 가능한 API surface** 를 만들어 둔다.

### 5.2.2 목표

- `VmConfig.DEFAULT_INSTANCE_ID` 가 UI 기본값 한 군데에서만 참조된다.
- `BridgePolicyStore`, `BridgeAuditLog`, `RomInstaller`, `ApkInstallPipeline`, `PackageOperations` 모두 `instanceId` 를 받아 동작.
- `InstanceStore.list()` 가 디스크에 존재하는 인스턴스 디렉터리를 enumerate.

### 5.2.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/storage/InstanceStore.kt` | `list()` / `delete(instanceId)` 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmConfig.kt` | `DEFAULT_INSTANCE_ID` 의 사용 위치를 grep 으로 점검 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmInstanceService.kt` | helper signature 가 instanceId 를 받도록 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmNativeActivity.kt` | 인텐트 extra 로 `instanceId` 전달 |
| `app/src/test/java/dev/jongwoo/androidvm/storage/MultiInstanceReadinessTest.kt` | NEW |

### 5.2.4 세부 작업

#### A.2.a `InstanceStore` 확장

```kotlin
class InstanceStore(context: Context) {
    fun list(): List<String>            // <filesDir>/avm/instances/* 의 디렉터리 이름
    fun ensureDefaultConfig(): VmConfig // 기존 동작 유지
    fun delete(instanceId: String)      // 디렉터리 전체 정리 (정책: 본인 root 안에서만)
}
```

`delete()` 는 path canonicalization 후 `<filesDir>/avm/instances/<instanceId>` 의 prefix 검증을 통과한 경우에만 실행한다.

#### A.2.b helper signature 통일

```kotlin
// 기존
fun importApk(context: Context, stagedPath: String)
fun launchPackage(context: Context, packageName: String)

// 변경 후
fun importApk(context: Context, instanceId: String, stagedPath: String)
fun launchPackage(context: Context, instanceId: String, packageName: String)
```

내부 Intent extra 로 `EXTRA_INSTANCE_ID` 를 추가하고, 받는 쪽이 명시적으로 매개변수로 다룬다.

#### A.2.c hard-coded `"vm1"` grep & 제거

다음 명령으로 점검:

```sh
grep -rn '"vm1"' app/src/main app/src/debug --include='*.kt' --include='*.cpp'
```

UI default 값(`MainActivity`, `VmNativeActivity`) 은 의도적으로 남겨두되, 그 외 `BridgePolicyStore` / `ApkInstallPipeline` 등에서 발견되면 매개변수화.

#### A.2.d 회귀 테스트 — `MultiInstanceReadinessTest`

```kotlin
@Test
fun policiesIsolatedAcrossInstances()
@Test
fun auditLogsIsolatedAcrossInstances()
@Test
fun romInstallerScansSpecificInstance()
@Test
fun deleteInstanceRemovesOnlyThatInstanceDirectory()
```

테스트는 두 임시 instance root (`vm1`, `vm-test`) 를 만들고 `BridgePolicyStore`, `BridgeAuditLog`, `PathLayout.ensureInstance` 가 격리됨을 확인한다.

### 5.2.5 검증 게이트

- 단위 테스트 통과.
- `grep -rn '"vm1"' app/src/main --include='*.kt' --include='*.cpp'` 결과가 UI default 와 fixture 외에는 없음.
- 회귀 라인: `STAGE_PHASE_A_MULTI_INSTANCE_READY passed=true store=isolated audit=isolated`.

---

## Step A.3 — 양 프로세스 IPC 신뢰 경계

### 5.3.1 Background

`VmNativeBridge` 가 default 프로세스와 `:vm1` 프로세스에서 따로 native 라이브러리를 로드한다. 두 프로세스가 본 native 상태(`std::map<instanceId, Instance>`) 는 메모리 분리되어 있어 동기화되지 않는다. CLAUDE.md 에 다음 문구가 있다:

> calls from `:vm1` hit the same in-memory `Instance` table; calls from the default process see a *different* native state and should be limited to read-only smoke checks.

즉 default 프로세스가 native 를 호출해 얻는 값은 부정확할 수 있다. 이를 명문화하고, **상태 동기화를 binder 로 일원화** 한다.

### 5.3.2 목표

- default 프로세스는 native call 을 read-only smoke 로만 사용.
- 상태 진실은 `VmInstanceService` 가 `:vm1` 프로세스에서 native 와 동기화하고, default 프로세스에 binder/Messenger 로 푸시.
- 모든 cross-process 메시지는 JSON 문자열로 직렬화 (이미 native ↔ Kotlin 사이는 그렇다).

### 5.3.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmInstanceService.kt` | Messenger / Binder 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmManagerService.kt` | `:vm1` 의 Messenger 로부터 상태 수신 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmIpcContract.kt` | NEW — 메시지 코드/스키마 |
| `CLAUDE.md` | "Two-process model" 절 갱신 |

### 5.3.4 세부 작업

#### A.3.a 메시지 컨트랙트 정의

`VmIpcContract.kt`:

```kotlin
object VmIpc {
    const val MSG_BOOTSTRAP_STATUS = 0x10
    const val MSG_BRIDGE_STATUS = 0x20
    const val MSG_PACKAGE_STATUS = 0x30
    const val MSG_GENERIC_LOG = 0x40
    // payload: Bundle("instanceId", "json")
}
```

#### A.3.b `VmInstanceService` 가 Messenger 노출

- service 가 `Messenger(handler)` 를 가지고 있고, default 프로세스가 service 를 bind 하면 reply Messenger 를 등록.
- native 상태 변화 시 (예: `getBootstrapStatus`, `getPackageOperationStatus` 의 polling 또는 native callback) 등록된 Messenger 들에 broadcast.

#### A.3.c default 프로세스 바인딩

`VmManagerService` 가 `bindService(VmInstanceService)` 로 reply Messenger 를 등록하고, 받은 메시지를 `StateFlow` 로 변환.

#### A.3.d native 의 boundary 문서화

CLAUDE.md "Two-process model" 절을 다음과 같이 보강:

```markdown
- default 프로세스에서 native 를 호출해 얻은 값은 read-only smoke 다.
- 권위 있는 상태는 :vm1 의 VmInstanceService 가 보유하고 binder/Messenger 로 default 에 푸시한다.
- 새 cross-process 메시지를 추가할 때는 VmIpcContract 의 코드/스키마를 동시에 갱신한다.
```

### 5.3.5 검증 게이트

- 진단: `Stage4DiagnosticsReceiver` 또는 신규 receiver 에서 default 프로세스가 본 `getBootstrapStatus` 와 `:vm1` 가 푸시한 마지막 상태가 일치하는지 비교.
- 회귀 라인: `STAGE_PHASE_A_IPC passed=true contract=ok cross_process_state=consistent`.

### 5.3.6 위험

- Android 가 Messenger transaction 을 GC 하지 않게 하려면 reply Messenger 의 `binder.linkToDeath` 처리가 필요. 누락 시 `:vm1` 가 죽었을 때 default 프로세스가 stale Messenger 로 send 시도 → DeadObjectException.

---

## Step A.4 — Canonical CI gate

### 5.4.1 Background

`CLAUDE.md` 에 canonical gradle 명령이 적혀 있고, Stage 7 final gate 가 unit test 로 결과 형식을 고정한다. 그러나 자동으로 굴리는 CI 파이프라인은 없다. PR 또는 main push 시 자동 실행이 필요.

### 5.4.2 목표

- GitHub Actions 워크플로 (또는 동등 CI) 가 push / PR 마다 다음을 강제:
  - `:app:testDebugUnitTest`
  - `:app:assembleDebug`
  - `:app:lintDebug`
  - `:app:assembleRelease`
- 실패 시 main 분기 머지 차단.
- 캐시 적용으로 5 분 이내 결과.

### 5.4.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `.github/workflows/ci.yml` | NEW |
| `.github/dependabot.yml` (옵션) | NEW |
| `CLAUDE.md` | CI 섹션 추가 |
| `gradle/wrapper/gradle-wrapper.properties` | (이미 있음) 버전 확인 |

### 5.4.4 세부 작업

#### A.4.a `.github/workflows/ci.yml` 골격

```yaml
name: ci
on:
  push:
    branches: [main]
  pull_request:

jobs:
  gradle-gate:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
      - uses: android-actions/setup-android@v3
      - run: ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

#### A.4.b 캐시 / NDK

- `android-actions/setup-android@v3` 가 NDK 도 자동 설치하지만, 명시 cmdline-tools 버전을 lock.
- `~/.android` 의 sdk component 캐시도 별도 cache key 로 묶어 5 분 이내 빌드 보장.

#### A.4.c PR 게이트 룰

- main 분기 protection: "Require status checks to pass before merging" → `gradle-gate` 추가.
- "Require branches to be up to date".

#### A.4.d Smoke audit job (옵션)

- 별도 job 으로 `Stage7FinalGateTest` 만 실행해 forbidden permission 도입을 가장 빠르게 차단:

```yaml
manifest-guard:
  runs-on: ubuntu-latest
  steps: [...]
  - run: ./gradlew --no-daemon :app:testDebugUnitTest --tests "dev.jongwoo.androidvm.bridge.ManifestPermissionGuardTest"
```

### 5.4.5 검증 게이트

- main push 시 모든 job 통과.
- 의도적으로 `READ_PHONE_STATE` 를 manifest 에 추가한 PR 이 빨갛게 됨 (회귀 dry-run).
- 회귀 라인 (수동 추가): README 또는 CLAUDE.md 의 "build status" 배지로 가시성 확보.

### 5.4.6 위험

- NDK 다운로드 / cmake 캐시 미스 시 빌드 시간이 폭증. 첫 빌드는 15 분 이상 가능.
- macOS 러너는 비싸고 느려서 ubuntu-only 로 운영. 추후 release-channel 빌드만 macOS.

---

## Step A.5 — Phase A 종합 회귀 receiver

### 5.5.1 Background

Phase A 의 모든 step 결과를 한 줄로 요약하는 게이트가 있어야 다음 Phase 진입 조건이 명확.

### 5.5.2 목표

- 신규 `StagePhaseADiagnosticsReceiver` 또는 기존 `Stage7DiagnosticsReceiver` 에 phase-level 라인을 덧붙인다.
- 라인 형식:

```text
STAGE_PHASE_A_RESULT passed=true vm_manager=true multi_instance_ready=true ipc=true ci=true stage4=true stage5=true stage6=true stage7=true
```

### 5.5.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/bridge/Stage7Diagnostics.kt` | (옵션) phase-level summary 추가 |
| `app/src/debug/java/dev/jongwoo/androidvm/debug/StagePhaseADiagnosticsReceiver.kt` | NEW |
| `app/src/debug/AndroidManifest.xml` | receiver 등록 |
| `app/src/test/java/dev/jongwoo/androidvm/bridge/StagePhaseAFinalGateTest.kt` | NEW |

### 5.5.4 세부 작업

- A.1~A.4 의 각 step 검증 함수를 모아 한 receiver 가 일괄 실행.
- Stage 4·5·6·7 의 기존 스모크와 함께 출력해 회귀 게이트 일관성 확보.
- `StagePhaseAFinalGateTest` 가 출력 라인 포맷을 픽스.

### 5.5.5 검증 게이트

```sh
adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_PHASE_A_DIAGNOSTICS \
    -n dev.jongwoo.androidvm/.debug.StagePhaseADiagnosticsReceiver
adb logcat -s AVM.PhaseADiag
```

마지막에 다음 라인이 나와야 한다:

```text
STAGE_PHASE_A_RESULT passed=true vm_manager=true multi_instance_ready=true ipc=true ci=true stage4=true stage5=true stage6=true stage7=true
```

---

## 6. Phase A 종료 게이트

다음을 **모두** 만족해야 Phase B 의 어떤 step 도 시작하지 않는다.

- [ ] `JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease` 통과 (CI 자동).
- [ ] Stage 4·5·6·7 broadcast receiver 가 모두 `passed=true`.
- [ ] `VmManagerServiceTest`, `MultiInstanceReadinessTest`, `StagePhaseAFinalGateTest` 통과.
- [ ] `STAGE_PHASE_A_RESULT passed=true ...` 라인이 emulator log 에 기록.
- [ ] CI 가 main / PR 양쪽에서 자동 실행 + 실패 시 머지 차단.
- [ ] 앱 강제 종료 → 재실행 시 직전 VM 상태 복원 수동 회귀 OK.
- [ ] CLAUDE.md 의 "Two-process model" 절 갱신 완료.

## 7. 비목표 (Phase A 가 다루지 않는 항목)

- 진짜 multi-instance manifest 선언 (Phase E.1).
- ELF / syscall / binder 어떤 실제 native runtime 작업 (Phase B 이후).
- bridge 실 구현 (Phase D).

## 8. 참고

- 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 1~3, 7 단계.
- 게이트 인덱스: `docs/planning/future-roadmap.md` Phase A 절.
- 현황: `docs/planning/post-stage7-roadmap.md` "현재 완료 상태 요약" 표.

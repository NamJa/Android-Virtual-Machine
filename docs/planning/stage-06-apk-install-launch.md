# Stage 06 - APK Install and Launch

## 목적

Stage 06의 목표는 host에서 선택한 APK를 guest Android 내부에 설치하고, 설치된 앱을 host UI 또는 guest launcher에서 실행할 수 있는 **Usable VM** 상태를 만드는 것이다.

이 단계가 완료된 시점의 판정 기준은 단순 staging PoC가 아니다. Stage 06 완료는 다음 사용자 흐름이 end-to-end로 동작한다는 뜻이다.

```text
APK 선택
  -> guest staging으로 안전하게 복사
  -> guest 내부 설치
  -> 설치된 앱 목록 표시
  -> 앱 실행
  -> 앱 UI가 framebuffer에 표시
  -> touch/key 입력으로 기본 조작
  -> 앱 삭제와 데이터 초기화
```

## Stage 05 Readiness Baseline

이 섹션은 기존 `pre_stage6_readiness.md`의 내용을 Stage 06 문서로 흡수한 것이다. Stage 06 작업은 아래 기준이 충족된 상태를 출발점으로 삼는다.

### 결론

Stage 06으로 진행할 수 있다.

Stage 05 MVP 기준의 잔여 blocker는 없다. 현재 구현은 software framebuffer 기반으로 guest frame 출력, input bridge, audio output, graphics device stub, lifecycle stress를 통과한 상태로 본다.

### Git 상태

- `main` branch가 `origin/main`과 동기화되어 있어야 한다.
- 검증 시점에 커밋되지 않은 변경 사항이 없어야 한다.

### Gradle 검증

다음 작업이 통과해야 Stage 06 진입 가능으로 본다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

최근 기준으로 요구하는 결과:

- `BUILD SUCCESSFUL`
- debug/release native build 통과
- debug unit test 통과
- lintDebug 통과

### Emulator 진단

Stage 05 진단은 다음 결과를 만족해야 한다.

```text
STAGE5_RESULT passed=true graphics=true graphicsDevice=true graphicsAcceleration=true input=true audio=true lifecycle=true stress=true
```

세부 항목:

- `STAGE5_GRAPHICS_RESULT passed=true`
- `STAGE5_GRAPHICS_DEVICE_RESULT passed=true`
- `STAGE5_GRAPHICS_ACCELERATION_RESULT passed=true`
- `STAGE5_INPUT_RESULT passed=true`
- `STAGE5_AUDIO_RESULT passed=true`
- `STAGE5_LIFECYCLE_RESULT passed=true`
- `STAGE5_STRESS_RESULT passed=true`

Stage 04 회귀 진단은 다음 결과를 만족해야 한다.

```text
STAGE4_VFS_RESULT passed=true
```

### Stage 05 완료 항목

- Native render loop가 host `Surface`에 frame을 그릴 수 있다.
- `ANativeWindow` lock/unlock/post path가 동작한다.
- Surface detach 시 render thread가 종료된다.
- Guest framebuffer memory를 runtime이 보유한다.
- Render loop는 guest framebuffer source를 host Surface로 복사한다.
- Dirty rect가 기록된다.
- Resize와 rotation mapping이 진단된다.
- `/dev/graphics/fb0` write는 guest framebuffer source로 유지된다.
- `/dev/gralloc` 및 `/dev/graphics/gralloc` stub이 allocation 요청을 기록한다.
- `/dev/hwcomposer` 및 `/dev/graphics/hwcomposer0` stub이 compose 요청을 기록한다.
- Hwcomposer compose는 host framebuffer bridge에 commit된다.
- Touch event가 guest 좌표계로 변환된다.
- Multi-touch pointer id가 기록된다.
- Key event가 guest input queue에 전달된다.
- Input queue reset이 lifecycle/stress 진단에 포함된다.
- AAudio 기반 test tone output path가 동작한다.
- Mute/unmute 상태가 진단된다.
- Stage 05 MVP는 `graphicsAccelerationMode=software_framebuffer`로 고정한다.
- `glesPassthroughReady=false`, `virglReady=false`, `venusReady=false`를 명시한다.

### Stage 05 Non-blocker Backlog

다음 작업은 Stage 06 진입을 막지 않는다.

- 실제 Android HAL ABI 수준의 `gralloc`/`hwcomposer` 호환성 구현
- 단일 buffer/layer stub을 넘어선 실제 buffer queue 구현
- Multi-layer composition
- 지속형 audio ring buffer와 장기 audio thread lifecycle 고도화
- GLES passthrough 실제 구현
- Virgl/Venus/Vulkan 실제 GPU 가속 구현
- 장시간 soak test
- 실제 화면 육안 QA
- 다양한 해상도와 density 장치 검증

## 원칙

- APK를 host Android에 설치하지 않는다.
- APK는 guest rootfs의 staging directory로 전달한다.
- Stage 06 완료 판정은 Usable VM 기준으로 한다.
- Staging/import만 동작하는 상태는 중간 체크포인트이며 Stage 06 완료가 아니다.
- 설치 처리는 최종적으로 guest PackageManager 또는 PackageManager-compatible runtime installer가 담당한다.
- Runtime installer를 중간 구현으로 사용할 수 있지만, Stage 06 완료 시점에는 설치, 목록, 실행, 삭제, 데이터 초기화가 일관된 package state로 유지되어야 한다.
- Host는 progress, result, package list만 표시한다.
- Host dangerous permission은 APK 설치/실행 기본 흐름에서 요구하지 않는다.
- 단일 APK를 Stage 06 범위로 삼고, split APK 계열은 후속 roadmap으로 둔다.

## 선행 조건

완전한 설치/실행까지 가려면 다음이 먼저 동작해야 한다.

- Stage 03 rootfs 설치와 health check
- Stage 04 guest process 실행
- Stage 04 binder/service manager 최소 path
- Stage 04 guest PackageManager 또는 PackageManager-compatible runtime installer
- Stage 05 guest frame 출력
- Stage 05 touch/key input bridge

## User Flow

```text
User selects APK
  -> Storage Access Framework URI
  -> copy to files/avm/instances/vm1/staging/
  -> calculate sha256
  -> write import metadata JSON
  -> call native importApk()
  -> guest installer receives staged path
  -> package installed into guest /data/app
  -> package state updated
  -> package list refreshed in host UI
  -> user launches package
  -> guest ActivityManager starts launcher activity
  -> guest app window renders to framebuffer
```

## 파일 구조

```text
files/avm/instances/vm1/
├─ staging/
│  ├─ import_0001.apk
│  ├─ import_0001.json
│  └─ tmp/
├─ export/
├─ rootfs/
│  └─ data/
│     ├─ app/
│     ├─ data/
│     ├─ dalvik-cache/
│     └─ system/
│        └─ packages.xml
├─ runtime/
│  └─ package-index.json
└─ logs/
   ├─ native_runtime.log
   └─ package_install.log
```

## Host Kotlin API

```kotlin
data class ApkImportRequest(
    val instanceId: String,
    val sourceUri: Uri,
    val displayName: String?,
    val sizeLimitBytes: Long
)

data class ApkImportProgress(
    val phase: ApkImportPhase,
    val bytesCopied: Long,
    val totalBytes: Long?,
    val message: String
)

enum class ApkImportPhase {
    OPEN_SOURCE,
    COPY_TO_STAGING,
    HASH,
    WRITE_METADATA,
    IMPORT_TO_GUEST,
    REFRESH_PACKAGES,
    DONE
}

data class ApkImportResult(
    val success: Boolean,
    val stagedPath: String?,
    val packageName: String?,
    val errorCode: String?,
    val message: String
)

data class GuestPackageInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val iconPath: String?,
    val enabled: Boolean,
    val launchable: Boolean,
    val installedPath: String
)
```

## Native API

```kotlin
external fun importApk(instanceId: String, stagedApkPath: String): Int
external fun uninstallPackage(instanceId: String, packageName: String): Int
external fun listPackages(instanceId: String): String
external fun launchPackage(instanceId: String, packageName: String): Int
external fun clearPackageData(instanceId: String, packageName: String): Int
external fun getPackageOperationStatus(instanceId: String): String
```

`listPackages()`와 `getPackageOperationStatus()`는 JNI boundary를 단순하게 유지하기 위해 JSON string을 반환하고, Kotlin wrapper가 typed model로 변환한다.

## 6.1 Readiness Lock

### 목표

Stage 06 작업 중 Stage 04/05 기능이 퇴행하지 않도록 baseline을 고정한다.

### 구현 작업

- Stage 04 VFS/runtime diagnostics를 반복 실행 가능하게 유지한다.
- Stage 05 graphics/input/audio/lifecycle diagnostics를 반복 실행 가능하게 유지한다.
- Stage 06 작업 전후로 Gradle verification command를 실행한다.
- Stage 06 package path가 software framebuffer/input path와 충돌하지 않는지 확인한다.

### 완료 기준

- Stage 04/05 diagnostics가 Stage 06 변경 후에도 통과한다.
- Stage 06 구현 중 `graphicsAccelerationMode=software_framebuffer` boundary가 유지된다.
- 앱 설치/실행 작업이 host broad permission을 추가하지 않는다.

## 6.2 APK Staging

### 목표

SAF URI로 선택한 APK를 instance staging directory에 안전하게 복사하고 metadata를 남긴다.

### 구현 작업

- SAF URI open
- MIME/type/name validation
- 단일 APK 확장자와 magic/header validation
- size limit check
- `tmp/import_XXXX.tmp`로 stream copy
- SHA-256 계산
- metadata JSON 작성
- `import_XXXX.apk`와 `import_XXXX.json`으로 atomic rename
- 실패 시 partial file 삭제

### metadata 예시

```json
{
  "sourceName": "example.apk",
  "stagedPath": "/absolute/path/staging/import_0001.apk",
  "size": 12345678,
  "sha256": "",
  "createdAt": "2026-04-28T00:00:00Z"
}
```

### 완료 기준

- 대용량 APK copy 중 progress가 표시된다.
- Copy 실패 시 partial file이 남지 않는다.
- Invalid APK는 install 단계로 넘어가지 않는다.
- Staged APK와 metadata JSON이 함께 생성된다.
- Native runtime 또는 guest shell이 staged APK path를 읽을 수 있다.

## 6.3 Guest Installer Integration

### 목표

Staged APK를 guest package state에 설치한다.

### 권장 접근

가능하면 guest PackageManager path를 사용한다.

```text
pm install /data/local/tmp/import.apk
```

장점:

- Android package state가 자연스럽게 유지된다.
- Permission, dexopt, data directory 처리가 framework에 맡겨진다.
- App launch와 uninstall 흐름이 Android semantics에 가까워진다.

초기 구현에서 runtime installer를 사용할 수 있지만, Stage 06 완료 기준은 다음 조건을 만족해야 한다.

- `/data/app` 또는 equivalent installed path가 생성된다.
- `/data/data/{packageName}` 또는 equivalent data path가 생성된다.
- Package index가 VM restart 후에도 유지된다.
- Launcher activity resolve가 가능하다.
- Uninstall과 clear data가 같은 package state를 갱신한다.

### 구현 작업

- APK manifest parse
- package name, version, label, launcher activity 추출
- ABI/density compatibility check
- duplicate install policy 정의
- install transaction directory 사용
- 실패 시 rollback
- package state persistence
- install log 작성

### 완료 기준

- 정상 단일 APK가 guest 내부에 설치된다.
- Invalid APK는 명확한 error로 실패한다.
- Duplicate install은 update 또는 reject 중 정책대로 처리된다.
- 설치 실패 시 `/data/app`, package index, metadata가 partial 상태로 남지 않는다.

## 6.4 Package List and Host Launcher

### 목표

설치된 guest app 목록을 host UI에서 확인하고 실행할 수 있게 한다.

### 수집 정보

- Package name
- App label
- Version
- Icon
- Launcher activity
- Installed path
- Enabled state
- Launchable state

### Host 표시

- Grid/list launcher
- Search
- Launch
- Uninstall
- Clear data
- App info
- Install result and error display

### 완료 기준

- 설치 직후 package list가 갱신된다.
- VM restart 후에도 package list가 유지된다.
- Launchable app과 non-launchable package를 구분한다.
- Icon 또는 fallback icon이 표시된다.

## 6.5 App Launch

### 목표

Host UI에서 package를 선택하면 guest app process/window가 시작되고, 앱 UI가 software framebuffer에 표시된다.

### Flow

```text
host launchPackage(packageName)
  -> native runtime
  -> launcher activity resolve
  -> guest ActivityManager start activity
  -> guest app process launch
  -> guest window attach
  -> framebuffer commit
  -> host Surface render
```

### 구현 작업

- Launcher activity resolve
- ActivityManager transaction 또는 runtime-compatible launch command
- App process creation
- App data directory setup
- Window attach
- Framebuffer commit 확인
- Back key/touch event routing 확인
- Launch failure reason reporting

### 완료 기준

- 설치된 앱을 host UI에서 실행할 수 있다.
- 앱 첫 화면이 guest framebuffer에 표시된다.
- Tap과 drag가 앱 좌표계로 전달된다.
- Back key가 guest app에 전달된다.
- Launch 실패 시 host UI와 log에 원인이 남는다.

## 6.6 Package Management

### 목표

설치된 앱을 실제 사용 가능한 수준으로 관리한다.

### 구현 작업

- Uninstall
- Clear package data
- Enable/disable package state
- Install/update conflict handling
- Package list persistence
- Restart 후 package state reload
- Package operation audit log

### 완료 기준

- 앱 삭제 후 package list와 guest data path가 갱신된다.
- Clear data 후 app data directory가 초기화된다.
- VM restart 후 installed/uninstalled state가 유지된다.
- 중복 설치, 삭제 실패, clear data 실패가 명확한 error로 반환된다.

## 6.7 File Import/Export Foundation

APK 외 파일 import/export도 같은 staging 구조를 사용한다. Stage 06의 필수 완료 범위는 APK import/install/launch이지만, import/export directory와 metadata 형식은 여기서 함께 맞춘다.

Import:

```text
host file URI
  -> staging
  -> guest /sdcard/Download or selected path
```

Export:

```text
guest path
  -> host export directory
  -> SAF create document
```

### 완료 기준

- APK staging metadata와 일반 file staging metadata가 충돌하지 않는다.
- Export directory가 instance boundary 밖으로 escape하지 않는다.
- 파일 import/export는 host broad storage permission 없이 SAF 기반으로 설계된다.

## 6.8 Verification Gate

### 자동 테스트

- APK staging metadata test
- SHA-256 mismatch test
- Invalid APK test
- Partial copy cleanup test
- Duplicate install test
- Package index persistence test
- Uninstall test
- Clear data test
- Launch command failure test
- Stage 04 VFS regression test
- Stage 05 graphics/input/audio regression test

### Emulator 진단

Stage 06 완료 전에는 다음 형태의 diagnostics를 추가한다.

```text
STAGE6_RESULT passed=true staging=true install=true packages=true launch=true management=true regressions=true
```

세부 항목:

- `STAGE6_STAGING_RESULT passed=true`
- `STAGE6_INSTALL_RESULT passed=true`
- `STAGE6_PACKAGE_LIST_RESULT passed=true`
- `STAGE6_LAUNCH_RESULT passed=true`
- `STAGE6_PACKAGE_MANAGEMENT_RESULT passed=true`
- `STAGE6_REGRESSION_RESULT passed=true stage4=true stage5=true`

## Stage 06 완료 기준

Stage 06은 다음 **Usable VM 완료 기준**을 모두 만족할 때만 완료로 본다.

- Host에서 APK를 선택해 guest staging으로 복사한다.
- Staged APK의 checksum과 metadata를 기록한다.
- Invalid APK와 copy 실패를 명확한 error로 반환한다.
- Guest 내부에 APK가 설치된다.
- 설치 실패 시 partial package state가 남지 않는다.
- 설치된 앱 목록이 host UI에 표시된다.
- Package list가 VM restart 후에도 유지된다.
- 앱을 실행하면 guest 화면에 앱 UI가 표시된다.
- Touch/key input으로 앱 기본 조작이 가능하다.
- 앱 삭제와 데이터 초기화가 동작한다.
- Stage 04 VFS/runtime diagnostics가 퇴행하지 않는다.
- Stage 05 graphics/input/audio/lifecycle diagnostics가 퇴행하지 않는다.
- APK 설치/실행 기본 흐름에서 host dangerous permission을 요구하지 않는다.

## Stage 06 이후 Roadmap

Stage 06에서는 단일 APK만 지원한다.

후속:

- `.apks`
- `.xapk`
- Split config APK
- ABI split
- Density split
- OBB import
- Guest PackageManager full compatibility hardening
- Multi-window guest launch
- Long-running app lifecycle soak test

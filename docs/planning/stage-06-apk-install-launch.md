# Stage 06 - APK Install and Launch

## 목적

host에서 선택한 APK를 guest Android 내부에 설치하고, guest launcher 또는 host UI에서 실행할 수 있게 한다.

## 원칙

- APK를 host Android에 설치하지 않는다.
- APK는 guest rootfs의 staging directory로 전달한다.
- 설치 처리는 guest PackageManager 또는 runtime installer가 담당한다.
- host는 progress, result, package list만 표시한다.

## 선행 조건

이 단계는 Stage 01의 첫 MVP gate가 아니라, VM을 실제로 사용할 수 있게 만드는 usable milestone이다.

완전한 설치/실행까지 가려면 다음이 먼저 동작해야 한다.

- Stage 03 rootfs 설치와 health check
- Stage 04 guest process 실행
- Stage 04 binder/service manager 최소 path
- Stage 04 guest PackageManager 또는 임시 runtime installer
- Stage 05 guest frame 출력
- Stage 05 touch/key input bridge

## Host Flow

```text
User selects APK
  -> Storage Access Framework URI
  -> copy to files/instances/vm1/staging/
  -> calculate sha256
  -> call native importApk()
  -> guest installer receives path
  -> package installed into guest /data/app
  -> package list refreshed
```

## 파일 구조

```text
files/instances/vm1/
├─ staging/
│  ├─ import_0001.apk
│  └─ import_0001.json
├─ export/
├─ rootfs/
│  └─ data/
│     ├─ app/
│     ├─ data/
│     └─ system/packages.xml
└─ logs/
```

## Host Kotlin API

```kotlin
data class ApkImportRequest(
    val instanceId: Int,
    val sourceUri: Uri,
    val displayName: String?
)

data class ApkImportResult(
    val success: Boolean,
    val packageName: String?,
    val errorCode: Int?,
    val message: String?
)

data class GuestPackageInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val iconPath: String?,
    val enabled: Boolean,
    val launchable: Boolean
)
```

## Native API

```kotlin
external fun importApk(instanceId: Int, stagedApkPath: String): Int
external fun uninstallPackage(instanceId: Int, packageName: String): Int
external fun listPackages(instanceId: Int): Array<GuestPackageInfo>
external fun launchPackage(instanceId: Int, packageName: String): Int
external fun clearPackageData(instanceId: Int, packageName: String): Int
```

## 6.1 APK Staging

### 구현 작업

- SAF URI open
- stream copy
- size limit check
- sha256
- metadata JSON 작성
- temporary file atomic rename

### metadata 예시

```json
{
  "sourceName": "example.apk",
  "stagedPath": "/absolute/path/staging/import_0001.apk",
  "size": 12345678,
  "sha256": "",
  "createdAt": ""
}
```

### 완료 기준

- 대용량 APK copy 중 progress 표시
- copy 실패 시 partial file 삭제
- staging file을 native runtime이 읽을 수 있음

## 6.2 Guest Installer

### 접근 1: Guest PackageManager 사용

가능하다면 guest 내부에서 다음 흐름을 사용한다.

```text
pm install /data/local/tmp/import.apk
```

장점:

- Android package state가 자연스럽게 유지된다.
- permission, dexopt, data directory 처리가 framework에 맡겨진다.

단점:

- guest shell/process/binder가 먼저 충분히 동작해야 한다.

### 접근 2: Runtime Package Installer

초기 PoC에서는 runtime이 APK를 직접 분석하고 guest package database를 갱신할 수 있다.

장점:

- full PackageManager boot 이전에도 설치 실험 가능

단점:

- Android package semantics를 많이 재구현해야 한다.
- 장기적으로 유지보수가 어렵다.

권장:

- PoC는 runtime installer로 시작할 수 있지만, MVP usable 단계에서는 guest PackageManager로 전환한다.

## 6.3 Package List

### 수집 정보

- package name
- app label
- version
- icon
- launcher activity
- installed path
- enabled state

### Host 표시

- grid/list launcher
- search
- launch
- uninstall
- clear data
- app info

## 6.4 App Launch

### Flow

```text
host launchPackage(packageName)
  -> native runtime
  -> guest ActivityManager start activity
  -> guest app process launch
  -> guest window rendered to framebuffer
```

### 최소 구현

- launcher activity resolve
- ActivityManager transaction
- app process creation
- window attach

## 6.5 Split APK Roadmap

MVP에서는 단일 APK만 지원한다.

후속:

- `.apks`
- `.xapk`
- split config APK
- ABI split
- density split
- OBB import

## 6.6 File Import/Export

APK 외 파일 import/export도 같은 staging 구조를 사용한다.

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

## 테스트

- small APK import test
- large APK import test
- invalid APK test
- duplicate install test
- uninstall test
- clear data test
- package list persistence test
- launch installed app test

## 완료 기준

### PoC 완료 기준

- host에서 APK를 선택해 guest staging으로 복사한다.
- staged APK의 checksum과 metadata를 기록한다.
- native runtime 또는 guest shell이 staged APK path를 읽을 수 있다.
- invalid APK와 copy 실패를 명확한 error로 반환한다.

### Usable VM 완료 기준

- host에서 APK를 선택해 guest staging으로 복사한다.
- guest 내부에 APK가 설치된다.
- 설치된 앱 목록이 host UI에 표시된다.
- 앱을 실행하면 guest 화면에 앱 UI가 표시된다.
- 삭제와 데이터 초기화가 동작한다.

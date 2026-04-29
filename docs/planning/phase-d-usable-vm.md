# Phase D — Usable VM

> 본 문서는 `docs/planning/future-roadmap.md` 의 Phase D 절을 step 단위로 풀어 쓴 detailed plan 이다.
> 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 6 단계 + 7 단계 bridge 항목 + Phase D.

## 1. 진입 조건

- Phase C 종료 게이트 (`STAGE_PHASE_C_RESULT passed=true ...`) 통과.
- system_server / SurfaceFlinger 가 부팅되고 host Surface 에 첫 frame 표출.
- guest binder transport / ashmem / property / zygote 모두 동작.

## 2. 핵심 산출물

- 사용자가 `MainActivity` 에서 import 한 일반 APK 의 `Activity.onCreate` 가 진입하여 launcher 또는 그 앱이 화면에 그려진다.
- Stage 7 의 모든 bridge (clipboard / location / audio output / vibration / network / device profile / camera / microphone) 가 시뮬레이션 hook 이 아닌 **진짜 guest API** 와 연결.
- Camera / Microphone 권한이 사용 시점에만 요청되고, off 상태에서는 host API 를 호출하지 않는다.

```text
STAGE_PHASE_D_RESULT passed=true pms=true launcher=true app_run=true bridges=true camera=true mic=true network=true file=true stage_phase_c=true
```

## 3. 진척 현황 요약

| 영역 | 상태 | 참고 |
|---|---|---|
| Host APK install pipeline | ✅ 메타데이터만 | `ApkStager` / `ApkInstaller` / `PackageOperations` / `LauncherActivityResolver` |
| 진짜 PMS install | ❌ | Phase D.1 |
| Launcher 부팅 | ❌ | Phase D.2 |
| Real dex 실행 | ❌ | Phase D.3 (ART runtime hosting) |
| Bridge 시뮬레이션 → 실 연동 | ❌ | Phase D.4 (Clipboard/Audio/Vibration/Network) |
| CameraX bridge | ❌ stub | `bridge/UnsupportedMediaBridge.kt` |
| Microphone PCM bridge | ❌ stub | `bridge/UnsupportedMediaBridge.kt` |
| VpnService isolation | ❌ stub | `bridge/NetworkBridge.kt` enable/disable only |
| File import/export | ⚠️ 부분 | `apk/FileExporter.kt`, `apk/FileStager.kt` (APK 만) |

## 4. 잔여 Step 일람

| Step | 제목 | 의존 | 결과물 |
|---|---|---|---|
| D.1 | PackageManagerService 와 실 install 연동 | C.5 | `pm list packages` 에 등장 |
| D.2 | Launcher 부팅 | D.1 | Launcher3 가 SurfaceFlinger 위에 그려짐 |
| D.3 | Real APK dex 실행 | D.1, C.4, C.5 | `Activity.onCreate` 진입 |
| D.4 | Bridge 실 연동 (Clipboard/Audio/Vibration/Network) | D.3 | 진짜 guest framework 와 연결 |
| D.5 | Camera bridge 실 구현 | D.4 | YUV420 frame 전달 |
| D.6 | Microphone bridge 실 구현 | D.4 | AudioRecord PCM 전달 |
| D.7 | VpnService 기반 per-instance network isolation | D.4 | virtual interface egress 분리 |
| D.8 | File import / export, SAF | D.4 | host ↔ guest 일반 파일 전송 |
| D.9 | Operational maturity (crash report / boot rollback / perf budget / data backup) | D.3, D.4, D.8 | guest 안정성·복원성·성능 가드 |
| D.10 | Phase D 종합 회귀 receiver | D.9 | `STAGE_PHASE_D_RESULT` 라인 |

---

## Step D.1 — PackageManagerService 와 실 install 연동

### 5.1.1 Background

현재 `ApkInstallPipeline` 은 staging → AXML 파싱 → host filesystem 에 metadata 작성 (`<instance>/data/app/<package>/base.apk` + `package_index.json`) 으로 끝난다. PMS 는 이걸 보지 않는다. Phase D 에서는 host 측 staging 결과를 PMS binder transaction 으로 install 하도록 한다.

### 5.1.2 목표

- 사용자가 SAF 로 import 한 APK 가 guest PMS 의 `installPackage` transaction 까지 도달.
- guest 안에서 `pm list packages` 출력에 패키지가 나타남.
- 기존 `PackageOperations` 의 host-side index 는 PMS 결과로 동기화 (single source of truth = guest PMS).

### 5.1.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/apk/ApkInstaller.kt` | guest PMS 호출 경로 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/apk/PackageOperations.kt` | guest PMS 결과로 sync |
| `app/src/main/cpp/binder/...` | PMS interface descriptor (`android.content.pm.IPackageManager`) helper |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmNativeBridge.kt` | `installApkViaPms`, `listGuestPackages` external |
| `app/src/main/cpp/jni/vm_native_bridge.cpp` | 위 JNI 구현 |
| `app/src/main/java/dev/jongwoo/androidvm/apk/PackageIndex.kt` | guest sync 결과 머지 |

### 5.1.4 세부 작업

#### D.1.a JNI surface 추가

```kotlin
external fun installApkViaPms(
    instanceId: String,
    stagedApkPath: String,
    flags: Int,        // INSTALL_REPLACE_EXISTING etc.
): String              // JSON: { result, packageName, message }

external fun listGuestPackages(instanceId: String): String
external fun launchGuestActivity(instanceId: String, packageName: String, activity: String): Int
```

#### D.1.b PMS binder transaction

`IPackageManager` 의 transaction code 매핑:

| code | name |
|---|---|
| 0x00000001 | getPackageInfo |
| 0x00000003 | getApplicationInfo |
| 0x00000005 | queryIntentActivities |
| 0x0000007D | installPackage (deprecated, use installPackageWithVerificationAndEncryption) |

API level 별로 transaction code 가 변경되므로, 7.1.2 (API 25) 에 맞춘 stable wrapper 를 한 번 만들고 Phase E.3/E.4 에서 다른 API level 호환을 추가.

#### D.1.c IObserver callback

`installPackage(observer)` 의 observer 가 binder callback 으로 결과를 받음. 우리 binder transport 가 BR_TRANSACTION 을 정상 라우팅 → host Kotlin 측 `coroutineScope.async` 로 결과 await.

#### D.1.d Sync 정책

- 진짜 source of truth = guest PMS.
- host index (`package_index.json`) 는 cache 역할만, 첫 부팅 후 `listGuestPackages()` 결과로 강제 갱신.

#### D.1.e 회귀 보존

- Stage 6 의 `Stage6DiagnosticsReceiver` 가 만든 synthetic stage6 APK 는 Phase D 시점에는 `installApkViaPms` 로 라우팅되도록 옵션 추가. 단, fallback 으로 기존 메타데이터 경로도 유지 (재현성).

### 5.1.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_PMS passed=true install=ok pms_listed=true package=<name>`.
- guest shell 회귀: `pm list packages | grep <name>` 가 hit.

### 5.1.6 위험

- PMS 의 install path 가 dex2oat 를 호출하면 ART (Phase D.3) 가 안정적이지 않을 때 abort. 우선 `--skip-dexopt` 옵션 사용.
- 7.1.2 PMS 가 SELinux context 를 요구. permissive 로 처리.

---

## Step D.2 — Launcher 부팅

### 5.2.1 Background

system_server 가 부팅됐고 PMS install 이 동작하면, Launcher (`com.android.launcher3` 또는 더 minimal 한 launcher) 를 미리 install 한 뒤 `am start` 로 진입.

### 5.2.2 목표

- guest 측 `am start -n com.android.launcher3/com.android.launcher3.Launcher` 가 동작.
- launcher 가 PMS `queryIntentActivities(MAIN/LAUNCHER)` 로 일반 앱 목록을 가져와 그리드 표시.
- host Surface 에 Launcher 가 그려진다.

### 5.2.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `tools/create_debug_guest_fixture.sh` | minimal launcher APK 포함 |
| `app/src/main/java/dev/jongwoo/androidvm/apk/PackageOperations.kt` | 첫 부팅 시 launcher 자동 install + start |
| `app/src/main/cpp/binder/service_manager.cpp` | `am start` 가 사용하는 ActivityManager transaction |

### 5.2.4 세부 작업

#### D.2.a Launcher binary 선택

옵션:

1. **AOSP Launcher3**: 무겁지만 표준. APK 수십 MB.
2. **minimal launcher**: AOSP 의 `Trebuchet` fork 또는 자체 minimal launcher. 약 2MB.

권장: 우선 minimal launcher 로 시작, 안정화 후 Launcher3 도 옵션.

#### D.2.b Auto-install on first boot

- `Stage6DiagnosticsReceiver` 와 비슷한 패턴: ROM 첫 install 후 launcher APK 도 auto install.
- `<instance>/data/app/com.android.launcher.minimal/base.apk` 위치.

#### D.2.c am start 진입

- `am start -n <package>/<activity>` 는 ActivityManager 의 `startActivity` binder transaction.
- Phase C.5 에서 등록된 `activity` service 가 응답.

### 5.2.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_LAUNCHER passed=true activities>=1 home_visible=true window_focused=true`.
- 수동: 화면에 launcher grid (앱 1개라도) 표시.

### 5.2.6 위험

- launcher 가 `Wallpaper`, `Notification`, `RecentsTaskList` 등을 요구. 의존 service 누락 시 crash. 부팅 시 의존 service 모두 등록 확인.

---

## Step D.3 — Real APK dex 실행

### 5.3.1 Background

D.1 까지는 PMS 가 패키지를 인식하지만, 실제 dex 코드가 실행되어 `Activity.onCreate` 까지 진입하려면 ART runtime 이 dex 를 interpret 또는 AOT compile 해서 실행해야 한다.

### 5.3.2 목표

- 사용자가 import 한 일반 APK (예: `org.fdroid.fdroid` 의 simple Calculator) 의 launcher activity `onCreate` 진입.
- crash 없이 다음 frame 표출.

### 5.3.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/loader/guest_process.cpp` | ART runtime 로드 chain |
| `app/src/main/cpp/property/property_service.cpp` | `dalvik.vm.dex2oat-flags` 설정 |
| `tools/create_debug_guest_fixture.sh` | `system/lib64/libart.so` 등 포함 |

### 5.3.4 세부 작업

#### D.3.a ART runtime stable load

- Phase C.4 에서 zygote 가 libart 를 dlopen 했지만, 첫 dex execution 까지 가지 못했을 가능성.
- 의존 라이브러리 (`libdexfile.so`, `libprofile.so`, `libartbase.so`) 까지 모두 fixture 포함.

#### D.3.b dex2oat 정책

- 옵션 A: `--compiler-filter=quicken` (interpret-only with quickening).
- 옵션 B: `--compiler-filter=verify` (verify only, no compile).
- 옵션 C: 완전 disable (interpret 모드).

권장: 처음에는 옵션 C 로 시작, 안정 시 옵션 A 로 격상.

#### D.3.c Dalvik cache 위치

- `<instance>/data/dalvik-cache/arm64/` 에 oat 파일.
- 첫 실행 시 dex2oat 가 여기에 쓴다. 권한: PMS 와 동일 user (system).

#### D.3.d Activity launch

- `am start` → ActivityManager → `ActivityThread.handleLaunchActivity` → Activity 의 `onCreate`.
- 우리는 ActivityThread 까지 진입하면 됨. 이후는 진짜 Android framework 가 진행.

### 5.3.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_APP_RUN passed=true package=<name> activity_oncreate=true frame_count>=1 crash_count=0`.
- logcat: `ActivityThread: Performing launch of ActivityRecord{...}` + `<package>: onCreate`.

### 5.3.6 위험

- **CPU detection mismatch**: ART 가 AOT compile 시 host CPU 의 vendor instruction 을 비트로 굳히면 다른 host 에서 동작 불가. host CPU detection 결과를 dex2oat 에 정확히 전달.
- **단일 process heap 단편화**: system_server 와 ART 가 같은 process 안에 살면 native heap 단편화 가능. monitoring 필요 (Phase D.9 perf 게이트와 연계).
- **host bionic ↔ guest libart symbol/TLS 충돌 (Critical, Phase B.3 의 위험과 동근)**: host 프로세스의 bionic 과 guest libart 가 같은 process 안에서 공존하면 다음 충돌 가능:
  - `pthread_setspecific` / `pthread_getspecific` 의 TLS slot 번호 충돌.
  - `malloc` 의 thread-local cache 가 두 libc 사이에서 cross-pollute.
  - `__cxa_atexit` 같은 weak global 의 순서 의존.
  완화: ART thread 가 진입할 때 `TPIDR_EL0` 를 guest TLS block 으로 swap 하는 trampoline 을 ART hosting 의 첫 entry 에 둔다 (Phase B.3 의 동일 mitigation 재사용).
- **ART version 명명 검증 필요**: 본 step 의 "ART runtime" 은 Android 7.1.2 의 libart.so (API 25). Phase E.3 (Android 10) 가 들어오면 별도 libart 가 필요하므로, 그때까지는 7.1.2 hard-pin.

---

## Step D.4 — Bridge 실 연동 (Clipboard / Audio / Vibration / Network)

### 5.4.1 Background

Phase D.3 까지 완료되면 진짜 guest framework 가 동작한다. Stage 7 에서 만든 bridge dispatcher / handler 들을 진짜 guest API 와 연결.

### 5.4.2 목표

- guest `ClipboardManager.setPrimaryClip` 이 호출되면 host `BridgeDispatcher` 로 라우팅 → policy 검사 → 통과 시 host `ClipboardManager` 갱신.
- guest 의 AudioFlinger PCM 출력이 `AudioOutputBridge` 를 거쳐 host AudioTrack 으로 출력.
- guest `Vibrator.vibrate` → host `VibrationBridge`.
- guest 의 `ConnectivityManager` / socket syscall 이 `NetworkBridge` 정책에 따라 동작 (off 시 ENETUNREACH).

### 5.4.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/bridge/ClipboardBridge.kt` | guest binder hook |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/AudioOutputBridge.kt` | AudioFlinger 와 연결 |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/VibrationBridge.kt` | guest Vibrator service 와 연결 |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/NetworkBridge.kt` | socket syscall 게이팅 |
| `app/src/main/cpp/binder/...` | `clipboard`, `vibrator`, `audio` 서비스 binder |
| `app/src/main/cpp/syscall/socket.cpp` | NetworkBridge 정책 적용 |

### 5.4.4 세부 작업

#### D.4.a Clipboard

- guest `IClipboard.setPrimaryClip` transaction 진입 시 host 의 `ClipboardBridge.guestToHost` 호출.
- host clipboard 변경 이벤트는 `ClipboardManager.OnPrimaryClipChangedListener` 를 통해 polling 또는 binder callback → guest ClipboardService 의 onClipChanged 트리거.

#### D.4.b Audio output

- AudioFlinger 의 `IAudioTrack` 이 mmap 한 ring buffer 를 host AAudio output 으로 redirect.
- volume / mute 토글 적용.

#### D.4.c Vibration

- guest `IVibratorService.vibrate` → host `VibrationBridge.vibrate(durationMs)`.
- duration cap 은 이미 Stage 7 에서 적용.

#### D.4.d Network

- guest socket syscalls (`socket`, `connect`, `sendto`, ...) 가 NetworkBridge 의 enabled 검사.
- disabled 인 경우 `ENETUNREACH` 또는 `EACCES`.
- 초기 mode: host NAT (host 의 INTERNET 권한 통한 직접 통신).

### 5.4.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_BRIDGE passed=true clipboard=ok audio=ok vibration=ok network=ok`.
- 수동: clipboard mode = host_to_guest 시 host 에서 복사한 텍스트가 guest 텍스트필드에 paste.

### 5.4.6 위험

- ClipboardListener 가 너무 자주 fire 시 audit log polluation. throttle 필요.
- AudioFlinger ring buffer 의 latency 가 host AAudio 와 어긋나면 underrun. xrun counter 노출.

---

## Step D.5 — Camera bridge 실 구현

### 5.5.1 Background

Stage 7 의 `UnsupportedMediaBridge.CAMERA` 를 실제 CameraX 기반 frame 전달로 교체.

### 5.5.2 목표

- bridge 가 ENABLED 모드일 때만 host `CAMERA` permission 을 사용 시점에 요청.
- CameraX `ImageAnalysis` 의 YUV_420_888 frame 을 guest `/dev/video0` virtual node 로 전달.
- guest CameraService 가 이 frame 을 카메라 HAL 으로 인식.

### 5.5.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/bridge/CameraBridge.kt` (NEW) | core handler |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/UnsupportedMediaBridge.kt` | CAMERA 케이스 제거 |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/Stage7BridgeScope.kt` | `CAMERA` → SUPPORTED |
| `app/src/main/AndroidManifest.xml` | `CAMERA` permission 선언 |
| `app/src/main/cpp/device/camera_device.{h,cpp}` (NEW) | guest camera HAL stub |
| `app/src/test/java/.../bridge/CameraBridgeTest.kt` (NEW) | 단위 테스트 |

### 5.5.4 세부 작업

#### D.5.a Manifest 변경

```xml
<uses-permission android:name="android.permission.CAMERA" />
```

`ManifestPermissionGuardTest` 의 forbidden list 에서 CAMERA 는 이미 빠져 있지만, Stage 7 MVP 의 "선언하지 않음" 가드를 풀어야 함. 가드 테스트 동시 갱신.

#### D.5.b CameraX use case

```kotlin
class CameraBridge(
    private val policyStore: BridgePolicyStore,
    private val auditLog: BridgeAuditLog,
    private val gateway: PermissionRequestGateway,
    private val cameraProvider: CameraXFrameSource,
) : BridgeHandler { ... }
```

- 첫 frame 요청 시 `gateway.request("android.permission.CAMERA", reason)`.
- `ImageAnalysis.Analyzer` 가 frame 도착 시 `cameraDevice.pushFrame(yuvBytes)` 호출.

#### D.5.c Guest camera HAL

- `/dev/video0` virtual node 에 YUV byte stream 을 큐잉.
- guest CameraService 가 V4L2 ioctl 시 host 가 fixed metadata 응답 (resolution, format).

### 5.5.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_CAMERA passed=true permission_flow=on_use frame_delivered>0 frame_format=YUV_420_888`.
- 수동: 카메라 앱이 preview 가 보임.

### 5.5.6 위험

- `CAMERA` permission 이 manifest 에 있으면 Play Store 정책상 카메라 사용 앱으로 분류. 명시적 documentation 필요.

---

## Step D.6 — Microphone bridge 실 구현

### 5.6.1 Background

D.5 와 동일 패턴, 입력 PCM stream.

### 5.6.2 목표

- bridge ENABLED 시 `RECORD_AUDIO` 권한 사용 시점 요청.
- AudioRecord ring buffer 의 PCM 을 guest audio input HAL 로 전달.
- sample rate conversion (host 48 kHz ↔ guest 16 kHz) 처리.

### 5.6.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/bridge/MicrophoneBridge.kt` (NEW) | core |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/UnsupportedMediaBridge.kt` | MICROPHONE 케이스 제거 |
| `app/src/main/AndroidManifest.xml` | `RECORD_AUDIO` permission |
| `app/src/main/cpp/device/audio_input_device.{h,cpp}` (NEW) | guest audio input HAL stub |
| `app/src/test/java/.../bridge/MicrophoneBridgeTest.kt` (NEW) | 단위 테스트 |

### 5.6.4 세부 작업

#### D.6.a AudioRecord 설정

```kotlin
val record = AudioRecord(
    MediaRecorder.AudioSource.MIC,
    48000,
    AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT,
    bufferSize,
)
```

- 첫 read 직전 `gateway.request("android.permission.RECORD_AUDIO", reason)`.

#### D.6.b Sample rate conversion

- 48000 → 16000 다운샘플 (3:1) 또는 22050 → guest 의 요구.
- linear interpolation 으로 충분 (음성 인식 수준).

### 5.6.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_MIC passed=true permission_flow=on_use frames>0 sample_rate_in=48000 sample_rate_out=16000`.

### 5.6.6 위험

- 동시에 host 의 다른 앱이 mic 를 사용 중이면 share 불가. error path 명확화.

---

## Step D.7 — VpnService 기반 per-instance network isolation

### 5.7.1 Background

Stage 7 NetworkBridge 는 enable/disable 만 지원. Phase D.7 에서는 모드를 확장:

- `host_nat` (기본).
- `socks5` (proxy 통해 라우팅).
- `vpn_isolated` (`VpnService` 로 가상 인터페이스 사용).

### 5.7.2 목표

- 인스턴스별로 트래픽이 호스트의 다른 앱과 섞이지 않게 가상 인터페이스 분리.
- `BIND_VPN_SERVICE` permission 을 사용 시점에만 요청.
- DNS proxy 옵션 추가.

### 5.7.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/bridge/NetworkBridge.kt` | mode 확장 |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/VmVpnService.kt` (NEW) | VpnService 상속 |
| `app/src/main/AndroidManifest.xml` | `<service>` + permission 선언 |
| `app/src/main/cpp/syscall/socket.cpp` | mode 별 라우팅 |

### 5.7.4 세부 작업

#### D.7.a VpnService.Builder

```kotlin
val builder = Builder()
    .addAddress("10.0.0.2", 24)
    .addRoute("0.0.0.0", 0)
    .addDnsServer("1.1.1.1")
    .setMtu(1500)
    .setSession("AVM-${instanceId}")
val tun = builder.establish()
```

`tun` fd 를 receive thread 가 `read()` 하면 IP 패킷 도착. `write()` 로 응답.

#### D.7.b SOCKS5 mode

- bridge 모드 = `socks5` 일 때 VPN 가상 인터페이스 대신 user-space SOCKS5 client 가 host network 로 라우팅.

#### D.7.c DNS proxy

- guest DNS query (port 53) 를 가로채 host 의 fixed resolver (`1.1.1.1`) 로 forward 옵션.

### 5.7.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_NETWORK_ISOLATION passed=true vpn_attached=true egress_mode=vpn_isolated dns_proxy=on`.

### 5.7.6 위험

- VpnService 는 사용자에게 강제 다이얼로그가 떠야 함. 첫 enable 시 명확한 reason UI 필요.

---

## Step D.8 — File import / export, SAF

### 5.8.1 Background

Stage 6 의 `FileExporter` / `FileStager` 는 APK 만 다룬다. 일반 파일 import/export (텍스트, 미디어 등) 가 가능해야 사용자 데이터 흐름이 닫힌다.

### 5.8.2 목표

- 사용자가 SAF 로 호스트 파일을 선택 → guest `/data/local/tmp/avm-import/` 에 staging.
- guest 안의 파일을 host 의 다운로드 디렉터리로 export.

### 5.8.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/apk/FileStager.kt` | 일반 파일도 받도록 확장 |
| `app/src/main/java/dev/jongwoo/androidvm/apk/FileExporter.kt` | 일반 파일 export |
| `app/src/main/java/dev/jongwoo/androidvm/ui/MainActivity.kt` | SAF launcher 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/bridge/FileBridge.kt` (NEW) | bridge 로 묶기 |

### 5.8.4 세부 작업

#### D.8.a Staging area 분리

- APK staging: `<instance>/staging/apk/`.
- 일반 파일 staging: `<instance>/staging/files/`.
- 두 path 가 섞이지 않도록 `FileStager` 가 destination 분기.

#### D.8.b Export 확인 다이얼로그

- 사용자가 export 요청 시 destination URI 와 파일 사이즈 확인 다이얼로그 표시.
- 한 번 승인된 destination directory 는 SAF persistable URI permission 으로 재사용.

### 5.8.5 검증 게이트

- 진단 라인: `STAGE_PHASE_D_FILE passed=true import=ok export=ok size_limit=enforced`.

### 5.8.6 위험

- import 사이즈 무제한이면 host 저장공간 가득. `ApkStager.DEFAULT_SIZE_LIMIT_BYTES` 와 동등한 제한 적용.

---

## Step D.9 — Operational maturity

### 5.9.1 Background

D.1~D.8 은 사용자가 일반 APK 를 실행하기까지의 *기능* 경로다. 그러나 진짜 사용성을 확보하려면 **부팅 실패 / 앱 crash / 성능 저하 / 데이터 분실** 4 가지 횡단 위험을 phase 종료 직전 한 번에 강화해야 한다. 검증 결과 "Crash 리포팅 / rollback / perf monitoring / 데이터 백업" 4 항목이 어느 phase 에도 명시 안 되어 있어 본 step 으로 통합한다.

### 5.9.2 목표

- guest 측 native crash / Java exception / ANR 을 host 가 수집해 사용자에게 노출.
- ROM 또는 launcher 부팅 실패 시 자동으로 직전 working state 로 rollback.
- Phase D 종료 게이트에 RSS / FPS / FD count 의 numeric budget 을 추가.
- 인스턴스 데이터 자동 백업 (snapshot 의 가벼운 변형) 을 SAF 로 export 가능.

### 5.9.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/core/crash_reporter.{h,cpp}` (NEW) | guest signal handler + minidump 작성 |
| `app/src/main/cpp/core/anr_watchdog.{h,cpp}` (NEW) | system_server main thread 의 watchdog timeout 감시 |
| `app/src/main/java/dev/jongwoo/androidvm/diag/CrashReportStore.kt` (NEW) | host 측 crash log persist |
| `app/src/main/java/dev/jongwoo/androidvm/diag/BootHealthMonitor.kt` (NEW) | 부팅 실패 감지 + auto rollback |
| `app/src/main/java/dev/jongwoo/androidvm/diag/PerfBudget.kt` (NEW) | RSS / FPS / FD 측정 + threshold |
| `app/src/main/java/dev/jongwoo/androidvm/storage/InstanceBackup.kt` (NEW) | 백업 / 복원 |
| `app/src/main/java/dev/jongwoo/androidvm/ui/MainActivity.kt` | crash log / backup UI |
| `app/src/test/java/.../diag/PerfBudgetTest.kt` (NEW) | numeric budget 회귀 |

### 5.9.4 세부 작업

#### D.9.a Crash report / ANR 수집

- guest 측 native signal (SIGSEGV/SIGABRT/SIGBUS) 가 도달하면 `core/crash_reporter` 가 minidump 형식으로 instance log 디렉터리 (`<instance>/logs/crashes/<timestamp>.dump`) 에 쓰기.
- ANR 감지: system_server main thread 가 N 초 (5s default) 응답 없으면 watchdog 이 thread stack snapshot.
- host UI: MainActivity 에서 crash log 목록 + 한 줄 요약 표시. 사용자가 SAF 로 export 가능.

#### D.9.b 부팅 실패 자동 rollback

- 첫 launcher activity 진입 (`STAGE_PHASE_D_LAUNCHER passed=true`) 까지를 *부팅 성공 마커* 로 정의.
- 각 ROM install / repair 직후 첫 부팅 시도가 60s 안에 마커에 도달하지 못하면 → 자동 rollback:
  1. Phase E.2 의 snapshot 이 있으면 그것으로 복원.
  2. 없으면 `RomInstaller.repair(instanceId)` 를 한 번 호출.
  3. 그래도 실패하면 STAGE_PHASE_D_BOOT_HEALTH passed=false reason=launcher_unreachable 으로 종료, 사용자에게 ROM 재설치 권유.
- 본 step 에서는 *감지 + 1회 자동 repair* 만 보장. 정교한 multi-snapshot rollback 은 Phase E.2 와 짝.

#### D.9.c Perf budget

- 측정 대상: 인스턴스의 host 프로세스 RSS, FPS (graphics_device 측), 열린 FD 개수, audit log 쓰기 속도.
- threshold (조정 가능):

| metric | budget |
|---|---|
| RSS (host process for `:vm1`) | ≤ 1024 MiB |
| FPS (last 30s rolling) | ≥ 24 |
| 열린 FD 개수 | ≤ 512 |
| audit log append/min | ≤ 600 |

- 게이트 라인: `STAGE_PHASE_D_PERF passed=true rss_mb=<v> fps_avg=<v> fd_count=<v> audit_rate=<v>`.

#### D.9.d 자동 백업

- `InstanceBackup.export(instanceId, destinationUri)`:
  - `<instance>/data/`, `<instance>/config/`, `<instance>/bridge-policy.json`, `<instance>/bridge-audit.jsonl`, `<instance>/logs/crashes/` 만 묶어 zip → SAF URI 로 write.
  - `<instance>/rootfs/` 는 ROM 에서 재생성 가능하므로 백업에 포함하지 않음 (사이즈 절감).
- 자동 트리거: 사용자가 명시 export 하거나, "주 1회" cron 옵션 (default off).
- 복원: `InstanceBackup.import(instanceId, sourceUri)` — 첫 호출 시 기존 데이터 덮어쓰기 확인 다이얼로그.

### 5.9.5 검증 게이트

- 진단 라인:

```text
STAGE_PHASE_D_OPS passed=true crash_report=on anr_watchdog=on boot_health=ok perf_budget=ok backup=ok
```

- 단위 테스트: `PerfBudgetTest`, `BootHealthMonitorTest`, `InstanceBackupTest`.

### 5.9.6 위험

- crash log 가 너무 자주 발생하면 디스크 폭주. rotation 정책 (최근 50 개만 유지).
- ANR watchdog 이 false positive 를 일으키면 정상 앱이 재시작. threshold 조정 + opt-out toggle.
- 백업 zip 안에 사용자 비밀 데이터 포함 가능 — Stage 7 audit redaction 정책과 동일하게 `bridge-audit.jsonl` 은 이미 redacted, 다른 파일 (예: `<instance>/data/data/<package>/databases/`) 은 사용자 명시 동의 후만 백업.

---

## Step D.10 — Phase D 종합 회귀 receiver

### 5.10.1 목표 라인

```text
STAGE_PHASE_D_RESULT passed=true pms=true launcher=true app_run=true bridges=true camera=true mic=true network=true file=true ops=true stage_phase_a=true stage_phase_b=true stage_phase_c=true
```

### 5.10.2 작업

- `StagePhaseDDiagnosticsReceiver` 가 Phase A/B/C 라인을 먼저 emit 한 뒤 D 라인 emit.
- D.9 의 `STAGE_PHASE_D_OPS` + `STAGE_PHASE_D_PERF` 라인도 함께 emit.
- `StagePhaseDFinalGateTest` 가 출력 형식을 픽스.

---

## 6. Phase D 종료 게이트

다음을 **모두** 만족해야 Phase E 의 어떤 step 도 시작하지 않는다.

- [ ] `STAGE_PHASE_D_RESULT passed=true pms=true launcher=true app_run=true bridges=true camera=true mic=true network=true file=true ops=true ...` 가 emulator log 에 기록.
- [ ] 사용자가 import 한 일반 APK 의 launcher activity 가 실제로 화면에 그려지고, touch 입력으로 상호작용 가능.
- [ ] Camera/Microphone permission 이 첫 사용 시점에만 요청.
- [ ] Off 상태인 bridge 는 host API 를 호출하지 않음 (Stage 7 회귀).
- [ ] `STAGE_PHASE_D_PERF passed=true rss_mb<=1024 fps_avg>=24 fd_count<=512` (D.9 perf budget).
- [ ] guest crash / ANR 발생 시 host crash log 디렉터리에 dump 가 기록 (D.9 crash report).
- [ ] 부팅 실패 시 자동 repair 1 회 시도 후 사용자에게 통지 (D.9 boot health).
- [ ] Stage 4·5·6·7 + Phase A·B·C 회귀 라인 미회귀.
- [ ] CI gate 통과.

## 7. 비목표

- 멀티 인스턴스 동시 실행 (Phase E.1).
- snapshot/overlay (Phase E.2).
- Android 10/12 호환 (Phase E.3/E.4).
- GLES/Virgl/Venus GPU 가속 (Phase E.5~E.7).
- 32-bit / x86 guest translation (Phase E.8).

## 8. 참고

- 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` 6 단계 + 7 단계 bridge 항목.
- Stage 7 의 bridge 인프라 위에서 동작: `docs/planning/stage-07-permission-bridges.md`.
- 게이트 인덱스: `docs/planning/future-roadmap.md` Phase D 절.

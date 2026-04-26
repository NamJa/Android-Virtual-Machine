# VPhoneOS-like Android-on-Android Clean-room Implementation Plan

이 문서는 `com.vphoneos.titan_arm64_4.13.2.apk` 정적 분석 결과를 바탕으로, VPhoneOS와 유사한 Android-on-Android 런타임을 **불필요한 권한과 외부 통신 없이** clean-room 방식으로 제작하기 위한 세부 구현 계획이다.

## 분석 기반 결론

VPhoneOS는 단순 APK 클로너가 아니라 다음 구조에 가깝다.

- Android guest rootfs를 APK asset에 포함한다.
- `NativeActivity` + foreground service 조합으로 각 VM 인스턴스를 별도 프로세스에서 실행한다.
- `libVPhoneGaGaLib.so`, `libuserkernel*.so`, `libtranslator.so`, `libvirglrenderer.so`, `librender_server.so` 등이 핵심 엔진이다.
- `zygote`, `system_server`, `binder`, `ashmem`, `property_service`, `PackageManager`, `virgl`, `qemu TCG` 흔적이 있다.
- Java/Kotlin 레이어는 UI, 인스턴스 관리, Surface/input/audio/network/permission bridge 역할을 한다.

따라서 제작 방향은 `DexClassLoader` 기반 앱 가상화가 아니라, **host APK 안에서 Android guest runtime을 띄우는 user-space Android runtime**이다.

---

## 1단계: 목표를 낮춘 Clean-room MVP

### 목표

처음부터 VPhoneOS 전체를 재현하지 않는다. 개인 사용 가능한 최소 단위의 VM을 먼저 만든다.

### 범위

- 단일 VM 인스턴스만 지원한다.
- host는 arm64 Android 기기만 지원한다.
- MVP guest는 arm64 Android 7.1.2만 지원한다.
- Android 10/12는 MVP 이후 compatibility phase로 남긴다.
- GMS, Magisk, Vulkan, 32-bit guest translation, multi-instance, 광고, 계정, 서버 통신은 제외한다.
- 권한은 최소 권한으로 시작한다.

### 제외 항목

- proprietary binary 재사용
- VPhoneOS native 라이브러리 복사
- 광고 SDK, 통계 SDK, 외부 업데이트 서버
- 256개 인스턴스용 Manifest static component
- root 권한 상승, 기기 설정 무단 변경, 권한 우회

### MVP 성공 기준

- 앱 설치 후 내부 저장소에 guest rootfs를 준비할 수 있다.
- 하나의 VM 인스턴스를 foreground service로 시작/중지할 수 있다.
- guest boot log를 host 앱에서 확인할 수 있다.
- Surface에 guest framebuffer 또는 boot progress 화면을 표시할 수 있다.
- host 권한 요청 없이 기본 UI와 runtime 초기화가 동작한다.

### 권장 디렉터리 구조

```text
vphone-cleanroom/
├─ app/
│  ├─ ui/
│  ├─ vm/
│  ├─ bridge/
│  └─ storage/
├─ native-runtime/
│  ├─ loader/
│  ├─ vfs/
│  ├─ binder/
│  ├─ property/
│  ├─ input/
│  ├─ graphics/
│  ├─ audio/
│  └─ network/
├─ guest-images/
│  ├─ android-7.1.2-arm64/
│  └─ tools/
└─ docs/
```

### 핵심 결정

- UI는 Kotlin + Jetpack Compose로 구현한다.
- native runtime은 C/C++ + Android NDK로 구현한다.
- guest filesystem은 처음에는 plain directory/image 기반으로 시작한다.
- 자체 VFS 포맷은 MVP 이후로 미룬다.

---

## 2단계: Host APK 구조

### 목표

VM을 관리하는 Android host 앱을 만든다. 이 단계에서는 guest Android를 완전히 부팅하지 않아도 된다. 중요한 것은 인스턴스 생명주기, foreground service, native bridge, Surface 전달 구조를 안정화하는 것이다.

### Manifest 설계

첫 버전은 하나의 인스턴스만 선언한다.

```xml
<application ...>
    <activity
        android:name=".ui.MainActivity"
        android:exported="true" />

    <activity
        android:name=".vm.VmNativeActivity"
        android:exported="false"
        android:process=":vm1"
        android:launchMode="singleInstance"
        android:configChanges="keyboard|keyboardHidden|orientation|screenSize"
        android:theme="@style/VmFullscreenTheme" />

    <service
        android:name=".vm.VmInstanceService"
        android:exported="false"
        android:process=":vm1"
        android:foregroundServiceType="dataSync" />

    <service
        android:name=".vm.VmManagerService"
        android:exported="false" />
</application>
```

### 최소 권한

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

선택 기능이 들어갈 때만 추가한다.

- Camera bridge: `CAMERA`
- Audio input bridge: `RECORD_AUDIO`
- Location bridge: `ACCESS_FINE_LOCATION`
- Media import: `READ_MEDIA_IMAGES` 또는 Storage Access Framework
- VPN/network isolation: `BIND_VPN_SERVICE`

### Kotlin 모듈

```text
app/vm/
├─ VmManagerService.kt
├─ VmInstanceService.kt
├─ VmNativeActivity.kt
├─ VmController.kt
├─ VmState.kt
├─ VmConfig.kt
├─ VmNativeBridge.kt
└─ VmSurfaceRegistry.kt
```

### 주요 책임

`VmManagerService`

- VM 생성/삭제/시작/중지 요청 처리
- VM 상태 저장
- foreground service bind 관리
- UI에 상태 이벤트 전달

`VmInstanceService`

- VM runtime 프로세스 생명주기 관리
- native runtime 초기화
- foreground notification 유지
- file import/export bridge
- host 권한 bridge 요청 처리

`VmNativeActivity`

- fullscreen Surface 제공
- touch/key/input event 수집
- native runtime에 Surface 전달
- guest 화면 resize/rotation 전달

`VmNativeBridge`

- JNI 함수 래핑
- native error code를 Kotlin sealed class로 변환
- native callback을 Flow/Channel로 변환

### JNI API 초안

```kotlin
object VmNativeBridge {
    external fun initHost(filesDir: String, nativeLibDir: String, sdkInt: Int): Int
    external fun initInstance(instanceId: Int, configJson: String): Int
    external fun startGuest(instanceId: Int): Int
    external fun stopGuest(instanceId: Int): Int
    external fun attachSurface(instanceId: Int, surface: Surface, width: Int, height: Int, dpi: Int): Int
    external fun resizeSurface(instanceId: Int, width: Int, height: Int, dpi: Int): Int
    external fun detachSurface(instanceId: Int): Int
    external fun sendTouch(instanceId: Int, action: Int, pointerId: Int, x: Float, y: Float): Int
    external fun sendKey(instanceId: Int, keyCode: Int, down: Boolean): Int
}
```

### 검증 기준

- host 앱이 `VmManagerService`를 시작/중지할 수 있다.
- `VmNativeActivity`가 `:vm1` 프로세스에서 열린다.
- Surface lifecycle event가 native layer까지 전달된다.
- native layer에서 dummy framebuffer를 렌더링할 수 있다.
- 앱 재시작 후 VM 상태가 복구된다.

---

## 3단계: ROM/Image 파이프라인

### 목표

guest Android 파일시스템을 생성, 패키징, 추출, 업데이트할 수 있는 체계를 만든다.

### 첫 구현 방식

MVP에서는 VPhoneOS의 `readonly.bin`, `superblock.bin`, metadata 기반 custom VFS를 바로 따라가지 않는다.

대신 다음 구조로 시작한다.

```text
files/
└─ instances/
   └─ vm1/
      ├─ config/
      │  └─ vm_config.json
      ├─ rootfs/
      │  ├─ system/
      │  ├─ vendor/
      │  ├─ data/
      │  ├─ cache/
      │  └─ root/
      ├─ logs/
      └─ snapshots/
```

### guest image 생성

1. AOSP 또는 공개 Android-x86/arm64 기반 rootfs를 준비한다.
2. guest에서 필요한 최소 구성만 남긴다.
3. boot animation, 불필요한 system app, telephony provider 등은 최소화한다.
4. `init`, `zygote`, `system_server`, `servicemanager`, `surfaceflinger` 실행에 필요한 파일을 확인한다.
5. host runtime이 제공할 device node와 socket은 guest rootfs에 직접 만들지 않는다.

### asset 패키징

```text
assets/
└─ guest/
   └─ androidfs_7.1.2_arm64.tar.zst
```

초기에는 `tar.zst` 또는 `zip`을 사용한다. 이후 random access와 snapshot이 필요해지면 custom image format을 설계한다.

### 추출 흐름

```text
MainActivity
  -> VmManagerService.createInstance()
  -> RomInstaller.ensureImage()
  -> asset checksum 검증
  -> files/instances/vm1/rootfs 로 추출
  -> vm_config.json 생성
```

### `vm_config.json` 예시

```json
{
  "instanceId": 1,
  "guestVersion": "7.1.2",
  "guestArch": "arm64",
  "rootfsPath": "files/instances/vm1/rootfs",
  "width": 720,
  "height": 1280,
  "densityDpi": 320,
  "networkMode": "host",
  "clipboardBridge": false,
  "locationBridge": false,
  "cameraBridge": false,
  "microphoneBridge": false
}
```

### 이후 확장

- readonly base layer
- writable overlay layer
- snapshot layer
- shared partition
- backup/restore
- image version migration

### 검증 기준

- asset checksum 검증 가능
- rootfs 최초 추출 가능
- 중복 추출 방지 가능
- 손상된 rootfs 감지 가능
- rootfs 삭제 후 재생성 가능

---

## 4단계: Native Runtime

### 목표

host Android 앱 권한 안에서 guest Android userspace를 실행하는 runtime을 만든다.

이 단계가 전체 프로젝트의 핵심이다.

### 하위 모듈

```text
native-runtime/
├─ core/
│  ├─ runtime_context.cpp
│  ├─ instance.cpp
│  ├─ event_loop.cpp
│  └─ logging.cpp
├─ loader/
│  ├─ guest_process.cpp
│  ├─ elf_loader.cpp
│  └─ linker_bridge.cpp
├─ syscall/
│  ├─ syscall_dispatch.cpp
│  ├─ futex.cpp
│  ├─ epoll.cpp
│  ├─ signal.cpp
│  └─ process.cpp
├─ vfs/
│  ├─ path_resolver.cpp
│  ├─ mount_table.cpp
│  ├─ file_node.cpp
│  └─ fd_table.cpp
├─ binder/
│  ├─ binder_device.cpp
│  ├─ service_manager.cpp
│  └─ parcel.cpp
├─ property/
│  ├─ property_service.cpp
│  └─ build_props.cpp
├─ device/
│  ├─ ashmem.cpp
│  ├─ input_device.cpp
│  ├─ graphics_device.cpp
│  └─ audio_device.cpp
└─ jni/
   └─ vm_native_bridge.cpp
```

### 현실적인 구현 순서

#### 4.1 Native bootstrap

- `initHost()`
- `initInstance()`
- log sink 연결
- runtime thread 생성
- guest rootfs path 확인
- host ABI/SDK/page size 확인

#### 4.2 VFS

- guest path `/system`, `/vendor`, `/data`, `/cache`, `/dev`, `/proc`, `/sys`를 host path 또는 virtual node로 매핑한다.
- guest process가 보는 path와 host 실제 path를 분리한다.
- `/dev/binder`, `/dev/ashmem`, `/dev/input`, `/dev/graphics`는 virtual device로 처리한다.

#### 4.3 Process model

처음에는 Android 전체 process model을 완벽히 흉내 내지 않는다.

1. single process 안에서 guest init simulation
2. guest binary 실행 PoC
3. zygote 실행
4. system_server 실행
5. app process fork/exec 또는 thread/process abstraction

#### 4.4 Binder

최소 목표:

- `servicemanager` 대응
- guest framework가 필요한 service 등록/조회
- `PackageManager`, `ActivityManager`, `SurfaceFlinger` 관련 최소 transaction 처리

장기 목표:

- binder driver semantics
- binder node/ref/death recipient
- thread pool
- handle table
- parcel 호환성

#### 4.5 Property service

- `ro.product.*`, `ro.build.*`, `ro.hardware.*`, `ro.zygote`, `ro.kernel.qemu` 제공
- device model spoofing은 명시적 설정값만 사용
- host 개인정보를 기본값으로 노출하지 않는다.

#### 4.6 Android service bootstrap

목표 부팅 순서:

```text
runtime
  -> virtual init
  -> property service
  -> servicemanager
  -> zygote
  -> system_server
  -> launcher
```

### 큰 위험

- Android framework는 binder, ashmem, SELinux, cgroup, signal, futex 의존도가 높다.
- Android 버전이 올라갈수록 binderfs, memfd, seccomp, hidden assumptions가 늘어난다.
- host Android 앱 sandbox 안에서 Linux kernel feature를 완전히 재현하기 어렵다.

### 검증 기준

- guest binary 하나를 VFS 안에서 실행할 수 있다.
- property lookup이 동작한다.
- binder service 등록/조회 smoke test가 통과한다.
- zygote 또는 최소 guest framework process가 시작된다.
- crash log와 native stack trace를 host에서 수집할 수 있다.

---

## 5단계: Graphics

### 목표

guest Android 화면을 host `Surface`에 표시하고, host 입력을 guest input으로 전달한다.

### 구현 단계

#### 5.1 Dummy renderer

- native thread에서 단색 framebuffer 렌더링
- boot progress overlay 표시
- Surface attach/detach/resize 안정화

#### 5.2 Software framebuffer

- guest framebuffer memory를 host Surface로 복사
- Android `ANativeWindow` 사용
- pixel format은 `RGBA_8888`로 시작
- rotation, density, aspect ratio 처리

#### 5.3 Input bridge

Host event:

- touch down/move/up
- key down/up
- back/home/recent
- text input
- gamepad optional

Guest event:

- virtual input device
- `/dev/input/event*` 또는 runtime input queue

#### 5.4 GPU acceleration

MVP 이후 순서:

1. SwiftShader/software renderer
2. GLES passthrough
3. virglrenderer
4. Venus/Vulkan

VPhoneOS는 `libvirglrenderer.so`, `librender_server.so`, `libOpenglRender.so` 흔적이 있으므로 장기적으로 이 방향이 맞다. 단, MVP에서는 GPU acceleration을 목표로 잡지 않는다.

### Surface API

```kotlin
external fun attachSurface(
    instanceId: Int,
    surface: Surface,
    width: Int,
    height: Int,
    densityDpi: Int
): Int

external fun resizeSurface(
    instanceId: Int,
    width: Int,
    height: Int,
    densityDpi: Int
): Int

external fun detachSurface(instanceId: Int): Int
```

### 검증 기준

- Surface 생성/삭제 반복 시 crash가 없다.
- 화면 회전/resize 후 render가 유지된다.
- touch 좌표가 guest 좌표계로 정확히 변환된다.
- dummy framebuffer 기준 30fps 이상 안정적으로 출력된다.
- software framebuffer 기준 간단한 launcher 화면이 출력된다.

---

## 6단계: APK 설치/실행

### 목표

host에서 선택한 APK를 guest Android 내부에 설치하고 실행한다.

### 설계 원칙

- APK는 host OS에 설치하지 않는다.
- host는 APK 파일을 guest `/data/local/tmp` 또는 import staging directory로 전달한다.
- 설치는 guest `PackageManager` 또는 runtime package installer가 처리한다.
- host 앱은 설치 상태와 progress만 표시한다.
- 이 단계는 Stage 04의 process/binder/PackageManager path와 Stage 05의 framebuffer/input path가 최소 동작한 뒤에 완료할 수 있다.

### Import flow

```text
User selects APK
  -> Storage Access Framework URI 획득
  -> host staging dir로 복사
  -> checksum 계산
  -> native importApk(instanceId, stagedApkPath)
  -> guest package installer 실행
  -> PackageManager state 갱신
  -> launcher icon 표시
```

### API 초안

```kotlin
data class GuestPackageInfo(
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val iconPath: String?,
    val enabled: Boolean
)

external fun importApk(instanceId: Int, hostApkPath: String): Int
external fun uninstallPackage(instanceId: Int, packageName: String): Int
external fun listPackages(instanceId: Int): Array<GuestPackageInfo>
external fun launchPackage(instanceId: Int, packageName: String): Int
```

### Split APK

MVP에서는 단일 APK만 지원한다.

이후 확장:

- `.apks`
- `.xapk`
- split APK set
- OBB import

### Launcher UI

- guest package list 표시
- 앱 실행
- 삭제
- 데이터 초기화
- APK import
- guest 설정 바로가기

### 권한 처리

guest 내부 권한 요청은 guest Android가 관리한다.

host 권한이 필요한 기능은 별도 bridge에서 처리한다.

예:

- guest camera 요청 -> host camera permission 확인 -> bridge 활성화
- guest location 요청 -> host location permission 확인 -> bridge 활성화
- guest microphone 요청 -> host microphone permission 확인 -> bridge 활성화

### 검증 기준

- 단일 APK 설치 가능
- 설치된 package list 조회 가능
- launcher activity 실행 가능
- 앱 삭제 가능
- guest 재부팅 후 package state 유지
- host 앱 권한 없이도 일반 APK 설치/실행 smoke test 가능

---

## 7단계: 권한 최소화 및 Host Bridge

### 목표

VPhoneOS처럼 광범위한 host 권한을 선점하지 않고, guest 기능이 실제로 필요할 때만 host 권한을 요청한다.

### 권한 정책

기본 원칙:

- host 개인정보는 guest에 기본 전달하지 않는다.
- 개인정보 또는 센서 입력 bridge는 기본 off다.
- audio output, vibration처럼 host 개인정보를 읽지 않는 출력성 bridge는 기본 on이 가능하지만 instance별 off toggle을 제공한다.
- 사용자가 인스턴스별로 켜야 한다.
- host 권한이 없는 bridge는 guest에 unavailable로 응답한다.
- 모든 bridge 접근은 로그로 남긴다.

### Bridge 목록

#### Clipboard bridge

- 기본 off
- host -> guest, guest -> host 방향을 따로 설정
- 민감 데이터 자동 공유 방지

#### Location bridge

- 기본 off
- 실제 위치, 고정 위치, 비활성화 모드 제공
- host `ACCESS_FINE_LOCATION`은 실제 위치 모드에서만 요청

#### Camera bridge

- 기본 off
- host camera frame을 guest camera HAL 대체 device로 전달
- MVP에서는 미지원 처리 가능

#### Microphone bridge

- 기본 off
- guest audio input 요청 시 host permission 필요
- MVP에서는 미지원 처리 가능

#### Audio output bridge

- guest PCM output을 host AudioTrack으로 출력
- 기본 on 가능
- mute toggle 제공

#### Network bridge

MVP:

- host network를 그대로 사용하는 NAT-like mode

이후:

- network off
- SOCKS5 proxy
- per-instance network isolation
- VPN service 기반 routing

#### Telephony/device identity bridge

- 기본 fake profile 사용
- host `READ_PHONE_STATE`는 사용하지 않는다.
- IMEI/phone number/real carrier 정보는 전달하지 않는다.

### 설정 UI

```text
Instance Settings
├─ Display
│  ├─ Resolution
│  ├─ DPI
│  └─ Rotation
├─ Privacy
│  ├─ Clipboard
│  ├─ Location
│  ├─ Camera
│  └─ Microphone
├─ Network
│  ├─ Enabled
│  ├─ Proxy
│  └─ Isolation
├─ Storage
│  ├─ Import APK
│  ├─ Import File
│  └─ Export File
└─ Runtime
   ├─ Logs
   ├─ Stop
   ├─ Reboot
   └─ Reset data
```

### 금지할 기본 동작

- `QUERY_ALL_PACKAGES`로 host 앱 목록 수집
- `READ_PHONE_STATE`로 실기기 식별자 수집
- `WRITE_SETTINGS`로 host 설정 변경
- `CHANGE_WIFI_STATE`로 host Wi-Fi 변경
- `SYSTEM_ALERT_WINDOW` 상시 요구
- 광고 ID 수집
- 외부 서버로 boot/app/package telemetry 전송

### 검증 기준

- 최초 설치 시 위험 권한 요청이 없다.
- 기능별로 권한 요청 타이밍이 분리된다.
- bridge off 상태에서 guest가 host 개인정보를 얻지 못한다.
- bridge 사용 로그를 UI에서 확인할 수 있다.
- 네트워크 off 모드에서 guest 앱 통신이 차단된다.

---

## 권장 개발 순서 요약

1. Kotlin host app + `VmManagerService` + `VmInstanceService` 골격을 만든다.
2. `VmNativeActivity`에서 native dummy renderer를 Surface에 띄운다.
3. ROM asset 추출/검증/instance directory 생성을 구현한다.
4. native VFS/property/logging부터 만든다.
5. guest binary 실행 PoC를 만든다.
6. binder/service manager 최소 구현을 시작한다.
7. zygote/system_server bootstrap을 목표로 한다.
8. software framebuffer와 input bridge를 연결한다.
9. APK import/list/launch flow를 guest PackageManager와 연결한다.
10. 권한 bridge를 기능별 opt-in으로 추가한다.

## 장기 로드맵

### Phase A: Host Shell

- host APK
- service/activity lifecycle
- native bridge
- dummy render
- instance storage

### Phase B: Guest Runtime PoC

- rootfs extract
- VFS
- property service
- guest process loader
- basic logging

### Phase C: Android Boot

- binder minimum
- servicemanager
- zygote
- system_server
- software framebuffer

### Phase D: Usable VM

- launcher
- APK install
- app launch
- input/audio/network bridge
- file import/export

### Phase E: Compatibility

- Android 10/12
- multi-instance
- snapshot
- GLES passthrough
- virgl/Vulkan
- optional GMS profile

## 가장 큰 기술 리스크

- user-space에서 binder/ashmem/futex/signal semantics를 충분히 재현해야 한다.
- Android framework는 kernel behavior에 강하게 의존한다.
- Android 12는 Android 7.1.2보다 훨씬 더 많은 compatibility 작업이 필요하다.
- GPU acceleration은 별도 대형 프로젝트에 가깝다.
- 32-bit guest 또는 x86 translation은 `libtranslator.so` 수준의 QEMU TCG 계열 작업이 필요하다.

## 개인용 추천 MVP

가장 현실적인 첫 목표는 다음이다.

```text
Single-instance Android 7.1.2 arm64 guest
+ host Kotlin app
+ native dummy/software renderer
+ rootfs extraction
+ minimal VFS/property/binder
+ APK install/list/launch
+ no ads
+ no analytics
+ no broad permissions
```

이 목표도 작지 않다. 하지만 VPhoneOS 구조를 기준으로 보면 가장 덜 위험하고, 이후 Android 10/12, Vulkan, multi-instance로 확장 가능한 기반이다.

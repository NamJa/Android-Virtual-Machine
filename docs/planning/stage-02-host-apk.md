# Stage 02 - Host APK Structure

## 목적

VM runtime을 담는 host Android 앱의 구조를 만든다. 이 단계에서는 guest Android를 완전히 부팅하지 않아도 된다. 중요한 것은 service/activity/process 분리, lifecycle, JNI bridge, Surface 전달 구조를 먼저 안정화하는 것이다.

## 패키지 구조

```text
app/src/main/java/com/example/vphone/
├─ MainApplication.kt
├─ ui/
│  ├─ MainActivity.kt
│  ├─ VmHomeScreen.kt
│  ├─ VmSettingsScreen.kt
│  └─ LogViewerScreen.kt
├─ vm/
│  ├─ VmManagerService.kt
│  ├─ VmInstanceService.kt
│  ├─ VmNativeActivity.kt
│  ├─ VmController.kt
│  ├─ VmState.kt
│  ├─ VmConfig.kt
│  ├─ VmEvent.kt
│  ├─ VmNativeBridge.kt
│  └─ VmSurfaceRegistry.kt
├─ storage/
│  ├─ InstanceStore.kt
│  ├─ RomInstaller.kt
│  ├─ AssetVerifier.kt
│  └─ PathLayout.kt
└─ bridge/
   ├─ PermissionBroker.kt
   ├─ ClipboardBridge.kt
   ├─ LocationBridge.kt
   ├─ AudioBridge.kt
   └─ NetworkBridge.kt
```

## Manifest

MVP에서는 하나의 VM 프로세스만 선언한다.

```xml
<manifest>
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />

    <application
        android:name=".MainApplication"
        android:allowBackup="false"
        android:extractNativeLibs="true"
        android:largeHeap="true"
        android:resizeableActivity="true">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".vm.VmNativeActivity"
            android:exported="false"
            android:process=":vm1"
            android:launchMode="singleInstance"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|density"
            android:theme="@style/VmFullscreenTheme" />

        <service
            android:name=".vm.VmManagerService"
            android:exported="false" />

        <service
            android:name=".vm.VmInstanceService"
            android:exported="false"
            android:process=":vm1"
            android:foregroundServiceType="dataSync" />
    </application>
</manifest>
```

## Service 설계

### `VmManagerService`

책임:

- VM 목록 관리
- VM 생성/삭제
- VM 시작/중지
- `VmInstanceService` bind/unbind
- UI에 상태 broadcast 또는 Flow 제공
- crash 후 상태 복구

상태:

```kotlin
enum class VmState {
    MissingImage,
    Installed,
    Starting,
    Running,
    Stopping,
    Stopped,
    Failed
}
```

주요 API:

```kotlin
interface VmManagerApi {
    suspend fun createInstance(config: VmConfig): Result<Unit>
    suspend fun deleteInstance(instanceId: Int): Result<Unit>
    suspend fun startInstance(instanceId: Int): Result<Unit>
    suspend fun stopInstance(instanceId: Int): Result<Unit>
    fun observeState(instanceId: Int): Flow<VmState>
}
```

### `VmInstanceService`

책임:

- VM 프로세스의 foreground service
- native runtime 초기화
- runtime start/stop
- VM notification 유지
- bridge 요청 처리
- log forwarding

시작 flow:

```text
onCreate()
  -> startForeground()
  -> load native library
  -> initHost()
  -> initInstance()
  -> wait for activity/surface
```

중지 flow:

```text
stopVm()
  -> native stopGuest()
  -> detach all surfaces
  -> stopForeground()
  -> stopSelf()
```

### `VmNativeActivity`

책임:

- guest 화면 표시
- `SurfaceView` 또는 `TextureView` 제공
- lifecycle event를 service/native로 전달
- input event를 guest로 전달

구조:

```kotlin
class VmNativeActivity : Activity() {
    private lateinit var surfaceView: SurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        surfaceView = SurfaceView(this)
        setContentView(surfaceView)
        surfaceView.holder.addCallback(surfaceCallback)
    }
}
```

## JNI Bridge

### Kotlin wrapper

```kotlin
object VmNativeBridge {
    init {
        System.loadLibrary("vphone_runtime")
    }

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

### Native callback

```kotlin
interface VmRuntimeCallback {
    fun onBootProgress(instanceId: Int, progress: Int, message: String)
    fun onRuntimeError(instanceId: Int, code: Int, message: String)
    fun onGuestReady(instanceId: Int)
    fun onGuestStopped(instanceId: Int)
    fun onLogLine(instanceId: Int, level: Int, tag: String, message: String)
}
```

## Notification

VM이 실행 중이면 foreground notification을 유지한다.

필드:

- 앱 이름
- 인스턴스 이름
- 현재 상태
- stop action
- open action

## 파일 경로

```text
files/
├─ instances/
│  └─ vm1/
│     ├─ config/vm_config.json
│     ├─ rootfs/
│     ├─ logs/
│     ├─ staging/
│     └─ export/
└─ global/
   └─ runtime_state.json
```

## 구현 순서

1. Android project와 package 구조를 만든다.
2. Compose 기반 `MainActivity`를 만든다.
3. `VmState`와 `VmConfig` data class를 정의한다.
4. `VmManagerService` bind API를 만든다.
5. `VmInstanceService` foreground notification을 만든다.
6. `VmNativeActivity`를 `:vm1` 프로세스에서 실행한다.
7. dummy JNI library를 연결한다.
8. Surface lifecycle을 JNI까지 전달한다.
9. input event를 JNI까지 전달한다.
10. log viewer 화면을 만든다.

## 테스트

- 앱 시작 smoke test
- service bind/unbind test
- VM start/stop 반복 test
- process name 확인 test
- notification action test
- Surface create/destroy 반복 test
- orientation change test

## 완료 기준

- `MainActivity`에서 VM 시작 버튼을 누르면 `VmInstanceService`가 foreground로 실행된다.
- `VmNativeActivity`가 열리고 native layer가 Surface pointer를 받는다.
- VM 중지 버튼을 누르면 activity/service/native runtime이 순서대로 정리된다.
- 앱 재실행 후 마지막 VM 상태를 UI에 표시한다.


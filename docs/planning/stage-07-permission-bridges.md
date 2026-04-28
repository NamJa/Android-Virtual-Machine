# Stage 07 - Permission Minimization and Host Bridges

## 목적

Stage 07의 목표는 guest Android가 host 기기 기능을 사용할 때, host 앱이 불필요한 권한을 선점하지 않고 기능별 opt-in bridge를 통해서만 host 기능을 노출하는 것이다.

이 단계가 완료된 시점의 판정 기준은 bridge API skeleton이 아니다. Stage 07 완료는 다음 사용자 흐름이 end-to-end로 동작한다는 뜻이다.

```text
Guest가 host 기능 요청
  -> native bridge dispatcher
  -> instance별 bridge policy 확인
  -> 필요 시 사용 시점에만 host runtime permission 요청
  -> 허용된 bridge만 host API 호출
  -> guest에 result 또는 unavailable 반환
  -> bridge 사용 내역을 audit log에 기록
  -> Settings UI에서 bridge 상태와 log 확인/변경
```

## Stage 06 Readiness Baseline

Stage 07 작업은 Stage 06이 runtime-compatible Usable VM 기준으로 완료된 상태를 출발점으로 삼는다.

### 진입 조건

- APK install/list/launch/delete/clear-data 기본 흐름이 동작한다.
- Stage 06 diagnostics가 다음 결과를 만족한다.

```text
STAGE6_RESULT passed=true staging=true install=true packages=true launch=true management=true regressions=true
```

- Stage 04/05 회귀 진단이 Stage 06 diagnostics 안에서 통과한다.
- Stage 06의 launch path는 최소한 runtime-compatible app process/window/input dispatch 상태를 제공한다.

### Non-blocker

다음 항목은 Stage 07 진입을 막지 않는다.

- 실제 Android `system_server` 기반 ActivityManager 실행
- Camera preview frame bridge
- Microphone PCM capture bridge
- VpnService 기반 per-instance network routing
- 실제 device HAL 수준의 sensor/camera/audio input compatibility

위 항목은 Stage 07의 privacy/permission boundary 안에 stub 또는 unsupported 상태로 포함하되, 구현 완료 blocker로 삼지 않는다.

## Stage 07 최종 목표

Stage 07은 다음 목표를 모두 만족할 때 완료로 본다.

- 앱 설치 직후 privacy-sensitive dangerous permission을 요청하지 않는다.
- Dangerous permission은 해당 bridge를 켜고 기능을 실제로 사용할 때만 요청한다.
- 모든 privacy-sensitive bridge는 기본 off다.
- off 또는 unsupported 상태의 bridge request는 host 개인정보를 반환하지 않고 `unavailable`로 끝난다.
- Clipboard, location, network, audio output, vibration, device profile bridge가 instance별 policy를 따른다.
- Camera와 microphone은 Stage 07 MVP에서 기본 off/unsupported로 명확히 응답하고, host permission을 선점하지 않는다.
- Device profile은 host 실제 식별자 대신 instance별 synthetic profile만 반환한다.
- Bridge 사용 결과는 instance별 audit log로 남는다.
- 사용자는 Settings UI에서 bridge enable/mode를 바꾸고 audit log를 확인할 수 있다.
- Host setting, phone identity, real installed package list, advertising id 접근은 기본적으로 없다.
- Stage 04, Stage 05, Stage 06 diagnostics가 퇴행하지 않는다.

## 기본 정책

- 개인정보 또는 sensor input bridge는 기본 off다.
- Audio output, vibration처럼 host 개인정보를 읽지 않는 출력성 bridge는 기본 on 가능하지만 instance별 off toggle을 제공한다.
- Host dangerous permission은 manifest에 선점하지 않고, 기능 사용 시점에만 요청한다.
- Guest에는 host 개인정보를 기본 전달하지 않는다.
- Bridge 사용 내역은 instance별 log로 남긴다.
- 사용자가 언제든 bridge를 끌 수 있어야 한다.
- Bridge policy는 VM instance boundary를 넘어 공유되지 않는다.
- Guest request는 항상 allow/deny/unavailable 중 하나의 결정 결과를 받아야 한다.

## Manifest 권한 정책

### 기본 허용 권한

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

`POST_NOTIFICATIONS`는 Android 13 이상에서 notification 표시 시점에만 요청한다. `VIBRATE`는 normal permission이지만 user-facing toggle을 둔다.

### 기본 금지 권한

```text
QUERY_ALL_PACKAGES
READ_PHONE_STATE
READ_PRIVILEGED_PHONE_STATE
WRITE_SETTINGS
CHANGE_WIFI_STATE
SYSTEM_ALERT_WINDOW
SYSTEM_OVERLAY_WINDOW
AD_ID
```

정말 필요한 경우에도 MVP 이후 별도 feature flag와 별도 문서로 분리한다.

### 사용 시점 요청 권한

아래 권한은 manifest에 추가하더라도 bridge 사용 시점까지 요청하지 않는다.

```text
ACCESS_FINE_LOCATION
CAMERA
RECORD_AUDIO
```

Stage 07 MVP에서 `CAMERA`와 `RECORD_AUDIO`는 기본적으로 요청하지 않는다. Camera/microphone bridge는 unsupported result와 audit log까지 구현하고, 실제 media stream은 후속 단계로 둔다.

## Bridge Architecture

```text
guest request
  -> native runtime
  -> bridge dispatcher
  -> PermissionBroker
  -> BridgePolicyStore
  -> optional host Android permission request
  -> host Android API or synthetic provider
  -> BridgeAuditLog
  -> native callback
  -> guest response
```

## Kotlin 모듈

```text
bridge/
├─ BridgeType.kt
├─ BridgeMode.kt
├─ BridgeDecision.kt
├─ BridgePolicy.kt
├─ BridgePolicyStore.kt
├─ PermissionBroker.kt
├─ BridgeAuditLog.kt
├─ BridgeDispatcher.kt
├─ ClipboardBridge.kt
├─ LocationBridge.kt
├─ CameraBridge.kt
├─ MicrophoneBridge.kt
├─ AudioOutputBridge.kt
├─ NetworkBridge.kt
├─ DeviceProfileBridge.kt
└─ VibrationBridge.kt
```

Native boundary:

```text
VmNativeBridge.getBridgeStatus(instanceId)
VmNativeBridge.setBridgePolicy(instanceId, bridge, mode, enabled)
VmNativeBridge.dispatchBridgeRequest(instanceId, bridge, operation, payloadJson)
VmNativeBridge.getBridgeAuditLog(instanceId, limit)
VmNativeBridge.clearBridgeAuditLog(instanceId)
```

## 공통 데이터 모델

### BridgeType

```kotlin
enum class BridgeType {
    CLIPBOARD,
    LOCATION,
    CAMERA,
    MICROPHONE,
    AUDIO_OUTPUT,
    NETWORK,
    DEVICE_PROFILE,
    VIBRATION,
}
```

### BridgeMode

```kotlin
sealed interface BridgeMode {
    data object Off : BridgeMode
    data object Enabled : BridgeMode
    data object Unsupported : BridgeMode
    data object ClipboardHostToGuest : BridgeMode
    data object ClipboardGuestToHost : BridgeMode
    data object ClipboardBidirectional : BridgeMode
    data class FixedLocation(val latitude: Double, val longitude: Double) : BridgeMode
    data object HostLocation : BridgeMode
}
```

### BridgeDecision

```kotlin
data class BridgeDecision(
    val allowed: Boolean,
    val result: BridgeResult,
    val reason: String,
)

enum class BridgeResult {
    ALLOWED,
    DENIED,
    UNAVAILABLE,
    UNSUPPORTED,
    PERMISSION_REQUIRED,
}
```

### Audit entry

```json
{
  "time": "",
  "instanceId": "vm1",
  "bridge": "location",
  "operation": "request_current_location",
  "allowed": false,
  "result": "UNAVAILABLE",
  "reason": "bridge_disabled"
}
```

## Step-by-step Implementation Plan

각 step은 이전 step의 산출물을 기반으로 한다. 마지막 step의 verification gate가 통과하면 Stage 07의 최종 목표가 모두 충족되어야 한다.

## Step 7.0 - Baseline and Scope Lock

### 목표

Stage 07 착수 전에 Stage 06 runtime-compatible 완료 상태와 permission baseline을 고정한다.

### 구현 작업

- Stage 06 diagnostics 실행 방법을 README 또는 CLAUDE checklist와 맞춘다.
- 현재 manifest 권한 목록을 테스트에서 읽을 수 있게 한다.
- Stage 07 MVP에서 지원할 bridge와 unsupported로 남길 bridge를 코드 상수로 고정한다.
- Stage 07에서 실제 host 개인정보를 반환하면 안 되는 항목을 denylist로 정의한다.

### 완료 기준

- Stage 06 diagnostics가 통과한다.
- 현재 manifest에 금지 권한이 없음을 테스트로 확인한다.
- Stage 07 bridge scope가 테스트 가능한 enum/list로 존재한다.

### 검증

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- Manifest permission denylist unit test

## Step 7.1 - Permission and Manifest Guard

### 목표

권한 최소화 정책을 자동 테스트로 잠근다.

### 구현 작업

- `QUERY_ALL_PACKAGES`, `READ_PHONE_STATE`, `WRITE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, `AD_ID` 등 금지 권한을 manifest guard test에 추가한다.
- `ACCESS_FINE_LOCATION`, `CAMERA`, `RECORD_AUDIO`가 기본 user flow에서 요청되지 않는지 추적할 수 있게 permission request recorder를 추가한다.
- Host dangerous permission request는 `PermissionBroker`를 통해서만 호출되도록 entry point를 하나로 모은다.
- Permission request reason message model을 정의한다.

### 완료 기준

- 금지 권한이 manifest에 추가되면 unit test가 실패한다.
- Bridge 외부에서 dangerous permission을 요청하는 path가 없다.
- 앱 첫 실행과 APK install/launch 기본 흐름에서 dangerous permission prompt가 뜨지 않는다.

### 검증

- Manifest denylist test
- PermissionBroker request recorder test
- Stage 06 APK install/launch regression diagnostic

## Step 7.2 - Bridge Policy Store

### 목표

Instance별 bridge enable/mode 상태를 저장하고 VM restart 후에도 유지한다.

### 구현 작업

- `BridgePolicy` data model을 만든다.
- `BridgePolicyStore`를 instance directory 아래 JSON 파일로 저장한다.
- Default policy를 정의한다.
- Policy load/save failure를 명확한 error로 반환한다.
- Instance boundary 밖 path escape를 막는다.

### 기본 policy

```text
clipboard = off
location = off
camera = unsupported
microphone = unsupported
audio_output = enabled
network = enabled
device_profile = enabled_synthetic
vibration = enabled
```

### 완료 기준

- 새 instance는 default policy를 가진다.
- Policy 변경은 restart 후 유지된다.
- 한 instance의 policy가 다른 instance에 영향을 주지 않는다.
- 손상된 policy file은 safe default로 복구되고 audit log에 남는다.

### 검증

- Policy default test
- Policy persistence test
- Instance isolation test
- Corrupt policy recovery test

## Step 7.3 - PermissionBroker

### 목표

Bridge별 policy와 Android runtime permission 요청을 하나의 broker에서 판정한다.

### API

```kotlin
interface PermissionBroker {
    suspend fun decide(
        instanceId: String,
        bridge: BridgeType,
        operation: String,
        reason: PermissionReason,
    ): BridgeDecision

    suspend fun ensurePermission(
        permission: String,
        reason: PermissionReason,
    ): Boolean

    fun isBridgeEnabled(instanceId: String, bridge: BridgeType): Boolean
    fun setBridgePolicy(instanceId: String, bridge: BridgeType, mode: BridgeMode)
}
```

### 구현 작업

- Off bridge는 permission 요청 없이 `UNAVAILABLE`을 반환한다.
- Unsupported bridge는 permission 요청 없이 `UNSUPPORTED`를 반환한다.
- Enabled bridge 중 dangerous permission이 필요한 경우에만 `ensurePermission`을 호출한다.
- Permission denied 결과를 guest에 안정적으로 전달한다.
- Permission reason은 bridge, operation, requested permission을 포함한다.

### 완료 기준

- Off 상태 request는 host API를 호출하지 않는다.
- Unsupported 상태 request는 host permission을 요청하지 않는다.
- Permission deny 시 guest는 crash 없이 unavailable/denied result를 받는다.
- Permission allow 시에만 host API bridge가 호출된다.

### 검증

- Off bridge decision test
- Unsupported bridge decision test
- Permission denied test
- Permission allowed test

## Step 7.4 - Audit Log Foundation

### 목표

모든 bridge request와 policy 변경을 instance별 audit log로 남긴다.

### 구현 작업

- `BridgeAuditLog` append/read/clear API를 만든다.
- Audit entry에 time, instanceId, bridge, operation, result, reason을 기록한다.
- 개인정보 payload는 log에 직접 저장하지 않는다.
- Log size cap과 rotation을 적용한다.
- Policy 변경 이벤트도 기록한다.

### 완료 기준

- 허용/거부/unsupported request가 모두 log에 남는다.
- Clipboard text, location coordinate raw payload 등 민감 payload는 log에 남지 않는다.
- Audit log는 instance별로 분리된다.
- Log clear가 해당 instance에만 적용된다.

### 검증

- Audit append/read test
- Audit redaction test
- Audit rotation test
- Audit instance isolation test

## Step 7.5 - Native Bridge Dispatcher

### 목표

Guest/native runtime request가 Kotlin bridge layer와 같은 policy/audit path를 사용하게 한다.

### 구현 작업

- Native `dispatchBridgeRequest` entry point를 추가한다.
- Request payload는 JSON으로 받고, bridge별 parser에서 schema validation을 수행한다.
- Unknown bridge/operation은 `UNSUPPORTED`로 반환한다.
- Dispatcher 결과를 native package/runtime status JSON에 포함한다.
- Guest-facing response는 항상 result code와 message를 포함한다.

### 완료 기준

- Native request가 policy off 상태에서 host API를 호출하지 않는다.
- Unknown operation이 crash 없이 unsupported로 끝난다.
- Dispatcher request가 audit log에 남는다.
- Stage 06 launch/input path가 bridge dispatcher 추가 후에도 퇴행하지 않는다.

### 검증

- Native dispatcher unit/instrumented test
- Unknown bridge test
- Invalid payload test
- Stage 06 launch regression diagnostic

## Step 7.6 - Settings UI

### 목표

사용자가 bridge 상태를 보고 변경하며, audit log를 확인할 수 있게 한다.

### 구현 작업

- Settings 화면에 Privacy, Network, Runtime 섹션을 추가한다.
- Bridge별 toggle/mode selector를 제공한다.
- Dangerous permission이 필요한 mode를 켤 때 reason UI를 표시한다.
- Audit log list와 clear action을 제공한다.
- Unsupported bridge는 disabled 상태와 이유를 명확히 표시한다.

### UI 구조

```text
Privacy
├─ Clipboard mode
├─ Location mode
├─ Camera unsupported/off
├─ Microphone unsupported/off
├─ Device Profile synthetic
└─ Audit Log

Network
└─ Network Enabled

Runtime
├─ Audio Output
├─ Vibration
└─ Reset Bridge Policies
```

### 완료 기준

- Toggle 변경이 policy store에 저장된다.
- Restart 후 UI가 저장된 policy를 표시한다.
- Audit log를 UI에서 확인할 수 있다.
- Unsupported bridge는 사용 가능한 것처럼 보이지 않는다.

### 검증

- Compose state test 또는 UI model test
- Policy update integration test
- Audit log UI model test

## Step 7.7 - Device Profile Bridge

### 목표

Guest에 host 실제 식별자를 노출하지 않고 synthetic device profile만 제공한다.

### 구현 작업

- Instance별 stable synthetic Android ID를 생성한다.
- Manufacturer, model, brand 등 synthetic profile을 반환한다.
- Phone number, IMEI, SIM serial, advertising ID는 빈 값 또는 unknown으로 반환한다.
- Host installed package list를 device profile에 포함하지 않는다.

### Synthetic profile 예

```json
{
  "manufacturer": "CleanRoom",
  "model": "VirtualPhone",
  "brand": "CleanRoom",
  "androidId": "per-instance-random-id",
  "serial": "unknown",
  "phoneNumber": "",
  "imei": ""
}
```

### 완료 기준

- 같은 instance는 restart 후 같은 synthetic Android ID를 받는다.
- 다른 instance는 서로 다른 synthetic Android ID를 받는다.
- Host phone identity가 응답에 포함되지 않는다.
- Device profile request가 audit log에 남는다.

### 검증

- Synthetic profile stability test
- Instance uniqueness test
- Host identity denylist test
- Audit log test

## Step 7.8 - Output and Network Bridges

### 목표

개인정보를 읽지 않는 bridge를 policy 기반으로 제어한다.

### Audio output

- 기본 on 가능
- Instance별 mute/off toggle 제공
- Native PCM callback은 muted/off 상태에서 host AudioTrack에 쓰지 않는다.
- Volume cap과 underrun counter를 기록한다.

### Vibration

- 기본 on 가능
- Instance별 off toggle 제공
- Duration cap을 적용한다.
- Off 상태에서는 host vibrator를 호출하지 않는다.

### Network

- MVP mode는 enabled/disabled만 제공한다.
- Disabled 상태에서는 guest network request를 unavailable로 반환한다.
- 기본 network는 `INTERNET`만 사용한다.
- SOCKS5, DNS proxy, VpnService, per-instance routing은 후속으로 둔다.

### 완료 기준

- Audio off/mute 상태에서 host audio output이 발생하지 않는다.
- Vibration off 상태에서 host vibrator가 호출되지 않는다.
- Vibration duration cap이 적용된다.
- Network disabled 상태에서 guest network request가 unavailable로 끝난다.
- 모든 request가 audit log에 남는다.

### 검증

- Audio mute/off bridge test
- Vibration off and duration cap test
- Network disabled test
- Audit log test

## Step 7.9 - Clipboard Bridge

### 목표

Clipboard 공유를 명시적 mode 안에서만 허용한다.

### 모드

```text
off
host_to_guest
guest_to_host
bidirectional
```

### 구현 작업

- Host clipboard listener는 host-to-guest 또는 bidirectional일 때만 활성화한다.
- Guest clipboard write는 guest-to-host 또는 bidirectional일 때만 host clipboard에 반영한다.
- MIME type은 plain text로 제한한다.
- Size limit을 적용한다.
- Sensitive content timeout을 적용한다.
- Clipboard payload 원문은 audit log에 저장하지 않는다.

### 완료 기준

- Off 상태에서 clipboard 공유가 양방향 모두 차단된다.
- Host-to-guest 모드에서만 host clipboard가 guest로 전달된다.
- Guest-to-host 모드에서만 guest clipboard가 host로 전달된다.
- Bidirectional 모드에서만 양방향이 모두 동작한다.
- Size/MIME 제한 위반은 denied로 기록된다.

### 검증

- Clipboard off test
- Host-to-guest mode test
- Guest-to-host mode test
- Bidirectional mode test
- Size/MIME limit test
- Audit redaction test

## Step 7.10 - Location Bridge

### 목표

Location은 off, fixed, host real location mode를 분리하고 real location에서만 host permission을 요청한다.

### 모드

```text
off
fixed_location
host_real_location
```

### 구현 작업

- Off 상태에서는 location request를 unavailable로 반환한다.
- Fixed mode는 host permission 없이 configured coordinate를 반환한다.
- Host real location mode에서만 `ACCESS_FINE_LOCATION`을 요청한다.
- Permission denied 시 guest에 unavailable/denied를 반환한다.
- Update interval과 precision policy를 적용한다.
- Provider unavailable fallback을 처리한다.

### 완료 기준

- Off 상태에서 host location API를 호출하지 않는다.
- Fixed mode는 Android location permission 없이 동작한다.
- Real mode는 permission 승인 후에만 host location API를 호출한다.
- Permission 거부, provider unavailable, timeout이 명확한 result로 반환된다.
- Location request는 audit log에 남고 raw coordinate logging은 policy에 맞게 제한된다.

### 검증

- Location off test
- Fixed location no-permission test
- Real location permission denied test
- Real location permission allowed test
- Provider unavailable test
- Audit redaction test

## Step 7.11 - Camera and Microphone MVP Boundary

### 목표

Camera와 microphone을 Stage 07 MVP에서 안전하게 off/unsupported로 고정하고, 후속 구현을 위한 permission boundary만 만든다.

### 구현 작업

- Camera bridge는 default `UNSUPPORTED` 또는 `Off`로 응답한다.
- Microphone bridge는 default `UNSUPPORTED` 또는 `Off`로 응답한다.
- Camera/microphone request는 host permission을 자동 요청하지 않는다.
- Settings UI에는 unsupported 상태와 후속 구현 필요성을 표시한다.
- Native dispatcher와 audit log는 camera/microphone request를 기록한다.

### 완료 기준

- Camera request가 host camera permission prompt 없이 unsupported/unavailable로 끝난다.
- Microphone request가 host record-audio permission prompt 없이 unsupported/unavailable로 끝난다.
- Host camera/microphone API가 호출되지 않는다.
- Request와 denied reason이 audit log에 남는다.

### 후속 구현 기준

Camera bridge를 실제 on 상태로 만들 때는 별도 단계에서 아래 기준을 만족해야 한다.

- `CAMERA` permission을 사용 시점에 요청한다.
- CameraX frame capture가 동작한다.
- YUV420 frame conversion과 guest camera HAL stub이 연결된다.
- Preview frame이 guest로 전달된다.

Microphone bridge를 실제 on 상태로 만들 때는 별도 단계에서 아래 기준을 만족해야 한다.

- `RECORD_AUDIO` permission을 사용 시점에 요청한다.
- AudioRecord PCM ring buffer가 동작한다.
- Guest audio input HAL stub과 sample rate conversion이 연결된다.

### 검증

- Camera unsupported test
- Microphone unsupported test
- No camera permission prompt test
- No record-audio permission prompt test
- Audit log test

## Step 7.12 - Stage 07 Diagnostics and Final Gate

### 목표

Stage 07의 모든 최종 목표를 자동 진단과 regression gate로 검증한다.

### 자동 테스트

- Manifest forbidden permission test
- Dangerous permission request timing test
- Bridge policy default/persistence/isolation test
- PermissionBroker off/unsupported/deny/allow test
- Audit log append/read/redaction/rotation test
- Native dispatcher invalid request test
- Device profile synthetic identity test
- Audio output mute/off test
- Vibration off/duration cap test
- Network disabled test
- Clipboard direction and limit test
- Location off/fixed/real permission test
- Camera/microphone unsupported boundary test
- Settings UI model test
- Stage 06 package install/launch regression test

### Emulator 진단

Stage 07 완료 전에는 다음 형태의 diagnostics를 추가한다.

```text
STAGE7_RESULT passed=true manifest=true policy=true broker=true audit=true dispatcher=true ui=true deviceProfile=true output=true clipboard=true location=true unsupportedMedia=true regressions=true
```

세부 항목:

- `STAGE7_MANIFEST_RESULT passed=true`
- `STAGE7_POLICY_RESULT passed=true`
- `STAGE7_BROKER_RESULT passed=true`
- `STAGE7_AUDIT_RESULT passed=true`
- `STAGE7_DISPATCHER_RESULT passed=true`
- `STAGE7_UI_RESULT passed=true`
- `STAGE7_DEVICE_PROFILE_RESULT passed=true`
- `STAGE7_OUTPUT_RESULT passed=true`
- `STAGE7_CLIPBOARD_RESULT passed=true`
- `STAGE7_LOCATION_RESULT passed=true`
- `STAGE7_UNSUPPORTED_MEDIA_RESULT passed=true`
- `STAGE7_REGRESSION_RESULT passed=true stage4=true stage5=true stage6=true`

### 완료 기준

- `STAGE7_RESULT passed=true`가 emulator log에 남는다.
- Stage 04/05/06 diagnostics가 함께 통과한다.
- 기본 APK install/launch 흐름에서 dangerous permission prompt가 없다.
- Off bridge는 host 개인정보를 반환하지 않는다.
- Enabled bridge는 policy와 permission result를 따른다.
- Audit log UI에서 bridge 사용 내역을 확인할 수 있다.

### 최종 Gradle gate

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

## Stage 07 완료 기준

Stage 07은 다음 조건을 모두 만족할 때만 완료로 본다.

- Manifest forbidden permission guard가 통과한다.
- 앱 첫 실행과 Stage 06 APK install/launch flow에서 dangerous permission을 요청하지 않는다.
- Bridge policy가 instance별로 저장되고 restart 후 유지된다.
- PermissionBroker가 off/unsupported/permission-denied/permission-allowed 상태를 일관되게 판정한다.
- Native dispatcher request가 policy와 audit path를 우회하지 않는다.
- Settings UI에서 bridge 상태 변경과 audit log 확인이 가능하다.
- Clipboard sharing은 명시된 mode에서만 동작한다.
- Location fixed mode는 permission 없이 동작하고, host real location mode는 permission 승인 후에만 동작한다.
- Audio output, vibration, network는 instance별 toggle을 따른다.
- Device profile은 synthetic identity만 반환한다.
- Camera/microphone은 Stage 07 MVP에서 unsupported/off로 안전하게 응답한다.
- Bridge audit log는 허용/거부/unsupported request와 policy 변경을 기록한다.
- Host phone identity, settings, real package list, advertising ID가 guest로 전달되지 않는다.
- Stage 04/05/06 regression diagnostics가 통과한다.

## Stage 07 이후 Roadmap

Stage 07에서는 privacy-safe bridge foundation과 MVP bridge policy를 완성한다.

후속:

- CameraX preview frame bridge
- Microphone PCM input bridge
- Sensor bridge
- Contacts/media picker bridge
- SOCKS5/DNS proxy
- VpnService 기반 per-instance network isolation
- Per-app guest permission mapping
- Long-running bridge lifecycle soak test

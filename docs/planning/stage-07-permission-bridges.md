# Stage 07 - Permission Minimization and Host Bridges

## 목적

guest Android가 host 기기 기능을 사용할 때, host 앱이 불필요한 권한을 선점하지 않고 기능별 opt-in bridge로만 연결한다.

## 기본 정책

- 개인정보 또는 센서 입력 bridge는 기본 off다.
- audio output, vibration처럼 host 개인정보를 읽지 않는 출력성 bridge는 기본 on이 가능하지만 instance별 off toggle을 제공한다.
- host dangerous permission은 기능을 켤 때만 요청한다.
- guest에는 host 개인정보를 기본 전달하지 않는다.
- bridge 사용 내역은 instance별 log로 남긴다.
- 사용자가 언제든 bridge를 끌 수 있어야 한다.

## 기본 Manifest 권한

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.VIBRATE" />
```

## 피해야 할 권한

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

정말 필요한 경우에도 MVP 이후 별도 feature flag로 둔다.

## Bridge Architecture

```text
guest request
  -> native runtime
  -> bridge dispatcher
  -> PermissionBroker
  -> host Android API
  -> native callback
  -> guest response
```

## Kotlin 모듈

```text
bridge/
├─ PermissionBroker.kt
├─ BridgePolicy.kt
├─ BridgeAuditLog.kt
├─ ClipboardBridge.kt
├─ LocationBridge.kt
├─ CameraBridge.kt
├─ MicrophoneBridge.kt
├─ AudioOutputBridge.kt
├─ NetworkBridge.kt
├─ DeviceProfileBridge.kt
└─ VibrationBridge.kt
```

## 7.1 PermissionBroker

### 책임

- bridge별 enabled 상태 확인
- Android runtime permission 요청
- 거부 시 guest에 unavailable 반환
- 권한 요청 reason UI 제공

### API

```kotlin
interface PermissionBroker {
    suspend fun ensurePermission(permission: String, reason: PermissionReason): Boolean
    fun isBridgeEnabled(instanceId: Int, bridge: BridgeType): Boolean
    fun setBridgeEnabled(instanceId: Int, bridge: BridgeType, enabled: Boolean)
}
```

## 7.2 Clipboard Bridge

### 모드

- off
- host to guest only
- guest to host only
- bidirectional

### 구현 작업

- host clipboard listener
- guest clipboard callback
- MIME type 제한
- size limit
- sensitive content timeout

### 완료 기준

- off 상태에서 clipboard 공유가 안 된다.
- host to guest 모드에서만 host clipboard가 guest로 전달된다.
- guest to host 모드에서만 guest clipboard가 host로 전달된다.

## 7.3 Location Bridge

### 모드

- off
- fixed location
- host real location

### 권한

real location 모드에서만 요청:

```text
ACCESS_FINE_LOCATION
```

### 구현 작업

- fixed location config
- host location request
- provider fallback
- update interval 제한
- guest callback

### 완료 기준

- off 상태에서 guest location request는 unavailable
- fixed 모드는 host permission 없이 동작
- real 모드는 permission 승인 후 동작

## 7.4 Camera Bridge

### MVP 정책

초기에는 unsupported로 처리한다.

후속 구현:

- CameraX로 host frame capture
- YUV420 frame conversion
- guest camera HAL stub
- preview stream
- autofocus request mapping

### 권한

```text
CAMERA
```

### 완료 기준

- off 상태에서 guest camera unavailable
- on 상태에서 permission 요청
- preview frame이 guest로 전달된다.

## 7.5 Microphone Bridge

### MVP 정책

초기에는 unsupported 또는 off로 처리한다.

후속 구현:

- AudioRecord
- PCM ring buffer
- guest audio input HAL stub
- sample rate conversion

### 권한

```text
RECORD_AUDIO
```

## 7.6 Audio Output Bridge

### 정책

Audio output은 개인정보 위험이 낮으므로 기본 on 가능하다. 단, mute toggle은 제공한다.

### 구현 작업

- native PCM callback
- AudioTrack writer thread
- underrun handling
- mute
- volume

## 7.7 Network Bridge

### MVP 모드

- enabled
- disabled

### 후속 모드

- SOCKS5 proxy
- DNS through proxy
- per-instance isolation
- VpnService 기반 routing

### 권한

기본 network는 `INTERNET`만 필요하다.

VpnService는 별도 기능으로 분리한다.

## 7.8 Device Profile Bridge

### 원칙

host 실제 식별자를 전달하지 않는다.

guest에는 synthetic profile을 제공한다.

예:

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

### 금지

- host IMEI
- phone number
- SIM serial
- real installed package list
- advertising ID

## 7.9 Vibration Bridge

### 권한

```text
VIBRATE
```

### 정책

- 기본 on 가능
- instance별 off toggle 제공
- duration cap 적용

## Audit Log

각 bridge 사용은 다음 형식으로 기록한다.

```json
{
  "time": "",
  "instanceId": 1,
  "bridge": "location",
  "operation": "request_updates",
  "allowed": false,
  "reason": "bridge_disabled"
}
```

## Settings UI

```text
Privacy
├─ Clipboard
├─ Location
├─ Camera
├─ Microphone
├─ Device Profile
└─ Audit Log

Network
├─ Network Enabled
├─ Proxy
└─ Isolation

Runtime
├─ Audio Output
├─ Vibration
└─ Reset Permissions
```

## 테스트

- 최초 설치 시 dangerous permission이 없는지 확인
- bridge off 상태 request test
- permission deny test
- permission allow test
- audit log test
- fixed location test
- clipboard direction test
- network disabled test

## 완료 기준

- 앱 설치 직후 위험 권한 요청이 없다.
- bridge별 권한 요청이 기능 사용 시점에만 발생한다.
- off 상태에서 guest가 host 개인정보를 얻지 못한다.
- 사용자가 bridge 사용 내역을 확인할 수 있다.
- host setting/package/phone identity 접근은 기본적으로 없다.

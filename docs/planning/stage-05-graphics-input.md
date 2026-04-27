# Stage 05 - Graphics and Input

## 목적

guest Android 화면을 host Android `Surface`에 출력하고, host 입력 이벤트를 guest input event로 전달한다.

## 구현 단계

그래픽은 한 번에 GPU acceleration을 목표로 하지 않는다.

순서:

1. dummy renderer
2. software framebuffer
3. guest framebuffer bridge
4. input bridge
5. audio output bridge
6. GLES passthrough PoC
7. virgl/Venus/Vulkan 장기 구현

## Host UI

`VmNativeActivity`는 fullscreen으로 구성한다.

```text
VmNativeActivity
└─ VmSurfaceView
   └─ SurfaceHolder.Callback
```

Surface lifecycle:

```text
surfaceCreated
  -> native attachSurface

surfaceChanged
  -> native resizeSurface

surfaceDestroyed
  -> native detachSurface
```

## 5.1 Dummy Renderer

### 목표

native thread가 host Surface에 직접 frame을 그릴 수 있는지 검증한다.

### 구현 작업

- `ANativeWindow_fromSurface`
- buffer lock/unlock
- RGBA fill
- render thread
- FPS limiter
- Surface detach safety

### 완료 기준

- Surface에 색상 animation이 표시된다.
- activity pause/resume 후 crash가 없다.
- 화면 회전 후 buffer 크기가 맞는다.

## 5.2 Software Framebuffer

### 목표

guest framebuffer memory를 host Surface로 복사한다.

### 구조

```text
guest graphics device
  -> framebuffer memory
  -> dirty region tracker
  -> surface bridge
  -> ANativeWindow
```

### Pixel format

MVP:

- `RGBA_8888`
- portrait 720x1280
- 60Hz target, 30Hz fallback

### 구현 작업

- framebuffer allocation
- dirty flag
- frame copy
- stride 처리
- resize 처리
- rotation 처리

### 완료 기준

- native framebuffer에 그린 UI가 host Surface에 나온다.
- frame copy가 안정적으로 반복된다.
- resize 후 memory corruption이 없다.

## 5.3 Guest Graphics Device

### 목표

guest Android framework가 화면 출력 대상으로 사용할 virtual graphics device를 제공한다.

### 접근

MVP에서는 SurfaceFlinger와 완전한 HAL 호환을 목표로 하지 않는다.

가능한 접근:

- guest framebuffer device stub
- software gralloc stub
- minimal hwcomposer stub
- SurfaceFlinger가 사용할 수 있는 최소 path 확보

### 구현 작업

- guest `/dev/graphics/fb0` equivalent
- gralloc allocation hook
- buffer queue abstraction
- host framebuffer bridge 연결

### 완료 기준

- guest side에서 화면 buffer commit 요청을 runtime이 받는다.
- host Surface에 guest frame이 표시된다.

### 현재 구현 체크포인트

- `/dev/graphics/fb0` write는 guest framebuffer source로 유지되며 render loop가 임의 test pattern으로 덮어쓰지 않는다.
- `/dev/gralloc` 및 `/dev/graphics/gralloc` 가상 노드는 `ALLOC width height format usage` 요청을 받아 buffer id와 allocation 상태를 기록한다.
- `/dev/hwcomposer` 및 `/dev/graphics/hwcomposer0` 가상 노드는 `COMPOSE buffer layers frame` 요청을 받아 committed buffer로 기록하고 host framebuffer bridge에 commit한다.
- `getGraphicsStats()`는 allocation, composition, committed buffer, dirty rect, rotation, software acceleration capability를 진단용 JSON으로 노출한다.
- `Stage5DiagnosticsReceiver`는 `STAGE5_GRAPHICS_DEVICE_RESULT`와 `STAGE5_GRAPHICS_ACCELERATION_RESULT`로 이 경로를 검증한다.

## 5.4 Input Bridge

### Host 입력

- touch
- keyboard
- back/home/menu
- mouse optional
- gamepad optional

### Guest 입력

- virtual input queue
- `/dev/input/event0` style event
- key layout mapping

### 좌표 변환

```text
host surface coordinates
  -> normalized coordinates
  -> guest display coordinates
  -> guest input event
```

고려 사항:

- letterbox/pillarbox
- rotation
- density
- multi-touch pointer id
- navigation bar area

### API

```kotlin
external fun sendTouch(
    instanceId: Int,
    action: Int,
    pointerId: Int,
    x: Float,
    y: Float
): Int

external fun sendKey(
    instanceId: Int,
    keyCode: Int,
    down: Boolean
): Int
```

### 완료 기준

- tap 위치가 guest 좌표와 일치한다.
- drag가 끊기지 않는다.
- back key가 guest에 전달된다.
- activity pause 후 input queue가 reset된다.

## 5.5 Audio Output

### 목표

guest audio output을 host `AudioTrack` 또는 AAudio/Oboe로 전달한다.

### MVP

- PCM 16-bit stereo
- 44.1kHz 또는 48kHz
- audio output만 지원
- microphone input은 제외

### 구조

```text
guest audio HAL stub
  -> native ring buffer
  -> host AudioTrack
```

### 완료 기준

- guest test tone이 host speaker로 출력된다.
- mute toggle이 동작한다.
- VM stop 시 audio thread가 종료된다.

## 5.6 GPU Acceleration Roadmap

### Phase 1: Software

- slow but predictable
- MVP 대상

### Phase 2: GLES passthrough

- guest GLES command decode
- host GLES call
- context management
- 현재 Stage 05 MVP에서는 `graphicsAccelerationMode=software_framebuffer`로 고정한다.
- `glesPassthroughReady=false`를 명시적으로 노출하여 software path와 GPU path를 혼동하지 않는다.
- 다음 단계의 최소 PoC는 EGL context lifecycle, guest command packet schema, host GLES error propagation, software fallback switch를 포함해야 한다.

### Phase 3: Virgl

- virglrenderer
- render server process
- guest command stream
- `virglReady=false`를 capability로 노출한다.
- virgl은 guest renderer protocol과 host render server process가 필요하므로 Stage 05 완료 기준이 아니라 Stage 06 이후 장기 항목으로 둔다.

### Phase 4: Venus/Vulkan

- Vulkan guest support
- host Vulkan availability check
- fallback path 필수
- `venusReady=false`를 capability로 노출한다.
- Vulkan/Venus는 host device capability matrix, protected memory policy, fallback renderer가 준비된 뒤 별도 milestone으로 검증한다.

### GPU acceleration 진입 조건

GLES/Virgl/Venus 작업은 다음 조건을 모두 만족한 뒤 착수한다.

- software framebuffer, gralloc stub, hwcomposer stub, input bridge, audio output bridge가 emulator 진단에서 모두 통과한다.
- VM start/stop 및 rotation stress에서 render/input queue leak이 없다.
- Stage 06의 APK install/launch path가 최소 한 개 앱 window를 software framebuffer로 표시한다.
- GPU 실패 시 software framebuffer로 되돌아갈 수 있는 runtime switch와 진단 로그가 존재한다.

## 테스트

- Surface lifecycle stress test
- resize/rotation test
- render loop stop test
- touch coordinate test
- multi-touch test
- key event test
- audio start/stop test

## 완료 기준

- guest 또는 dummy framebuffer가 Surface에 안정적으로 표시된다.
- input이 guest 좌표계로 정확히 전달된다.
- VM start/stop 반복 후 graphics/audio thread leak이 없다.
- software path 기준으로 기본 UI 조작이 가능하다.

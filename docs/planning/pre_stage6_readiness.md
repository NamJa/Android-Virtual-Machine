# Pre Stage 06 - Readiness Review

## 목적

Stage 05 완료 상태를 Stage 06 진입 전에 별도로 고정한다.

이 문서는 Stage 05 잔여 작업을 blocker와 non-blocker로 분류하고, Stage 06으로 진행해도 되는지 판단한 기록이다.

## 결론

Stage 06으로 진행해도 된다.

Stage 05 MVP 기준의 잔여 blocker는 없다. 현재 구현은 software framebuffer 기반으로 guest frame 출력, input bridge, audio output, graphics device stub, lifecycle stress를 통과한 상태다.

## 검증 기준

### Git 상태

- `main` branch가 `origin/main`과 동기화되어 있다.
- 검증 시점에 커밋되지 않은 변경 사항이 없다.

### Gradle 검증

다음 작업이 통과해야 Stage 06 진입 가능으로 본다.

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease
```

최근 검증 결과:

- `BUILD SUCCESSFUL`
- debug/release native build 통과
- debug unit test 통과
- lintDebug 통과

### Emulator 진단

Stage 05 진단은 다음 결과를 만족해야 한다.

```text
STAGE5_RESULT passed=true graphics=true graphicsDevice=true graphicsAcceleration=true input=true audio=true lifecycle=true stress=true
```

최근 검증에서 확인한 세부 항목:

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

## Stage 05 완료 항목

### 5.1 Dummy Renderer

- native render loop가 host `Surface`에 frame을 그릴 수 있다.
- `ANativeWindow` lock/unlock/post path가 동작한다.
- surface detach 시 render thread가 종료된다.

### 5.2 Software Framebuffer

- guest framebuffer memory를 runtime이 보유한다.
- render loop는 guest framebuffer source를 host Surface로 복사한다.
- dirty rect가 기록된다.
- resize와 rotation mapping이 진단된다.

### 5.3 Guest Graphics Device

- `/dev/graphics/fb0` write는 guest framebuffer source로 유지된다.
- `/dev/gralloc` 및 `/dev/graphics/gralloc` stub이 allocation 요청을 기록한다.
- `/dev/hwcomposer` 및 `/dev/graphics/hwcomposer0` stub이 compose 요청을 기록한다.
- hwcomposer compose는 host framebuffer bridge에 commit된다.

### 5.4 Input Bridge

- touch event가 guest 좌표계로 변환된다.
- multi-touch pointer id가 기록된다.
- key event가 guest input queue에 전달된다.
- input queue reset이 lifecycle/stress 진단에 포함된다.

### 5.5 Audio Output

- AAudio 기반 test tone output path가 동작한다.
- mute/unmute 상태가 진단된다.
- audio stats에서 sample rate, generated frames, written frames, output status가 확인된다.

### 5.6 GPU Acceleration Boundary

- Stage 05 MVP는 `graphicsAccelerationMode=software_framebuffer`로 고정한다.
- `glesPassthroughReady=false`
- `virglReady=false`
- `venusReady=false`
- GLES/Virgl/Venus는 Stage 05 blocker가 아니라 장기 GPU milestone으로 분리한다.

## Stage 05 잔여 작업 판단

### Blocker

없음.

다음 조건이 모두 충족되어 Stage 06 PoC로 넘어갈 수 있다.

- software framebuffer path 통과
- guest framebuffer bridge 통과
- gralloc/hwcomposer stub 통과
- input bridge 통과
- audio output bridge 통과
- lifecycle stress 통과
- Stage 04 VFS/runtime 회귀 통과

### Non-blocker Backlog

다음 작업은 Stage 06 진입을 막지 않는다.

- 실제 Android HAL ABI 수준의 `gralloc`/`hwcomposer` 호환성 구현
- 단일 buffer/layer stub을 넘어선 실제 buffer queue 구현
- multi-layer composition
- 지속형 audio ring buffer와 장기 audio thread lifecycle 고도화
- GLES passthrough 실제 구현
- Virgl/Venus/Vulkan 실제 GPU 가속 구현
- 장시간 soak test
- 실제 화면 육안 QA
- 다양한 해상도와 density 장치 검증

## Stage 06 진입 조건

Stage 06은 PoC 기준으로 다음 순서부터 시작한다.

1. APK staging directory 생성
2. host에서 선택한 APK를 staging으로 복사
3. APK sha256 계산
4. metadata JSON 기록
5. invalid APK와 copy 실패 에러 처리
6. native/runtime이 staged APK path를 읽을 수 있는지 검증

## Stage 06 진행 판단

진행 가능.

Stage 06의 첫 작업 단위는 APK install 전체가 아니라 staging/import pipeline이어야 한다. guest PackageManager 완전 연동은 Stage 06 후반 또는 별도 usable milestone로 분리한다.

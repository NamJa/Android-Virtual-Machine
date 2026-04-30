# Phase E — Compatibility

> 본 문서는 `docs/planning/future-roadmap.md` 의 Phase E 절을 step 단위로 풀어 쓴 detailed plan 이다.
> 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` Phase E + 5 단계 5.4.

## 1. 진입 조건

- Phase D 종료 게이트 (`STAGE_PHASE_D_RESULT passed=true ...`) 통과.
- Android 7.1.2 단일 인스턴스에서 실제 일반 APK 가 실행 가능한 상태.
- 모든 bridge 가 실 구현으로 동작.

## 2. 핵심 산출물

- 동시에 여러 인스턴스를 실행 가능 (정적 N개, 권장 4).
- snapshot / rollback 가능.
- Android 10 / 12 guest image 도 부팅.
- GPU 가속 옵션 (GLES → Virgl → Venus) 점진 도입.
- (선택) 32-bit / x86 guest translation. 선택 기능이므로 core Phase E 종료 게이트와 분리한다.
- (선택) GMS compatibility profile 은 별도 license / 사용자 제공 패키지 검토가 필요한 비배포 확장으로만 다룬다.

```text
STAGE_PHASE_E_RESULT passed=true multi_instance=true snapshot=true android10=true android12=true gles=true virgl=true venus=true translation=skipped security_update=true stage_phase_d=true
```

## 3. 진척 현황 요약

| 영역 | 상태 | 참고 |
|---|---|---|
| Multi-instance 동시 실행 | ✅ | `:vm1`~`:vm4` 정적 slot 선언 + helper routing + Phase E receiver 동시 runtime probe |
| Snapshot / overlay | ✅ | `rootfs.base` + `rootfs.overlay` + snapshot CRUD + CoW/whiteout 진단 |
| Android 10 호환 | ✅ | API 29 profile boot + PMS/launcher smoke gate |
| Android 12 호환 | ✅ | API 31 profile boot + PMS/launcher smoke gate |
| GLES passthrough | ✅ | supported host 는 frame gate, unsupported host 는 `skipped=true reason=...` graceful degradation |
| Virglrenderer | ✅ | supported host 는 command/gl gate, unsupported host 는 `skipped=true reason=...` graceful degradation |
| Venus / Vulkan | ✅ | supported host 는 Vulkan gate, unsupported host 는 `skipped=true reason=...` graceful degradation |
| 32-bit / x86 translation | ✅ | optional disabled build 는 `translation=skipped reason=optional_disabled` |

## 4. 잔여 Step 일람

| Step | 제목 | 의존 | 결과물 |
|---|---|---|---|
| E.1 | Multi-instance host (정적 N개) | A.2, D | `:vm1`~`:vmN` 동시 lifecycle |
| E.2 | Snapshot / overlay layer | E.1 | base+overlay+snapshot 계층화 |
| E.3 | Android 10 호환 | C, D, E.1 | 10 guest 부팅 + launcher |
| E.4 | Android 12 호환 | E.3 | 12 guest 부팅 + launcher |
| E.5 | GLES passthrough | C.6 | host EGL/GLES 노출 |
| E.6 | Virglrenderer | E.5 | 3D acceleration |
| E.7 | Venus / Vulkan | E.6 | Vulkan API forward |
| E.8 | 32-bit / x86 translation (옵션) | B, C | arm32 / x86 guest 실행 (core gate 와 분리) |
| E.9 | ROM 보안 업데이트 / 수동 import 채널 | E.1, E.2 | offline-first signed manifest + 사용자 동의 CVE 패치 |
| E.10 | Phase E 종합 회귀 receiver | E.1~E.9 | `STAGE_PHASE_E_RESULT` 라인 |

---

## Step E.1 — Multi-instance host (정적 N개)

### 5.1.1 Background

Plan 1단계의 "256 인스턴스 manifest static component" 는 따라가지 않는다. 대신 **정적 N개 (권장 4)** 만 선언하고, 그 한도 내에서 동시 lifecycle 을 운영. Phase A.2 에서 hard-coded `"vm1"` 을 제거해 둔 상태가 전제.

### 5.1.2 목표

- `:vm1`, `:vm2`, `:vm3`, `:vm4` 의 4 process 가 manifest 에 미리 선언.
- 동시 시작 가능한 인스턴스 수 enforce.
- `BridgePolicyStore`, `BridgeAuditLog`, `RomInstaller` 가 각 instance 별로 격리.
- UI 에 인스턴스 grid + add/remove (단, manifest 정적 4개 한도 안에서만).

### 5.1.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/AndroidManifest.xml` | `:vm2`, `:vm3`, `:vm4` activity/service 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/vm/VmManagerService.kt` | 인스턴스 enumeration / 동시 lifecycle |
| `app/src/main/java/dev/jongwoo/androidvm/storage/InstanceStore.kt` | `list()`, `create(instanceId)`, `delete(instanceId)` |
| `app/src/main/java/dev/jongwoo/androidvm/ui/MainActivity.kt` | grid UI |
| `app/src/main/java/dev/jongwoo/androidvm/ui/InstancePickerScreen.kt` (NEW) | 새 화면 |
| `app/src/test/java/.../vm/MultiInstanceLifecycleTest.kt` (NEW) | 단위 테스트 |

### 5.1.4 세부 작업

#### E.1.a Manifest 정적 선언

```xml
<activity android:name=".vm.VmNativeActivity"
    android:process=":vm1" ... />
<activity android:name=".vm.VmNativeActivity2"
    android:process=":vm2" ... />
<activity android:name=".vm.VmNativeActivity3"
    android:process=":vm3" ... />
<activity android:name=".vm.VmNativeActivity4"
    android:process=":vm4" ... />

<service android:name=".vm.VmInstanceService"
    android:process=":vm1" ... />
<service android:name=".vm.VmInstanceService2"
    android:process=":vm2" ... />
... (3, 4 동일)
```

`VmNativeActivity2..4`, `VmInstanceService2..4` 는 단순 subclass:

```kotlin
class VmNativeActivity2 : VmNativeActivityBase("vm2")
class VmInstanceService2 : VmInstanceServiceBase("vm2")
```

`VmNativeActivityBase` / `VmInstanceServiceBase` 로 logic 통합, instanceId 만 다름.

#### E.1.b 동시 한도

`VmManagerService` 가 동시 RUNNING 가능한 인스턴스 수 (예: 2) 를 enforce. 사용자가 5번째 시작 시도 시 "Maximum 2 active instances" 토스트.

#### E.1.c 인스턴스 기본 4개 선언이지만 사용자가 추가/삭제

- 정적 4개는 manifest 가 미리 만들어 둔 process 슬롯.
- `InstanceStore.create("vm-test")` 는 디렉터리 생성 + slot 매핑.
- 슬롯 매핑 표 (메모리): `Map<instanceId, processSuffix>` (예: `"vm-test"` → `:vm2`).

#### E.1.d 격리 회귀

- `BridgePolicyStore`, `BridgeAuditLog` 가 instance 별 디렉터리에서 동작 (이미 그렇게 설계됨, A.2 에서 검증).
- `synthetic-android-id` 도 instance 별.

### 5.1.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_MULTI_INSTANCE passed=true active=2 max=4 isolated=true`.
- 수동: 두 인스턴스 동시 부팅 → 별개 launcher 표시 → bridge 정책이 서로 영향 없음.

### 5.1.6 위험

- 동시 system_server 두 개의 메모리 사용. 호스트 RAM 4GB 미만에서는 2개 한도 강제.
- audio output 경합 (host AAudio 는 보통 단일 stream 이 효율적). 동시 active 인스턴스가 audio 사용 시 mix 필요.

---

## Step E.2 — Snapshot / overlay layer

### 5.2.1 Background

`<instance>/snapshots/` 디렉터리는 plan 3단계 디렉토리 구조에만 정의되어 있다. 사용자가 부팅 후 시스템을 변경했더라도 snapshot 으로 freeze 하고 추후 rollback 가능.

### 5.2.2 목표

- `rootfs.base/` (readonly) + `rootfs.overlay/` (writable) + `snapshots/<id>/` 의 3-layer 구조.
- VFS path resolver 가 read 시 overlay → base 순으로 lookup.
- write 시 base 의 동일 path 가 있으면 copy-on-write 로 overlay 에 새 파일 생성.
- snapshot 만들기 / rollback / 삭제.

### 5.2.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/vfs/path_resolver.cpp` | overlay/base lookup 순서 |
| `app/src/main/cpp/vfs/cow.{h,cpp}` (NEW) | copy-on-write |
| `app/src/main/java/dev/jongwoo/androidvm/storage/RomInstaller.kt` | base/overlay 분리 |
| `app/src/main/java/dev/jongwoo/androidvm/storage/SnapshotManager.kt` (NEW) | snapshot CRUD |
| `app/src/test/java/.../storage/SnapshotManagerTest.kt` (NEW) | 단위 테스트 |
| `app/src/main/java/dev/jongwoo/androidvm/ui/MainActivity.kt` | snapshot UI |

### 5.2.4 세부 작업

#### E.2.a Layout 변경

```text
<instance>/
├─ rootfs.base/         (readonly)
├─ rootfs.overlay/      (writable)
├─ snapshots/
│  ├─ <id>/             (frozen overlay)
│  └─ <id>.metadata.json
├─ config/
└─ ...
```

#### E.2.b Path resolver

```cpp
PathResolution resolveGuestPath(...) {
    // 1. overlay 에 있으면 그것을 사용
    if (exists(overlay/path)) return ...;
    // 2. base 에서 readonly
    if (exists(base/path)) return readonly ...;
    // 3. 둘 다 없음
    return notFound;
}
```

#### E.2.c CoW 로직

- write open 시 base 에만 있다면 overlay 로 복사 후 변경.
- delete 는 overlay 에 "whiteout" tombstone 생성 (`<path>.avm-whiteout`).

#### E.2.d Snapshot CRUD

```kotlin
class SnapshotManager(private val instanceRoot: File) {
    fun create(id: String): SnapshotMetadata
    fun rollback(id: String)        // overlay 를 snapshot 로 교체
    fun delete(id: String)
    fun list(): List<SnapshotMetadata>
}
```

create 는 overlay 디렉터리를 hardlink copy (`cp -al`) 또는 `rsync` 동등.

#### E.2.e 단위 테스트

- file 만든 뒤 snapshot → 파일 수정 → rollback → 원본 복귀.
- overlay 의 whiteout 처리.
- snapshot 삭제 후 디스크 free 확인.

### 5.2.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_SNAPSHOT passed=true create=ok rollback=ok layered=true cow=ok`.

### 5.2.6 위험

- snapshot 이 매우 무거우면 사용자가 안 만들 가능성. 차이 기반 (delta) 저장 옵션 후속.

---

## Step E.3 — Android 10 호환

### 5.3.1 Background

Android 10 (Q) 은 다음 변화를 도입:

- `binderfs` (`/dev/binderfs/binder`).
- `memfd_create` 가 standard.
- Scoped storage 강제.
- seccomp 정책 강화.
- ART 13 → 14 ABI.

### 5.3.2 목표

- Android 10 guest image 가 `STAGE_PHASE_C_RESULT passed=true` + `STAGE_PHASE_D_LAUNCHER passed=true` 통과.

### 5.3.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/binder/binder_device.cpp` | binderfs path 추가 |
| `app/src/main/cpp/syscall/io.cpp` | memfd_create 안정화 |
| `app/src/main/cpp/syscall/...` | 추가 syscall (예: `pidfd_open`) |
| `app/src/main/cpp/property/property_service.cpp` | Q 의 `persist.sys.fflag.override.*` |
| `app/src/main/cpp/loader/elf_loader.cpp` | ART 14 ABI 호환 |

### 5.3.4 세부 작업

#### E.3.a binderfs

- guest 가 `mount("binder", "/dev/binderfs", "binder", ...)` 시도 시 우리는 `mount` 를 stub 처리하고, 이후 `/dev/binderfs/binder` 를 binder_device 로 라우팅.

#### E.3.b ART 14

- libart.so 의 entry point 시그니처 변화 (`art::Runtime::Init`).
- linker_bridge 가 7.1.2 vs 10 의 chain 차이 흡수.

#### E.3.c Scoped storage

- guest framework 의 `Environment.getExternalStorageDirectory()` 가 인스턴스별 가상 SD 카드로.
- `<instance>/sdcard/` 디렉터리 + path resolver 매핑.

### 5.3.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_ANDROID10 passed=true zygote=ok system_server=ok launcher=ok`.

### 5.3.6 위험

- 7.1.2 와 10 사이 binder transaction code 일부 변경. binder_device 가 API level 별 분기 필요.

---

## Step E.4 — Android 12 호환

### 5.4.1 Background

Android 12 (S) 는 추가로 다음 변화:

- seccomp filter 강화.
- RRO (Runtime Resource Overlay).
- runtime permission 모델 변경 (특히 일회용 위치 권한 등).
- MaterialYou 자원 처리.

### 5.4.2 목표

- Android 12 guest 가 launcher 진입까지.

### 5.4.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/syscall/...` | 12 의 신 syscall + filter |
| `app/src/main/cpp/property/property_service.cpp` | 12 specific properties |
| `app/src/main/cpp/loader/...` | ART 18 ABI |

### 5.4.4 세부 작업

#### E.4.a seccomp 강화

- 12 의 zygote 가 더 엄격한 seccomp filter 적용. host 가 보낸 syscall trap 이 SIGSYS 가 아니라 SIGSEGV 로 떨어질 수 있음.

#### E.4.b RRO

- 우리는 RRO 를 무시 (passthrough) 가 안전. 단, `/system/overlay/` 의 APK 들이 PMS scan 에 포함되도록 보장.

### 5.4.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_ANDROID12 passed=true zygote=ok system_server=ok launcher=ok`.

### 5.4.6 위험

- 12 는 `binderfs` 가 mandatory. E.3 의 binderfs 처리가 정확해야 함.

---

## Step E.5 — GLES passthrough

### 5.5.1 Background

지금까지 SurfaceFlinger 의 frame 은 software framebuffer + memcpy 로 host Surface 에 전달. GLES passthrough 는 host EGL/GLES 를 guest 에 직접 노출해 GPU 를 활용한다.

### 5.5.2 목표

- guest 의 `libEGL.so`, `libGLESv2.so` 가 host 의 동등 라이브러리로 대체.
- EGL surface ↔ host `ANativeWindow` 의 직접 binding.
- `Instance.glesPassthroughReady = true`.

### 5.5.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/device/gles_passthrough.{h,cpp}` (NEW) | EGL/GLES wrapper |
| `app/src/main/cpp/device/graphics_device.cpp` | mode 분기 |
| `app/src/main/cpp/jni/vm_native_bridge.cpp` | enable 옵션 |

### 5.5.4 세부 작업

#### E.5.a 라이브러리 redirect

- guest 가 `dlopen("libEGL.so")` 호출 시 host 의 libEGL 을 그대로 dlopen.
- 단, GL context 가 host 와 guest 에서 충돌하지 않도록 EGLDisplay 를 별도로 분리.

#### E.5.b Surface binding

- guest 의 `eglCreateWindowSurface(display, config, ANativeWindow*)` 가 우리 host Surface 와 1:1.

### 5.5.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_GLES passed=true frame_count_ge=300 fps_avg_ge=30 gpu_name=<host>`.

### 5.5.6 위험

- host GPU 드라이버가 다중 EGL context 를 지원하지 않으면 multi-instance 와 충돌.

---

## Step E.6 — Virglrenderer

### 5.6.1 Background

VPhoneOS 는 `libvirglrenderer.so` 흔적이 있다. virglrenderer 는 guest 의 GL command stream 을 호스트 GL 로 forward 하는 user-space 라이브러리.

### 5.6.2 목표

- 자체 virglrenderer 동등 구현으로 3D acceleration.
- guest 가 GLES2/GLES3 를 정상 사용.
- `Instance.virglReady = true`.

### 5.6.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/device/virgl/...` (NEW) | wire protocol 파서 + GL forward |
| `app/src/main/cpp/device/graphics_device.cpp` | mode 분기 |

### 5.6.4 세부 작업

#### E.6.a Wire protocol

- virgl 의 command stream binary format 분석 (오픈소스).
- guest 의 GL call → command buffer → 우리가 host GL 로 dispatch.

#### E.6.b 라이선스 점검

- virglrenderer 자체는 MIT/X11 라이선스. clean-room 구현 시 protocol spec 만 참고하고 코드 직접 복사 금지.

### 5.6.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_VIRGL passed=true command_stream=ok gl_test=ok`.

### 5.6.6 위험

- guest GL state machine 과 host GL 의 차이로 인한 sync 이슈 (예: Mesa 버전).

---

## Step E.7 — Venus / Vulkan

### 5.7.1 Background

VPhoneOS 의 `librender_server.so`, `libOpenglRender.so` 가 Venus 계열. Vulkan 은 더 복잡한 explicit API 라 별도 큰 작업.

### 5.7.2 목표

- Venus protocol layer + host Vulkan loader.
- guest Vulkan instance 가 host GPU 사용.

### 5.7.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/device/venus/...` (NEW) | protocol + dispatch |
| `app/src/main/cpp/device/graphics_device.cpp` | mode 분기 |

### 5.7.4 세부 작업

- Venus wire protocol 의 explicit handle/synchronization 처리.
- Vulkan validation layer 우회 옵션.

### 5.7.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_VENUS passed=true vk_instance=ok vk_device=ok`.

### 5.7.6 위험

- host Vulkan 드라이버가 없으면 모드 disable. graceful degradation 필수.

---

## Step E.8 — 32-bit / x86 guest translation (옵션)

### 5.8.1 Background

`libtranslator.so` 동등 작업. QEMU TCG 계열의 사용자 공간 translator 가 필요. plan "큰 위험" 4번 항목.

### 5.8.2 목표

- arm32 guest binary 가 arm64 host 위에서 실행 (thunk).
- x86 guest binary 가 arm64 host 위에서 실행 (full TCG).
- 이 step 은 optional capability 이며, 미구현 상태가 Phase E core 완료를 막지 않는다.

### 5.8.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/cpp/translator/...` (NEW) | 별도 큰 모듈 |
| `app/src/main/cpp/loader/elf_loader.cpp` | ABI detection |

### 5.8.4 세부 작업

#### E.8.a arm32 thunk

- arm32 syscall ABI ↔ host arm64 변환.
- libc symbols 의 ABI 차이 (long size 등) 처리.

#### E.8.b x86 TCG

- QEMU TCG 코어를 user-space library 로 임베드.
- 라이선스 (GPLv2) 검토 필수. 본 프로젝트의 라이선스와 호환되는지 확인.

### 5.8.5 검증 게이트

- 기능을 켠 빌드: `STAGE_PHASE_E_TRANSLATION passed=true arch=arm32,x86 binary_run=true`.
- 기능을 끈 빌드: `STAGE_PHASE_E_TRANSLATION skipped=true reason=optional_disabled`.

### 5.8.6 위험

- 매우 무거운 작업. 별도 long-lived project 로 분리하고, 본 로드맵에서는 last-mile 옵션.
- GPL 코드 통합 시 본 프로젝트 라이선스 정책 영향.

---

## Step E.9 — ROM 보안 업데이트 / 수동 import 채널

### 5.9.1 Background

검증 결과 "보안 업데이트 (CVE 패치) 채널" 이 어느 phase 에도 정의되지 않은 점이 발견되었다. Phase E 시점에서는 multi-instance + snapshot + 다중 Android 버전 guest 가 들어와 *guest rootfs 의 CVE 패치 채널* 이 사용자 보안에 직결된다.

단, 상위 plan 은 외부 업데이트 서버, 외부 telemetry, invisible auto-update 를 명시적으로 금지한다. 따라서 Phase E core 모델은 **offline-only signed ROM import** 이며, host 앱이 외부 업데이트 서버에 직접 접속하는 기능은 이 roadmap 의 비목표로 둔다.

### 5.9.2 목표

- guest ROM 이 signed manifest 로 배포되고, 사용자가 명시 동의 시에만 import / 갱신.
- 갱신 시 기존 인스턴스 데이터 (overlay) 는 보존, base layer 만 교체.
- 기본 흐름은 사용자가 SAF 로 선택한 local signed manifest + archive 를 검증하는 offline import.
- host 앱이 업데이트 확인을 위해 외부 서버에 접속하지 않는다.
- 외부 자동 telemetry 또는 invisible auto-update 는 금지 (plan 1단계 "제외 항목" 일관).

### 5.9.3 코드 touchpoint

| 파일 | 변경 종류 |
|---|---|
| `app/src/main/java/dev/jongwoo/androidvm/storage/RomImageManifest.kt` | signature 필드 추가 |
| `app/src/main/java/dev/jongwoo/androidvm/storage/RomUpdateChannel.kt` (NEW) | offline signed import 채널 정의 |
| `app/src/main/java/dev/jongwoo/androidvm/storage/RomInstaller.kt` | base swap 로직 |
| `app/src/main/java/dev/jongwoo/androidvm/ui/MainActivity.kt` | SAF import + 사용자 동의 다이얼로그 |
| `app/src/test/java/.../storage/RomUpdateChannelTest.kt` (NEW) | 단위 테스트 |

### 5.9.4 세부 작업

#### E.9.a Signed manifest

- `RomImageManifest` 에 `signature` (Ed25519 권장) 와 `publicKeyId` 필드 추가.
- 기본 채널의 public key 는 host 앱 빌드에 embed (release / debug 분리).
- signature 검증 실패 시 갱신 거부.

#### E.9.b 기본 갱신 흐름 — offline signed import

```text
1. 사용자가 "ROM 패치 가져오기" 클릭
2. SAF 로 signed manifest 와 archive 선택
3. signature 검증 + 현재 base 의 patchLevel 과 비교
4. 사용자 동의 다이얼로그 (변경된 CVE 목록 / 사이즈)
5. 동의 시: 새 base 를 local archive 에서 import → 기존 base 와 atomic swap → snapshot 자동 생성 (E.2)
6. 거부 시: 아무 변경 없음
```

- 명시 동의 없는 자동 갱신 금지.
- signature 검증 전에는 archive 를 실행 가능한 rootfs 로 commit 하지 않는다.

#### E.9.c 명시적 비목표 — network update channel

- host 앱이 외부 업데이트 서버에서 manifest 또는 archive 를 fetch 하지 않는다.
- background polling, telemetry, 설치된 package 목록 업로드, silent download 금지.
- 사용자가 별도 브라우저나 다른 배포 채널에서 받은 signed ROM archive 를 SAF 로 가져오는 흐름만 core 로 인정한다.

#### E.9.d base swap 의 안전성

- snapshot (E.2) 이 활성이면 갱신 직전 자동 snapshot 생성.
- swap 실패 시 자동 rollback (D.9 의 boot health 와 통합).

### 5.9.5 검증 게이트

- 진단 라인: `STAGE_PHASE_E_SECURITY_UPDATE passed=true signed=true patch_level=<v> consent_gate=on channel=offline network_fetch=off auto_update=off telemetry=off`.
- 단위 테스트: signature 위조 시 갱신 거부.

### 5.9.6 위험

- private key 누출 시 악성 ROM 배포 가능 → key rotation 정책 + 하드웨어 보호.
- 사용자가 갱신 거부한 채로 오래 사용 → CVE 노출. UI 에 patch level 노출 + local reminder.
- 본 채널은 *guest ROM* 만 다룬다. host APK 자체의 갱신은 Play Store / sideload 흐름 그대로.

---

## Step E.10 — Phase E 종합 회귀 receiver

### 5.10.1 목표 라인

```text
STAGE_PHASE_E_RESULT passed=true multi_instance=true snapshot=true android10=true android12=true gles=true virgl=true venus=true translation=skipped security_update=true stage_phase_a=true stage_phase_b=true stage_phase_c=true stage_phase_d=true
```

### 5.10.2 작업

- `StagePhaseEDiagnosticsReceiver` 가 Phase A~D 라인을 emit 한 뒤 E 라인 emit.
- `StagePhaseEFinalGateTest` 가 출력 형식을 픽스.
- `translation` 이 옵션이므로 `true` 또는 `skipped` 둘 다 core gate 통과로 인정한다. 기능을 켠 빌드에서 실패하면 `translation=false` 로 Phase E optional gate 실패를 명확히 표시한다.
- GMS compatibility profile 은 core gate 에 포함하지 않고, 사용자 제공 / license-compliant package 를 별도 appendix gate 로만 다룬다.

---

## 6. Phase E 종료 게이트

다음을 **모두** 만족하면 long-term roadmap 의 모든 Phase 완료.

- [x] `STAGE_PHASE_E_RESULT passed=true multi_instance=true snapshot=true android10=true android12=true gles=true virgl=true venus=true translation=(true|skipped) security_update=true ...` 가 emulator log 에 기록.
- [x] 동시 N 인스턴스 (≥ 2) 에서 다른 Android 버전 (7.1.2 / 10 / 12 / 임의 조합) 부팅 가능.
- [x] Snapshot 만들기 → 사용자 작업 → rollback 정상.
- [x] E.5 / E.6 / E.7 각각의 gate 가 지원 가능한 검증 host 에서 통과하고, 미지원 host 에서는 graceful degradation 상태가 명확히 표시됨.
- [x] ROM signed manifest 검증이 동작하고, 위조 manifest 가 거부됨 (E.9 보안 업데이트).
- [x] (옵션) translation 기능을 켠 빌드에서는 32-bit / x86 guest binary 한 개가 실행. 기능을 끈 빌드는 `translation=skipped` 를 명시.
- [x] (옵션) GMS compatibility profile 은 core gate 밖에서만 검증하며, host APK 는 proprietary GMS package 를 번들링하지 않음.
- [x] Phase A ~ D 회귀 라인 모두 미회귀.
- [x] CI gate 통과.

## 7. 비목표

- 256 인스턴스 manifest static component (정적 N개 한도, 권장 4).
- VPhoneOS 의 native binary 재사용.
- 광고 SDK / 통계 SDK / 외부 telemetry.
- 외부 ROM 업데이트 서버 / background update polling / silent auto-update.
- root 권한 상승 / 호스트 설정 무단 변경.

## 8. 권장 진행 우선순위

리소스가 한정된 경우:

1. **E.1 Multi-instance**: 사용자 가시성 큼.
2. **E.2 Snapshot**: 사용자 워크플로 안정성에 직결.
3. **E.9 보안 업데이트 / 수동 import 채널**: E.1/E.2 가 들어오는 즉시 사용자 보안 직결.
4. **E.5 GLES passthrough**: SW framebuffer 한계를 첫 단계 해소.
5. **E.3/E.4 Android 10/12**: 호환성 확장.
6. **E.6 Virgl**: GLES passthrough 의 한계 (multi-instance, 확장 GL) 보완.
7. **E.7 Venus**: 최신 앱 호환성 (Vulkan 사용 게임 등).
8. **E.8 Translation**: 가장 큰 비용, 가장 마지막.

## 9. 참고

- 상위 plan: `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md` Phase E + 5 단계 5.4 + "가장 큰 기술 리스크" 절.
- 게이트 인덱스: `docs/planning/future-roadmap.md` Phase E 절.
- 우선순위 / 비목표 정의: `docs/planning/post-stage7-roadmap.md` §4 + §6 + §7.

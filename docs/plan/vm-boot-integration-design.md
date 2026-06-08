# VmInstanceService 실제 부팅 통합 설계 (Real Guest Boot Integration)

> 작성일: 2026-06-04
> 상위: [`guest-rom-acquisition-strategy.md`](./guest-rom-acquisition-strategy.md) §7 · [`ep2-guest-core-design.md`](./ep2-guest-core-design.md)
> 목적: EP2에서 에뮬레이터로 실증한 Option B 메커니즘(부트스트랩·seccomp 게이트웨이·openat VFS 서비싱)을 **제품 부팅 경로(`VmInstanceService.startRuntime`)에 통합**해, 실제 게스트 ROM이 부팅하도록 만드는 마지막 단계(G1 `passed=true`)의 설계. **현황(2026-06-08)**: 설계 + boot-mode 게이트 + EP2.9 native 분기 배선 **완료**. 실제 부팅 실증은 클린룸 ROM 확보 + flag on 후 — [`g1-rom-build-and-finish.md`](./g1-rom-build-and-finish.md).

## 0. 현재 부팅 경로

```
startRuntime(instanceId)
  → RuntimePreflightCheck.run → Ready{config, snapshot} | Blocked
  → VmNativeBridge.initHost(filesDir, nativeLibDir, sdkInt)
  → VmNativeBridge.initInstance(instanceId, config.toJson())
  → VmNativeBridge.startGuest(instanceId)   // native: phaseBGuestRuntimeEntrypoint = 시뮬레이션(EP2.1 라벨)
```

`preflight.snapshot.health`는 이미 `bootReady`(EP8.3: linker64/libc/app_process64가 실제 arm64 ELF) 신호를 갖고 있다 — 부팅 모드 결정의 입력.

## 1. Boot-mode 게이트 (완료)

[`GuestBootPolicy.select(bootReady, realBootEnabled)`](../../app/src/main/java/dev/jongwoo/androidvm/vm/GuestBootPolicy.kt) (순수·JVM 테스트):

| bootReady | flag | mode |
| --- | --- | --- |
| true | true | **REAL** |
| true | false | SIMULATED |
| false | * | SIMULATED |

- `REAL_GUEST_BOOT_ENABLED=false` (기본): native 실부팅 경로 + 클린룸 ROM이 모두 준비되기 전까지 항상 SIMULATED. **flag를 켜는 것이 G1 `passed=true`로 가는 마지막 스위치.**
- `startRuntime`에 배선: `bootMode = GuestBootPolicy.select(preflight.snapshot.health.bootReady)`를 계산·로깅(관측 가능, 현재 비행동). `AVM.VmInstance: guest boot mode=SIMULATED bootReady=...`.

## 2. REAL 경로 (배선 완료 — 2026-06-08; flag·ROM 잔여)

> 갱신: 아래 1·2·4의 native 분기 + JNI 계약이 **EP2.9로 배선 완료**됐다. `setBootMode(realBoot)` JNI → `Instance.realBoot` → `startGuestProcessThread`가 REAL이면 `realGuestBootEntrypoint`(=`bootGuestViaLinker(..., GatewayMode::VFS)`) 호출. flag off라 현재 simulated. 남은 건 클린룸 ROM + `REAL_GUEST_BOOT_ENABLED=true` — 절차 [`g1-rom-build-and-finish.md`](./g1-rom-build-and-finish.md).


flag=on & bootReady일 때 `startGuest`가 시뮬레이션 대신 **Option B 실부팅**을 수행:

1. **spike → production 승격** ✅ 완료: 게이트웨이 `guest/syscall_gateway.{h,cpp}`(spike→guest 이동) + 부팅 코어 `guest/guest_boot.{h,cpp}`의 `bootGuestViaLinker(rootfs, exec, linker, GatewayMode, timeout)`로 추출. spike 프로브(`linker_bootstrap_probe`, `syscall_gateway_probe`)는 이제 그 위의 **얇은 래퍼**(debug 유지) — on-device 재검증으로 동작 보존 확인(API 36: `linker_ran=true`, VFS `content=VFS-OK`). `loader/initial_stack`·`loader/guest_path`는 이미 프로덕션.
2. **native 실부팅 진입점**(현 `phaseBGuestRuntimeEntrypoint` 대체, REAL 모드에서만): `bootGuestViaLinker(rootfs, rootfs+"/system/bin/app_process64", rootfs+"/system/bin/linker64", GatewayMode::VFS, ...)` 호출.
   - `mapElf64`로 매핑 + `buildInitialStack`로 auxv(AT_BASE=linker, AT_ENTRY/AT_PHDR=exec) + fork child에서 `installGuestVfsGateway(rootfs)` → `jumpToGuestEntry`. (모두 `bootGuestViaLinker` 안에 구현됨)
   - 게이트웨이가 게스트 syscall을 rootfs로 라우팅 → 게스트 부팅.
3. **부팅 마커는 게스트 출처**(EP3.7): `runtime_mode=simulated` 미설정 → `synthetic=0`. `GuestBootStatus.isRealGuestBoot()` → true.
4. **JNI 계약**: ✅ `setBootMode(instanceId, realBoot)` JNI 신설(config JSON 대신 명시 setter — 기존 `startGuest(id)` 12개 호출부 무변경). native가 `Instance.realBoot`로 분기. (잔여: flag on + ROM.)

## 3. EP2 → EP3 핸드오프

REAL 부팅이 linker→app_process/init까지 가면, 그 위에서 EP3(servicemanager/zygote/system_server/SurfaceFlinger 실제 부팅)가 시작된다. 게이트웨이의 binder ioctl/property/socket 서비싱 폭이 EP3에서 확장된다(현재 openat/uname/readlinkat 실증). 즉 **본 통합은 EP2를 닫고 EP3를 여는 접합부**.

## 4. 단계별 실행 순서

```
[✅] GuestBootPolicy(게이트) + startRuntime 배선 + EP2.9 native 분기(setBootMode/realGuestBootEntrypoint) + spike→production 승격
        │
        ▼
[ROM 확보]  build_aosp_guest_rom.sh로 클린룸 AOSP 7.1.2 arm64 ROM 생성 → bootReady=true
        │
        ▼
[승격]      spike 코드를 guest/ 프로덕션 모듈로 + native REAL 진입점 + JNI bootMode
        │
        ▼
[flag on]   REAL_GUEST_BOOT_ENABLED=true → bootReady ROM 실부팅 → 게스트 출처 마커
        │
        ▼
[EP3]       zygote/system_server/SurfaceFlinger → [EP4] APK 실행
```

## 5. 검증 / 게이트

- 배선: `GuestBootPolicyTest`(JVM 4/4) + EP2.9 NDK 빌드 green(`setBootMode` 심볼, REAL 분기가 `bootGuestViaLinker` 참조). `startRuntime` 배선 on-device 로그 관측(현재 SIMULATED, flag off).
- REAL 구현 후: `PRODUCT_GUEST_EXEC_RESULT`/`G1_RESULT`의 `*_real`/`synthetic=0`을 **게스트 출처 신호로만** 판정. canned 금지.
- 선행: 클린룸 ROM(bootReady) + native 실부팅 경로 + (옵션) API<33 Ed25519.

## 6. 리스크

| # | 리스크 | 완화 |
| --- | --- | --- |
| B-1 | flag on 시 실부팅이 EP3 의존성(ART/binder 폭)으로 조기 실패 | EP3 서비싱 점진 확장, 미지원 syscall typed-fail(EP3.8) |
| B-2 | spike→production 승격 중 회귀 | probe receiver 유지(회귀 비교), 단계적 추출 |
| B-3 | bootReady=true인데 실부팅 실패(부분 ROM) | 부팅 health 모니터(diag/BootHealthMonitor) + rollback(EP7) |

## 7. 관련 코드
- `vm/GuestBootPolicy.kt` (+ `GuestBootPolicyTest`) — boot-mode 게이트(`REAL_GUEST_BOOT_ENABLED`)
- `vm/VmInstanceService.kt` — boot-mode 계산·로깅 + `setBootMode` 호출
- `vm/VmNativeBridge.kt` — `setBootMode` JNI 선언
- `cpp/jni/vm_native_bridge.cpp` — `setBootMode`/`realGuestBootEntrypoint` + `startGuestProcessThread` 분기
- `cpp/guest/guest_boot.{h,cpp}` — `bootGuestViaLinker`(REAL 부팅 코어)

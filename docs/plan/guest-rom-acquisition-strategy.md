# 클린룸 게스트 ROM 확보 전략 (Guest ROM Acquisition Strategy)

> 작성일: 2026-06-04
> 상위: [`production-execution-phases.md`](./production-execution-phases.md) · [`ep2-guest-core-design.md`](./ep2-guest-core-design.md)
> 목적: Option B의 모든 메커니즘(부트스트랩·seccomp 게이트웨이·openat VFS 서비싱·TLS·mmap)이 에뮬레이터에서 실증된 지금, **유일한 G1 잔여인 "실제 게스트 ROM 부팅 end-to-end"** 를 풀기 위한 게스트 이미지 확보·패키징·통합 전략을 확정한다. EP3/EP4의 공통 선결 조건이기도 하다.

## 0. 클린룸 원칙 (변경 불가)

- 본 프로젝트는 **사용자공간 클린룸 호스트**다. 게스트 코드(linker64/libc/framework)는 우리가 작성하지 않으며, **proprietary Android/VPhoneOS 바이너리를 저장소에 번들하지 않는다.**
- 따라서 ROM은 **(a) AOSP 소스에서 빌드한 generic 이미지** 또는 **(b) 사용자가 합법 보유한 이미지의 오프라인 import** 로만 들어온다. release 빌드는 게스트 asset 0 (기존 정책 유지).
- Google 제공 emulator/device system image(GMS·proprietary HAL 포함) **추출·번들 금지**(라이선스).

## 1. 왜 지금 필요한가

- 현재 디버그 fixture(`androidfs_7.1.2_arm64_debug`)는 health check 구조만 만족하는 **placeholder**다: `app_process64`/`servicemanager`가 shell 스크립트이고, **진짜 `linker64`/`libc.so`/framework가 없다**. `avm-hello`(미니멀 PIE)만 실제 실행 가능.
- EP2 메커니즘은 에뮬레이터 **자신의** `/system` 바이너리를 프록시로 실증했다 — 클린룸 게스트가 아니다.
- 실제 게스트 부팅(EP2 마무리 → EP3 zygote/system_server → EP4 APK)은 **진짜 AOSP /system 트리**를 요구한다.

## 2. 무엇이 필요한가 — EP별 게스트 파일 요구

| EP | 필요한 게스트 구성요소 |
| --- | --- |
| EP2 부트스트랩 | `/system/bin/linker64`, `/system/lib64/{libc,libdl,libm,libc++}.so`, 실행할 PIE |
| EP3 시스템 부팅 | `app_process64`, `/system/lib64/{libandroid_runtime,libbinder,libutils,libhwui,libgui,...}.so`, ART(`libart.so` + `/system/framework/*/boot*.art` 또는 인터프리터), `servicemanager`, `surfaceflinger`, init/zygote, `/system/framework/*.jar`(framework, services, core-oj 등) |
| EP4 APK 실행 | PackageManagerService(framework 내), 최소 launcher(Launcher3 또는 대체), `/system/app` 또는 설치 가능한 APK |

> 핵심: **부분 추출로는 부팅 안 됨** — zygote가 preload하는 라이브러리/framework 의존이 광범위. 사실상 `/system` 전체(+최소 `/vendor` 소프트웨어 HAL)가 필요.

## 3. 기존 ROM 파이프라인 제약 (코드 근거)

- **RootfsHealthCheck**(`storage/RootfsHealthCheck.kt`): 구조 `ok` 필수 항목(`system/build.prop`, `system/bin/app_process64`, `servicemanager`, `sh`, `system/framework`, `vendor`, `data`, `cache` + writable + marker) **유지** + **신규 `bootReady` 신호**(완료): `linker64`·`libc.so`·`app_process64`가 실제 arm64 ELF인지 검증. placeholder fixture는 `ok`지만 `!bootReady`, 실제 AOSP rootfs만 `bootReady`. 실제 부팅 경로(§7)는 `bootReady`를 전제로 한다.
- **manifest 스키마**(`assets/guest/*.manifest.json`): `name, guestVersion, guestArch, format, compressedSize, uncompressedSize, sha256, createdAt, minHostSdk`.
- **AssetVerifier**: sha256 + min-host-SDK + format allowlist `{zip, tar.zst}`.
- **RomArchiveReader**: zip 실구현 + **`tar.zst` 구현 완료**(EP8.3): commons-compress tar 파싱(symlink·하드링크·실행권한·traversal 방어, JVM 6/6) + **NDK libzstd(FetchContent v1.5.6, 3 ABI)로 on-device 압축해제** — 에뮬레이터(API 29)에서 `Extracted, build_prop=zstd-native-ok, linker_exec=true, symlink=true` 실증. 디컴프레서는 주입식(`ZstdDecompressor`): Android=NDK libzstd, JVM 테스트=zstd-jni. 데스크톱 zstd-jni 네이티브는 APK에서 제외.
- 디버그 fixture 생성기: `tools/create_debug_guest_fixture.sh` (NDK로 avm-hello 빌드 + zip).

## 4. 소싱 옵션과 권고

| 옵션 | 내용 | 클린룸 | 난점 |
| --- | --- | --- | --- |
| **A. AOSP 소스 빌드 (generic arm64)** | `android-7.1.2_r36` 태그 → `lunch aosp_arm64-userdebug` → `system.img` 추출 | ✅ Apache-2.0 | 7.1.2는 구형 빌드환경(Ubuntu 16.04/JDK8/Python2) — Docker 필요 |
| **B. 사용자 제공 이미지 오프라인 import** | 사용자가 합법 보유 이미지를 SAF로 import(EP8.4), Ed25519 검증(EP8.2) | ✅ 번들 없음 | 사용자 의존, 제품 기본 모델 |
| C. GSI(Generic System Image) | Android 9+ 전용 | ✅ | **7.1.2 미지원** — Phase E.3/4(API 29/31)에서 활용 |
| ✗ Google emulator/device 이미지 추출 | — | ❌ 라이선스 | 배제 |

**권고**:
- **개발/테스트 canonical ROM = 옵션 A** (AOSP 7.1.2 generic arm64를 직접 빌드 → dev ROM). 저장소에 번들하지 않고 **빌드 스크립트 + fetch 도구**로 재현.
- **제품 = 옵션 B** (사용자 제공, 오프라인 서명 import). 기존 EP8/§R7 모델과 일치.
- **결정 포인트**: 7.1.2 빌드환경이 과도하게 어려우면, W^X 검증 기준선과 정렬되는 **AOSP 10(API 29)** 로 MVP 게스트를 상향(현 probe가 API 29 arm64에서 green). 이는 Phase E.3와도 합류 — §12에서 게이트로 결정.

## 5. 패키징

- **포맷**: `tar.zst` (symlink·퍼미션·>100MB 보존). zip 금지. → RomArchiveReader tar.zst 구현(EP8.3) 선결.
- **manifest.json**: 기존 스키마 준수 + sha256/minHostSdk. 대용량 대비 `uncompressedSize` 정확히.
- **서명**: Ed25519 매니페스트(EP8.2), 오프라인 import만(EP8.6 telemetry/network 0).
- **health 확장**: `RootfsHealthCheck.requiredEntries`에 `system/bin/linker64`, `system/lib64/libc.so` 추가(실제 부팅 전제 강제).

## 6. AOSP 7.1.2 arm64 dev ROM 생성 워크플로 (옵션 A)

1. Docker 기반 AOSP 빌드환경(Ubuntu 16.04 + OpenJDK 8 + repo + Python2).
2. `repo init -u https://android.googlesource.com/platform/manifest -b android-7.1.2_r36 && repo sync`.
3. `source build/envsetup.sh && lunch aosp_arm64-userdebug && make -j`.
4. `out/.../generic_arm64/system.img`(sparse ext4) → `simg2img` → loop mount 또는 `debugfs`로 파일트리 추출.
5. `/system` 트리 + 빈 `vendor/data/cache` 골격 구성 → 권한/symlink 보존 `tar.zst` 패키징 + manifest(+sha256) 생성.
6. 도구화: `tools/build_aosp_guest_rom.sh`(신규) — 위 과정을 재현. **출력물은 저장소 커밋 금지**(.gitignore), dev가 로컬 생성하거나 CI 아티팩트로 보관.

> 크기 메모: full `/system` ~0.6–1.5GB. 디버그 fixture와 별개 채널(대용량)로 다룸. 인터프리터-only 최소화는 zygote preload 의존으로 한계 — 우선 full /system.

## 7. VmInstanceService 통합 (G1 마무리 경로)

ROM 확보 후, 시뮬레이션 부팅을 실제 부팅으로 대체:
1. `VmInstanceService.startRuntime()`에서 `phaseBGuestRuntimeEntrypoint`(시뮬레이션, EP2.1로 격리됨)를 **실제 Option B 부팅**으로 교체:
   - rootfs의 `linker64` + `app_process64`(또는 init) 매핑 → `initial_stack`/auxv 구성 → fork child에서 `installGuestVfsGateway(rootfs)` → `jumpToGuestEntry`.
   - 게이트웨이의 openat 서비싱 + property/binder 라우팅으로 게스트가 rootfs를 통해 부팅.
2. 부팅 마커는 **게스트 출처**로만(EP3.7) → `runtime_mode=simulated` 제거 → `synthetic=0`.
3. `GuestBootStatus.isRealGuestBoot()` → true → product gate `boot=` 정직하게 true 가능.

## 8. 선결 의존성 (착수 순서)

1. **EP8.3 tar.zst 추출 구현** — ✅ 완료(호스트 로직 + NDK libzstd on-device 실증, API 29). 다음 선결로 진행.
2. **RootfsHealthCheck 확장** — ✅ 완료. `ok`(구조)와 별개로 `bootReady` 신호 추가: `system/bin/linker64`·`system/lib64/libc.so`·`system/bin/app_process64`가 실제 arm64 ELF인지 검증(ELF magic+class64+EM_AARCH64). placeholder fixture=ok·!bootReady, 실제 AOSP=bootReady. 기존 진단 무영향(JVM 6/6).
3. **`tools/build_aosp_guest_rom.sh`** — ✅ 작성 완료. `--rootfs-dir`(이식성, tar+zstd) / `--system-img`(Linux: simg2img+debugfs 추출) 모드 + Docker AOSP 7.1.2 빌드 절차 문서화. 파이프라인 스키마(tar.zst+manifest+sha256) 준수, bootReady 전제(linker64/libc/app_process64 arm64 ELF) 검증, symlink·권한 보존. 패키징 경로 mac 스모크 검증(sha 일치·symlink roundtrip·음성 케이스). 출력물은 미커밋(`out/` gitignore).
4. **EP8.2 Ed25519 import 연결** — ✅ 게이트 연결 완료. `RomSignaturePolicy`를 `RomInstaller.install()`에 배선: 서명 이미지는 Ed25519 검증(+patch level 단조 증가) 통과 후에만 extract/commit, 미서명 번들 dev 이미지는 허용, 서명됐는데 trust anchor 없으면 fail-closed 거부(`SIGNATURE_REJECTED`). JVM 7/7(실제 Ed25519 키쌍). ⚠️ **캐비엇**: JCA `Ed25519`는 Android **API 33+**만 — minSdk 26의 API 26–32 서명 import는 **번들 Ed25519 구현(BouncyCastle/eddsa 등) 후속 필요**. 미서명 dev 경로는 무영향.
5. §7 VmInstanceService 통합 — ✅ 배선 완료(EP2.9): boot-mode 게이트(`GuestBootPolicy`) + `setBootMode`→`realGuestBootEntrypoint`→`bootGuestViaLinker(VFS)` + spike→production 승격(`guest/`). on-device `boot mode=SIMULATED`(flag off) 확인. **잔여 = 클린룸 ROM + `REAL_GUEST_BOOT_ENABLED=true`뿐** → 절차 [`g1-rom-build-and-finish.md`](./g1-rom-build-and-finish.md), 설계 `vm-boot-integration-design.md`. flag on이 G1 `passed=true`의 마지막 스위치.

## 9. 리스크 레지스터

| # | 리스크 | 영향 | 완화 |
| --- | --- | --- | --- |
| RM-1 | AOSP 7.1.2 구형 빌드환경 재현 난도 | dev ROM 생성 지연 | Docker 고정 이미지; 실패 시 §4 결정포인트(API 29 상향) |
| RM-2 | generic AOSP가 vendor/HAL 없이 부팅 실패 | EP3 막힘 | software/swiftshader HAL 포함 generic 타깃, 최소 vendor 골격 |
| RM-3 | ROM 대용량(>1GB) UX/저장 | 설치 실패 | tar.zst 압축, 스트리밍 추출, 저장공간 preflight |
| RM-4 | 라이선스/provenance 미흡 | 배포 불가 | NOTICE/라이선스 수집(EP6/P6), 번들 금지·user-provided 원칙 |
| RM-5 | ART dexopt/부팅 불안정 | 앱 실행 불가 | quicken/인터프리터 옵션(EP4.2), tombstone triage(P2) |
| RM-6 | tar.zst 미구현 | 파이프라인 차단 | EP8.3 선결로 순서 고정 |

## 10. 검증 게이트

```text
GUEST_ROM_READY passed=? format=tar.zst signed=ed25519 health=extended(linker64+libc) \
    license_docs=true bundled_in_repo=0 boots_first_guest_marker=?
```
- `bundled_in_repo=0`: ROM이 저장소에 없음(번들 금지 강제).
- `boots_first_guest_marker`: §7 통합 후 게스트 출처 부팅 마커 1개 도달 → EP2 G1의 `spike_oncreate_reached` 연결.

## 11. 실행 순서 / 결정 포인트 요약

```
[결정] 7.1.2 vs AOSP10(API29) MVP 게스트  ← RM-1 빌드 난도 평가 후
   │
   ▼
EP8.3 tar.zst → health 확장 → build_aosp_guest_rom.sh(Docker) → Ed25519 import
   │
   ▼
VmInstanceService 실제 부팅 통합(§7) → 게스트 출처 마커 → EP3(zygote/system_server)
```

> 본 문서는 전략·계획이며 코드 변경을 포함하지 않는다. 착수 시 §8 순서대로 진행한다.

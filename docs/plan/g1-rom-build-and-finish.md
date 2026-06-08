# G1 마무리 가이드 — 클린룸 AOSP ROM 빌드 → 실부팅 → G1 closure

> 작성일: 2026-06-08
> 상위: [`guest-rom-acquisition-strategy.md`](./guest-rom-acquisition-strategy.md) · [`vm-boot-integration-design.md`](./vm-boot-integration-design.md) · [`production-execution-phases.md`](./production-execution-phases.md) EP2.9
> 대상 독자: **개발자 / CI** (이 절차는 다개월 인프라가 아니라, AOSP 빌드 머신 1대에서 수 시간 내 1회 수행).
> 목적: `G1_RESULT passed=true`까지 남은 단 2가지 — ① 클린룸 AOSP arm64 ROM ② `REAL_GUEST_BOOT_ENABLED=true` — 를 실제로 닫는 재현 가능한 절차.

## 0. 전제 / 왜 여기(레포)에서 자동화하지 않는가

- AOSP 7.1.2 빌드는 **Ubuntu 16.04 + OpenJDK 8 + Python 2 + repo**, **디스크 ~250GB / RAM ≥ 16GB / 빌드 수 시간**을 요구한다. 클린룸 호스트 개발 환경(macOS/CI)과 분리된 1회성 빌드라 레포 빌드에 포함하지 않는다.
- **클린룸 원칙**: ROM은 **AOSP 소스(Apache-2.0)에서 직접 빌드**한다. Google 제공 emulator/device 시스템 이미지(GMS/proprietary HAL 포함)는 **사용 금지**(라이선스). 산출 ROM은 **dev/CI 아티팩트 — 저장소 커밋 금지**(`out/`는 gitignore, release는 게스트 asset 0).

## 1. Docker 빌드 환경

```dockerfile
# aosp-7.1.2.Dockerfile
FROM ubuntu:16.04
RUN apt-get update && apt-get install -y \
    openjdk-8-jdk git-core gnupg flex bison gperf build-essential zip curl \
    zlib1g-dev libc6-dev-i386 lib32ncurses5-dev x11proto-core-dev libx11-dev \
    lib32z1-dev libgl1-mesa-dev libxml2-utils xsltproc unzip python \
    simg2img e2fsprogs zstd
RUN curl -o /usr/local/bin/repo https://storage.googleapis.com/git-repo-downloads/repo \
    && chmod a+x /usr/local/bin/repo
ENV LANG=C.UTF-8
WORKDIR /aosp
```

```sh
docker build -f aosp-7.1.2.Dockerfile -t aosp-7.1.2 .
# 큰 빌드 디렉터리는 호스트 볼륨으로
docker run -it --rm -v "$HOME/aosp712:/aosp" -v "$PWD:/avm" aosp-7.1.2 bash
```

## 2. AOSP 7.1.2 소스 받기 + 빌드 (컨테이너 안)

```sh
git config --global user.email you@example.com && git config --global user.name you
repo init -u https://android.googlesource.com/platform/manifest -b android-7.1.2_r36
repo sync -c -j"$(nproc)"            # ~수십 분~시간, 디스크 대용량

source build/envsetup.sh
lunch aosp_arm64-userdebug           # generic arm64 (vendor blob 불요)
make -j"$(nproc)"                    # 수 시간
# 산출: out/target/product/generic_arm64/system.img  (sparse ext4)
```

> 메모: `aosp_arm64`는 소프트웨어 HAL 기반 generic 타깃이라 벤더 blob 없이 부팅 가능한 /system을 만든다. (full 부팅 안정화는 EP3 영역 — §7 참고.)

## 3. system.img → 게스트 ROM 패키징

레포의 [`tools/build_aosp_guest_rom.sh`](../../tools/build_aosp_guest_rom.sh)를 컨테이너(또는 simg2img/debugfs/zstd 있는 Linux)에서 실행:

```sh
/avm/tools/build_aosp_guest_rom.sh \
    --system-img out/target/product/generic_arm64/system.img \
    --out /avm/out/guest-rom \
    --guest-version 7.1.2 --arch arm64 --min-host-sdk 26
```

스크립트가 수행: system.img 추출(simg2img+debugfs) → rootfs 조립(+vendor/data/cache) → **bootReady 전제(`linker64`/`libc.so`/`app_process64`가 arm64 ELF) 검증** → symlink·권한 보존 `tar.zst` + `manifest.json`(+`.sha256`) 생성.

산출:
```
out/guest-rom/androidfs_7.1.2_arm64.tar.zst
out/guest-rom/androidfs_7.1.2_arm64.manifest.json
out/guest-rom/androidfs_7.1.2_arm64.sha256
```

## 4. (선택) Ed25519 서명 — 오프라인 import 채널용

제품 import 경로(`RomSignaturePolicy.ed25519Import`)는 서명 매니페스트를 요구한다. dev 번들 경로는 미서명 허용(`RomSignaturePolicy.bundledDev`)이라 G1 검증엔 서명 불요. 서명 시:

```sh
# manifest.json의 canonicalSigningBody(서명 제외 본문)에 대해 Ed25519 서명
#  → manifest에 "signature"(hex) / "publicKeyId" / "patchLevel" 추가
#  → 호스트에 신뢰 공개키 임베드(EP8.4 외부 import UI에서 ed25519Import에 주입)
```
⚠️ Android **API 33+** 만 JCA `Ed25519` 지원 → API 26–32 import는 번들 Ed25519 구현 후속 필요(EP8.2 캐비엇).

## 5. 디바이스에 설치 (dev)

ROM은 대용량(~수백 MB~1GB)이라 debug 에셋 번들은 비권장. dev 설치 옵션:

- **(권장) 외부 import (EP8.4 완료 후)**: SAF로 `tar.zst`+서명 manifest import → `RomInstaller.install`이 `RomSignaturePolicy` 게이트 통과 후 commit.
- **(임시 dev) 인스턴스 rootfs 직접 배치**: 추출된 rootfs를 기기의 `<filesDir>/avm/instances/vm1/rootfs/`에 push하고 `image_manifest.json` 기록. 설치 후 health 확인:

```sh
# 설치 후 bootReady 확인 (RootfsHealthCheck.bootReady = linker64/libc/app_process64가 arm64 ELF)
adb shell run-as dev.jongwoo.androidvm ls -l files/avm/instances/vm1/rootfs/system/bin/linker64
# 또는 진단 receiver로 health/bootReady 로깅
```

`bootReady=true`가 되어야 다음 단계가 의미를 가진다(아니면 게이트가 SIMULATED 유지).

## 6. G1 닫기 — flag on → 재빌드 → 부팅

1. `app/src/main/java/dev/jongwoo/androidvm/vm/GuestBootPolicy.kt`:
   ```kotlin
   const val REAL_GUEST_BOOT_ENABLED = true   // false -> true
   ```
2. 재빌드·설치:
   ```sh
   JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon :app:assembleDebug
   adb install -r -g app/build/outputs/apk/debug/app-debug.apk
   ```
3. VM 시작(UI Start 또는 매니저). 경로: `startRuntime` → `GuestBootPolicy.select(bootReady=true, flag=true)=REAL` → `setBootMode(realBoot=true)` → `startGuest` → `realGuestBootEntrypoint` → `bootGuestViaLinker(rootfs, .../app_process64, .../linker64, GatewayMode::VFS)`.

## 7. 검증 기준 (G1)

- **부팅 모드 로그**: `AVM.VmInstance: guest boot mode=REAL bootReady=true`.
- **부트스트랩 status(게스트 출처)**: `getBootstrapStatus` → `runtime_mode=real;linker_ran=true;...` (`runtime_mode=simulated` **아님**) → `GuestBootStatus.isRealGuestBoot()=true`, `synthetic=0`.
- **G1_RESULT**(게스트 출처 신호로만 판정):
  ```text
  G1_RESULT passed=true linker_real=true reloc_applied=true syscalls_real=true heap_real=true tls_safe=true vfs_mapped=true thread_create=true synthetic=0
  ```
  - 실제 `linker64`가 `libc.so` 등을 매핑·relocation → app_process 진입(linker_real/reloc_applied)
  - VFS openat 서비싱으로 rootfs 라이브러리 로드(vfs_mapped)
  - seccomp SIGSYS 게이트웨이로 syscall 서비싱(syscalls_real), 프로세스 분리 TLS(tls_safe), clone(thread_create)

## 8. 정직한 경계

- **G1은 "실행 코어"**: 실제 linker가 실제 libc/app_process를 링킹·실행하면 닫힌다. **전체 Android 부팅**(servicemanager→zygote→system_server→SurfaceFlinger)은 **EP3(G2)** 영역이다. app_process가 full 환경 부재로 조기 종료하더라도 linker→libc→app_process 진입(게스트 출처)이면 **G1 코어는 충족**이며, 그 위에서 EP3가 서비싱 폭(binder ioctl/property/socket)을 확장한다.
- **클린룸/라이선스**: ROM은 AOSP 소스 빌드만. NOTICE/license 수집(EP8.7). 저장소 커밋·release 번들 금지.
- **canned 금지**: 모든 `*_real`/`synthetic=0`은 게스트 출처 신호로만 판정. flag를 켰는데 bootReady가 아니면 자동으로 SIMULATED(게이트가 위조 방지).

## 9. 트러블슈팅

| 증상 | 점검 |
| --- | --- |
| boot mode가 계속 SIMULATED | `bootReady=true`인가(`RootfsHealthCheck`), flag on인가, ROM의 linker64/libc/app_process64가 arm64 ELF인가 |
| 자식이 즉시 SIGSEGV/SIGILL | 스택/auxv(EP2.2) — initial_stack의 AT_RANDOM/AT_BASE/AT_ENTRY 정합, ABI(arm64) |
| `CANNOT LINK EXECUTABLE library "X" not found` | VFS openat 서비싱이 rootfs로 경로 재작성하는지(EP2.6), 의존 라이브러리가 rootfs에 존재하는지 |
| seccomp 트랩 후 행/루프 | 게이트웨이 IP-allow 범위(`avmRawSyscall`), `SECCOMP_RET_TRAP` 후 반환 처리 |
| W^X로 코드 실행 차단 | `GuestExecProbe`로 기기 능력 재확인(`prot_exec_mmap`/`memfd_exec`) |

## 10. 요약 체크리스트

- [ ] Docker AOSP 7.1.2 빌드 → `system.img`
- [ ] `build_aosp_guest_rom.sh --system-img` → `tar.zst`+manifest (bootReady 검증 통과)
- [ ] (선택) Ed25519 서명
- [ ] 디바이스 설치 → `bootReady=true` 확인
- [ ] `REAL_GUEST_BOOT_ENABLED=true` → 재빌드·설치
- [ ] VM Start → `boot mode=REAL`, `runtime_mode=real`, `synthetic=0` 확인
- [ ] `G1_RESULT passed=true` (게스트 출처) → EP3 착수

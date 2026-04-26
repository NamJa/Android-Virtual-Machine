# Stage 03 - ROM and Image Pipeline

## 목적

guest Android rootfs를 만들고, host APK asset으로 패키징하고, 앱 내부 저장소에 안전하게 추출하는 파이프라인을 만든다.

## 설계 원칙

- MVP에서는 custom VFS image format을 만들지 않는다.
- 처음에는 plain directory 기반 rootfs로 시작한다.
- archive format은 `tar.zst` 또는 `zip` 중 하나를 사용한다.
- rootfs 추출 후 checksum과 version marker를 남긴다.
- image update, snapshot, shared partition은 후속 단계로 미룬다.

## 권장 Rootfs 구조

```text
rootfs/
├─ system/
├─ vendor/
├─ data/
├─ cache/
├─ root/
├─ dev/
├─ proc/
├─ sys/
└─ metadata/
```

MVP에서 `dev`, `proc`, `sys`는 실제 host filesystem을 mount하지 않는다. native runtime의 VFS layer가 virtual node로 제공한다.

## Asset 구조

```text
app/src/main/assets/guest/
├─ androidfs_7.1.2_arm64.tar.zst
├─ androidfs_7.1.2_arm64.sha256
└─ androidfs_7.1.2_arm64.manifest.json
```

`manifest.json` 예시:

```json
{
  "name": "androidfs_7.1.2_arm64",
  "guestVersion": "7.1.2",
  "guestArch": "arm64",
  "format": "tar.zst",
  "compressedSize": 0,
  "uncompressedSize": 0,
  "sha256": "",
  "createdAt": "",
  "minHostSdk": 26
}
```

## Instance Rootfs 배치

```text
files/instances/vm1/
├─ config/
│  ├─ vm_config.json
│  └─ image_manifest.json
├─ rootfs/
│  ├─ system/
│  ├─ vendor/
│  ├─ data/
│  ├─ cache/
│  └─ root/
├─ staging/
├─ logs/
└─ export/
```

## `vm_config.json`

```json
{
  "instanceId": 1,
  "displayName": "VM 1",
  "guestVersion": "7.1.2",
  "guestArch": "arm64",
  "rootfsPath": "/absolute/path/files/instances/vm1/rootfs",
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

## Host 구현 클래스

```text
storage/
├─ AssetVerifier.kt
├─ RomArchiveReader.kt
├─ RomInstaller.kt
├─ InstanceStore.kt
├─ PathLayout.kt
└─ RootfsHealthCheck.kt
```

### `AssetVerifier`

책임:

- asset 존재 여부 확인
- sha256 계산
- manifest parsing
- guest version/arch 확인

### `RomInstaller`

책임:

- rootfs 설치 여부 확인
- 임시 directory에 추출
- 추출 완료 후 atomic rename
- 실패 시 partial directory 삭제
- progress callback 제공

### `RootfsHealthCheck`

책임:

- 필수 파일 존재 확인
- marker file 확인
- image version 확인
- writable directory 확인

필수 파일 예시:

```text
system/build.prop
system/bin/app_process64
system/bin/servicemanager
system/bin/sh
system/framework/
vendor/
data/
cache/
```

## 추출 Flow

```text
createInstance()
  -> load asset manifest
  -> verify sha256
  -> create temp dir
  -> extract archive
  -> run health check
  -> write image_manifest.json
  -> write vm_config.json
  -> rename temp dir to rootfs
```

## 손상 복구 Flow

```text
startInstance()
  -> rootfs health check
  -> if invalid, mark state MissingImage
  -> ask user to repair
  -> delete rootfs
  -> reinstall from asset
```

## Guest Image 제작 작업

### 1. AOSP rootfs 준비

- Android 7.1.2 arm64 target 준비
- system/vendor/data/cache 분리
- 불필요한 prebuilt app 제거
- boot animation 제거 또는 경량화
- default launcher 포함

### 2. Guest profile 작성

`build.prop`에 기본 device profile을 넣는다.

```properties
ro.product.brand=CleanRoom
ro.product.manufacturer=CleanRoom
ro.product.model=VirtualPhone
ro.product.device=virtual_phone
ro.build.version.release=7.1.2
ro.zygote=zygote64
```

### 3. Runtime dependency 목록화

- linker/linker64
- libc/libm/libdl
- app_process64
- zygote 관련 jar/art/oat
- framework jar
- servicemanager
- surfaceflinger 관련 dependency

### 4. Archive 생성

```bash
tar --numeric-owner -cf androidfs_7.1.2_arm64.tar rootfs
zstd -19 androidfs_7.1.2_arm64.tar
sha256sum androidfs_7.1.2_arm64.tar.zst > androidfs_7.1.2_arm64.sha256
```

## Native Runtime과의 계약

Rootfs pipeline은 native runtime에 다음 값을 제공한다.

- absolute rootfs path
- guest version
- guest arch
- writable data path
- log path
- staging path
- display config
- bridge config

## 테스트

- asset manifest parse test
- checksum mismatch test
- partial extract cleanup test
- rootfs health check success/fail test
- app reinstall 후 기존 rootfs 유지 test
- rootfs 삭제 후 repair test

## 완료 기준

- 앱 내부에서 rootfs를 설치할 수 있다.
- 손상된 rootfs를 감지하고 복구할 수 있다.
- VM 시작 전 native runtime에 정확한 rootfs/config path를 전달할 수 있다.
- archive 교체 시 image version mismatch를 감지할 수 있다.


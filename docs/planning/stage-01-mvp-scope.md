# Stage 01 - Clean-room MVP Scope

## 목적

VPhoneOS와 같은 Android-on-Android 앱을 한 번에 복제하려 하지 않고, 개인용으로 검증 가능한 최소 VM 제품을 정의한다. 이 단계의 결과물은 코드보다 더 중요하다. 범위를 잘못 잡으면 이후 native runtime, graphics, binder 구현이 끝없이 커진다.

## 최종 결정

MVP는 다음 조건으로 제한한다.

- VM 인스턴스는 1개만 지원한다.
- host 기기는 Android arm64만 지원한다.
- guest OS는 Android 7.1.2 arm64를 우선한다.
- guest 32-bit 앱 지원은 제외한다.
- GMS, Magisk, Vulkan, multi-instance, snapshot, cloud update는 제외한다.
- 광고, analytics, 계정, 외부 서버 통신은 넣지 않는다.
- host 앱 설치 직후 위험 권한을 요청하지 않는다.

## 왜 Android 7.1.2부터 시작하는가

Android 7.1.2는 Android 10/12보다 binder, seccomp, linker namespace, filesystem policy 의존성이 낮다. user-space runtime을 처음 만들 때 Android 12부터 시작하면 framework boot 이전에 막히는 지점이 너무 많다.

Android 12는 장기 목표로 남긴다.

## MVP 기능 목록

### 반드시 포함

- host 앱 UI
- VM 생성/삭제
- guest rootfs asset 추출
- VM 시작/중지
- boot log 표시
- native dummy renderer
- Surface attach/detach/resize
- 최소 VFS
- 최소 property service
- 최소 guest process 실행 PoC
- APK 파일 import staging

### 가능하면 포함

- guest launcher 표시
- APK 설치
- APK 실행
- audio output
- basic network

### 제외

- host 앱 목록 수집
- host 전화번호/IMEI/통신사 정보 수집
- host Wi-Fi 설정 변경
- host global setting 변경
- overlay permission 강제 요구
- background auto-start abuse
- 계정 로그인
- remote config
- ad SDK
- telemetry upload

## 성공 기준

MVP 성공은 다음 기준으로 판단한다.

- 앱 최초 실행 시 dangerous permission 요청이 없다.
- 앱 내부 저장소에 guest rootfs가 생성된다.
- 사용자가 버튼을 눌러 VM을 시작할 수 있다.
- foreground service notification이 표시된다.
- `:vm1` 프로세스에서 native runtime thread가 시작된다.
- `VmNativeActivity`의 Surface에 dummy frame 또는 guest frame이 표시된다.
- VM을 중지하면 native thread와 foreground service가 정리된다.
- crash 발생 시 host 앱에서 로그 파일을 확인할 수 있다.

## 프로젝트 구조

```text
vphoneos/
├─ app/
│  ├─ src/main/java/.../ui/
│  ├─ src/main/java/.../vm/
│  ├─ src/main/java/.../bridge/
│  ├─ src/main/java/.../storage/
│  └─ src/main/cpp/
├─ guest-images/
│  ├─ android-7.1.2-arm64/
│  └─ tools/
├─ docs/
└─ tests/
```

## 주요 모듈 책임

### UI

- VM 상태 표시
- VM 생성/삭제/시작/중지 버튼
- rootfs 설치 진행률 표시
- log viewer
- 권한 bridge 설정 화면

### VM 관리

- instance config 관리
- foreground service 관리
- native runtime 상태 관리
- crash/restart 정책 관리

### Storage

- asset checksum 검증
- rootfs 추출
- instance directory 생성
- log/snapshot/export directory 관리

### Native Runtime

- host app sandbox 내부에서 guest userspace 실행
- VFS, property, binder, process abstraction 제공
- Surface/input/audio/network bridge와 연결

## Milestone

### M1: Host skeleton

- Android project 생성
- Compose UI 기본 화면
- `VmManagerService`
- `VmInstanceService`
- `VmNativeActivity`
- dummy JNI bridge

완료 기준:

- VM 시작 버튼이 `:vm1` service를 띄운다.
- VM 화면 activity가 열린다.
- notification이 표시된다.

### M2: Storage skeleton

- `InstanceStore`
- `RomInstaller`
- asset checksum
- rootfs directory 생성

완료 기준:

- assets의 archive를 app files directory로 추출한다.
- 추출 상태를 UI에서 볼 수 있다.

### M3: Native dummy runtime

- JNI 로딩
- native instance context
- dummy render loop
- log file writer

완료 기준:

- Surface에 색상 frame이 표시된다.
- VM stop 시 native loop가 종료된다.

### M4: Guest runtime PoC

- guest path resolver
- property service
- guest binary 실행 실험

완료 기준:

- guest rootfs 내부의 테스트 binary 또는 shell-like binary 실행 결과가 log에 남는다.

## 리스크

- Android guest boot는 일반 앱 sandbox에 맞지 않는다.
- binder, ashmem, futex, signal semantics 구현량이 매우 크다.
- Android 12는 MVP 대상이 아니다.
- GPU acceleration은 별도 장기 프로젝트다.

## 산출물

- MVP scope 문서
- architecture decision record
- permission policy 문서
- stage별 구현 ticket 목록
- 초기 Android project skeleton


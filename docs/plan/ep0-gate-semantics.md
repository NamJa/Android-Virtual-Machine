# EP0.7 — 게이트 의미 정의: debug 진단 게이트 vs release product 게이트

> 작성일: 2026-06-04
> 상위: [`production-execution-phases.md`](./production-execution-phases.md) EP0.7
> 목적: "진단 게이트 통과"와 "제품 게이트 통과"가 전혀 다른 보증임을 코드 수준에서 고정한다. 이 문서는 EP0의 진실성(truth) 산출물이다.

## 1. 두 게이트의 차이

| 항목 | Debug 진단 게이트 (Stage/Phase) | Release Product 게이트 (PRODUCT_*) |
| --- | --- | --- |
| 트리거 | `app/src/debug/`의 `Stage*`/`StagePhase*DiagnosticsReceiver` | `app/src/product/`의 `ProductGateReceiver` (release-equivalent `product` 변형) |
| 빌드 표면 | debug 빌드에만 존재 | product 변형에만 존재. 실제 `release`에는 **둘 다 없음** |
| 판정 출처 | 호스트가 주입한 프로브 / canned 상태 허용 | **게스트 출처 신호만** 인정, canned 금지 (`synthetic*=0`) |
| 측정값 | 결정론적/시뮬레이션 다수 | 실측 신호([`ProductGateSignalSource`]). 미구현 항목은 fail-closed(`false`) |
| 통과 의미 | "구조가 설계대로 배선됨" | "사용자가 실제로 쓸 수 있음" |

핵심: **debug 진단 게이트가 모두 `passed=true`여도 product 게이트는 대부분 `false`다.** 이는 버그가 아니라 의도된 정직성이다. product 게이트의 각 필드는 해당 EP가 실제 동작을 구현할 때만 `true`로 뒤집힌다.

코드 보증:
- `ProductReleaseSurfaceGuardTest` — main/product 매니페스트와 소스셋에 debug receiver / `FixedCameraSource` / `FixedPcmSource`가 유입되지 않음을 정적으로 강제.
- `ProductGateRunner` + `ConservativeProductGateSignalSource` — 기본 fail-closed. 단위 테스트(`ProductGateRunnerTest`)가 "모든 신호 false → 모든 라인 passed=false"를 고정.
- `AndroidProductGateSignalSource` — 현재 진실하게 측정 가능한 필드만 `true`(예: `cameraPolicyDefaultOff`, product 변형의 `debugSurfaceClosed=BuildConfig.PRODUCT_GATE`), 나머지는 위임으로 `false`.

## 2. "실제 제품으로 인정되는" on-device 시나리오 목록 (고정)

product 변형 APK를 실기기/release-equivalent 에뮬레이터에 설치한 뒤, 아래 시나리오가 **게스트 출처 신호로** 성립해야 해당 PRODUCT 게이트 필드를 `true`로 인정한다. 호스트 로그/플래그 조작은 인정하지 않는다.

| # | 시나리오 | 인정 신호 | 대응 게이트 필드 | 책임 EP |
| --- | --- | --- | --- | --- |
| S1 | 사용자가 외부 ROM을 import → 무결성 검증 통과 | 서명/health 검증 결과가 실제 파일에서 도출 | bridge/file, security/update | EP8 |
| S2 | VM이 게스트 `init→zygote→system_server→SurfaceFlinger`로 부팅 | 게스트 프로세스가 출력한 부팅 마커 | runtime/boot | EP3 |
| S3 | 사용자가 일반 APK를 설치 → `pm list packages`에 등장 | 게스트 PMS 응답 | runtime/install | EP4 |
| S4 | launcher에서 앱 실행 → `Activity.onCreate` 진입·렌더 | 게스트 앱 프로세스 생성 + 첫 프레임 | runtime/launch, runtime/graphics | EP4/EP5 |
| S5 | 터치/뒤로가기가 게스트 앱에 반영 | 게스트 InputFlinger 수신 | runtime/input | EP4/EP5 |
| S6 | 게스트 오디오가 호스트로 출력 | 실제 AAudio sink 재생, xrun 카운터 | runtime/audio | EP5 |
| S7 | clipboard/file/network 브리지가 정책대로 동작, off 경로는 host API 미호출 | 브리지 audit 기록 + off-path 호출 0 | bridge/* | EP6 |
| S8 | 스냅샷 생성→롤백, 백업 export/import | 실제 overlay/zip 산출물 | resilience/* | EP7 |
| S9 | crash/boot 실패 시 복구 메시지 + 아티팩트 번들 | `FailureBundle` ZIP(redaction 적용) | resilience/crash_report, boot_repair | EP0.5/EP7 |
| S10 | release 빌드에 debug receiver/fixed source 0, telemetry 0 | 정적 가드 + 매니페스트 | release/debug_surface, security/telemetry | EP10 |

## 3. docs/planning 제거 — 항목 드롭(확정)

EP0.7의 나머지 한 항목이었던 "Phase A–E 문서(`docs/planning/phase-*.md`)의 '잔여 Step' 표 분리"는 **드롭한다**: `docs/planning/`(레거시 Stage/Phase·product-readiness 계획)은 작업 트리에서 **영구 제거**되며 복원하지 않기로 확정됐다. 그 내용은 `docs/plan/` 세트로 흡수됐고, **이제 `docs/plan/`이 단일 권위 계획**이다. 분리 대상 문서가 존재하지 않으므로 본 항목은 불요(워크플로/코드 무영향).

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Common commands

Pick up the Android SDK by writing `local.properties` with `sdk.dir=...` if Gradle does not auto-detect it. JDK 17 is required.

```sh
# Canonical readiness check (matches docs/planning/pre_stage6_readiness.md)
JAVA_HOME=$(/usr/libexec/java_home -v 17) ./gradlew --no-daemon \
    :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleRelease

# Targeted variants
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest --tests dev.jongwoo.androidvm.storage.RomArchiveReaderTest

# Regenerate the debug-only clean-room guest fixture (required for debug builds
# whose ROM pipeline tests/diagnostics need an asset)
tools/create_debug_guest_fixture.sh
```

Stage diagnostics are exercised by broadcasting to debug-only receivers on a connected device/emulator (see `app/src/debug/AndroidManifest.xml`):

```sh
adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_STAGE4_DIAGNOSTICS \
    -n dev.jongwoo.androidvm/.debug.Stage4DiagnosticsReceiver
adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_STAGE5_DIAGNOSTICS \
    -n dev.jongwoo.androidvm/.debug.Stage5DiagnosticsReceiver
adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_STAGE6_DIAGNOSTICS \
    -n dev.jongwoo.androidvm/.debug.Stage6DiagnosticsReceiver
adb shell am broadcast -a dev.jongwoo.androidvm.debug.RUN_STAGE7_DIAGNOSTICS \
    -n dev.jongwoo.androidvm/.debug.Stage7DiagnosticsReceiver
# Inspect with: adb logcat -s AVM.Stage4Diag AVM.Stage5Diag AVM.Stage6Diag AVM.Stage7Diag AVM.Native
```

Each diagnostic receiver logs a single `STAGE{n}_RESULT passed=...` line plus per-subcheck lines; readiness docs treat these as the canonical pass criteria.

## Architecture

This is a clean-room **Android-on-Android user-space VM**. The host APK ships a Kotlin/Compose UI plus a single C++ JNI library (`avm_host`) and runs the guest runtime inside the host's process sandbox — no kernel features, no proprietary VPhoneOS binaries. Implementation is staged; each stage has a planning doc under `docs/planning/stage-XX-*.md` and the long-form rationale lives in `VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md`. Read the relevant stage doc before changing code in that area — the "검증 기준 / 완료 기준" sections double as the acceptance contract.

### Two-process model

The Android manifest splits the app across two OS processes on purpose:

- Default process: `MainActivity` (Compose control surface) and `VmManagerService` (lifecycle bookkeeping).
- `:vm1` process: `VmNativeActivity` (fullscreen `Surface`) and `VmInstanceService` (foreground `dataSync` service that owns the native runtime). The native library is loaded here, so all `VmNativeBridge` calls from `:vm1` hit the same in-memory `Instance` table; calls from the default process see a *different* native state and should be limited to read-only smoke checks.

`VmInstanceService.startRuntime()` is the canonical boot path: `RuntimePreflightCheck` → `VmNativeBridge.initHost` → `initInstance(configJson)` → `startGuest`. Preflight reads `InstanceStore` and `RomInstaller.snapshot()`; if the rootfs is missing or unhealthy, the service refuses to start and surfaces an error state instead of partially booting.

### Native runtime (`app/src/main/cpp/vm_native_bridge.cpp`)

A single ~2k-line translation unit hosts every JNI entry point declared in `VmNativeBridge.kt`. It maintains a process-wide `std::map<instanceId, Instance>` guarded by per-instance mutexes; each `Instance` tracks rootfs paths, an FD/file table, a virtual property store, a binder-handle stub, an input queue, an audio output state, a guest framebuffer, dirty-rect bookkeeping, gralloc/hwcomposer device stubs, and a render thread bound to an `ANativeWindow`. The Kotlin layer treats this as opaque and exchanges JSON strings for stats (`getGraphicsStats`, `getInputStats`, `getAudioStats`, `getBootstrapStatus`, `resolveGuestPath`) — when adding native state, prefer extending those JSON payloads over inventing new JNI signatures.

The runtime is deliberately *not* a kernel/QEMU emulator. Stage 04 builds a path-rewriting VFS, virtual `/dev/*` device nodes, a property service stub, and a binder/servicemanager stub. Stage 05 adds a software framebuffer (`graphicsAccelerationMode=software_framebuffer` is the MVP ceiling — GLES/Virgl/Venus are out of scope) plus input and AAudio output. Stage 06 onward (APK staging, package manager, etc.) is not implemented yet.

### Instance storage layout

`PathLayout` (host `filesDir/avm/`) and `InstancePaths` (`avm/instances/<id>/{config,rootfs,logs,runtime,shared,staging,export}`) are the single source of truth for on-disk paths. Anything that touches guest files should derive paths from `InstancePaths` rather than concatenating strings, and the same paths are serialized into `vm_config.json` so the native side never has to guess directory names. `VmConfig.DEFAULT_INSTANCE_ID = "vm1"` is currently the only supported instance — multi-instance support is explicitly deferred.

### ROM pipeline

`RomInstaller` discovers candidates from `assets/guest/*.manifest.json`, verifies them with `AssetVerifier` (sha256 + min-host-SDK + format allowlist `{zip, tar.zst}`), extracts via `RomArchiveReader` into a per-install staging directory, runs `RootfsHealthCheck`, then atomically commits to `<instance>/rootfs/`. `RomPipelineSnapshot` is the read-only view used by UI and preflight; `repair()` is the only path that deletes an installed rootfs. Debug builds rely on the fixture from `tools/create_debug_guest_fixture.sh`; release builds ship no guest assets by default.

### Bridge policy

Stage 07 introduced the privacy boundary as a per-instance, per-bridge policy stored at
`<instance>/bridge-policy.json` and read through `BridgePolicyStore`. Each bridge has a
`BridgePolicy(bridge, mode, enabled, options)` record; defaults live in
`DefaultBridgePolicies.all`. Privacy-sensitive bridges (`CLIPBOARD`, `LOCATION`, `CAMERA`,
`MICROPHONE`) default off; `CAMERA` and `MICROPHONE` are explicitly `UNSUPPORTED` for the
Stage 07 MVP and never request host runtime permissions. Output-only bridges
(`AUDIO_OUTPUT`, `VIBRATION`, `NETWORK`, `DEVICE_PROFILE`) default on but can be toggled
per instance.

Every bridge request flows through `BridgeDispatcher` -> `DefaultPermissionBroker` ->
`BridgeAuditLog`. The broker is the only place that may call into a host runtime permission
prompt, and it does so only via `PermissionRequestGateway`. Off / unsupported bridges never
reach the gateway. Audit entries (`<instance>/bridge-audit.jsonl`) record bridge / operation
/ result / reason but never the raw payload (clipboard text, location coordinates, etc.).
`Stage7BridgeScope.forbiddenManifestPermissions` and the `ManifestPermissionGuardTest` lock
the manifest denylist; `Stage7Diagnostics` runs the full gate from a unit test or the
`Stage7DiagnosticsReceiver` (`STAGE7_RESULT passed=true ...`).

## Conventions worth knowing

- Do not introduce new Android permissions casually. The base manifest is intentionally minimal (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` only). Stage 07 enumerates which permissions are allowed and under what feature gate.
- `compileSdk = 36`, `targetSdk = 36`, `minSdk = 26`. NDK ABIs: `arm64-v8a`, `armeabi-v7a`, `x86_64`. C++20, `-Wall -Wextra`.
- Unit tests are pure JVM JUnit 4 (no Robolectric, no instrumentation). The native runtime is exercised via on-device diagnostic broadcasts, not via `androidTest`.
- Planning docs are written in Korean; commit messages and code identifiers are English.

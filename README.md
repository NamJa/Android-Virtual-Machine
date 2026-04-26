# Android Virtual Machine

Clean-room Android-on-Android virtual machine project based on the planning docs in
`docs/planning`. The first implementation slice creates a minimal host APK,
per-instance storage layout, foreground VM service, isolated display activity,
and native `Surface` renderer stub.

## Current Scope

- Host app: Kotlin + Compose control surface.
- Runtime process: `:vm1` foreground service and display activity.
- Native bridge: C++ JNI library named `avm_host`.
- Guest target: Android 7.1.2 arm64 MVP configuration.
- Bridge policy: privacy-sensitive bridges disabled by default.

## Build

```sh
./gradlew :app:assembleDebug
```

If the Android SDK is not auto-detected, create an untracked `local.properties`
file with:

```properties
sdk.dir=/Users/jongwoo/Library/Android/sdk
```

## Planning Docs

- `docs/planning/VPHONEOS_CLEANROOM_IMPLEMENTATION_PLAN.md`
- `docs/planning/stage-01-mvp-scope.md`
- `docs/planning/stage-02-host-apk.md`
- `docs/planning/stage-03-rom-image-pipeline.md`
- `docs/planning/stage-04-native-runtime.md`
- `docs/planning/stage-05-graphics-input.md`
- `docs/planning/stage-06-apk-install-launch.md`
- `docs/planning/stage-07-permission-bridges.md`

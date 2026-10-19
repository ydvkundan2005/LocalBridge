# Phase 1 Test Evidence — Android Clipboard Feasibility

> Status: NOT TESTED. No physical device tests have been run.
> This template must be filled with actual device test results before Phase 1 is marked complete.

## Device Environment (REQUIRED — fill from device)

```
Manufacturer  : [record from device: adb shell getprop ro.product.manufacturer]
Model         : [record from device: adb shell getprop ro.product.model]
Android API   : [record from device: adb shell getprop ro.build.version.sdk]
Android OS    : [record from device: adb shell getprop ro.build.version.release]
Nothing OS    : [record from device: adb shell getprop ro.nothing.os.version or equivalent]
Security patch: [record from device: adb shell getprop ro.build.version.security_patch]
Build ID      : [record from device: adb shell getprop ro.build.display.id]
CPU ABI       : [record from device: adb shell getprop ro.product.cpu.abi]
Shizuku ver   : [installed version, if present]
```

## Build Environment

```
JDK           : OpenJDK 21.0.11 JBR-21.0.11+10-1163.116-nomod (ARM)
Mac OS        : 27.0.1 (Build 26A434)
Xcode         : N/A (no Xcode in CLI tools)
Swift         : 6.4 (swiftlang-6.4.0.34.1)
AGP           : 9.4.0 (pinned)
Kotlin        : 2.4.21 (pinned)
Gradle        : 9.8.1 (pinned)
compileSdk    : 36
minSdk        : 29
```

## Build Evidence

- [ ] `./gradlew assembleDebug` — PASS / FAIL / NOT RUN
- [ ] APK installed on device — YES / NO
- [ ] Build output recorded: _attach or paste here_

## Shizuku Investigation

- [ ] Shizuku installed: YES / NO
- [ ] Shizuku binder available: YES / NO
- [ ] Shizuku permission granted: YES / NO
- [ ] IClipboard service descriptor verified from Android source: YES / NO / IN PROGRESS
- [ ] Binder API signatures inspected: YES / NO
- [ ] Notes: _record findings here_

## Capture Test Results

| App | State | Adapter | Result | Notes |
|-----|-------|---------|--------|-------|
| Chrome | Foreground | Standard | NOT TESTED | |
| Chrome | Background | Standard | NOT TESTED | |
| Chrome | Screen locked | Standard | NOT TESTED | |
| Chrome | Foreground | Shizuku | NOT TESTED | pending Phase 1 binder impl |
| Chrome | Background | Shizuku | NOT TESTED | pending Phase 1 binder impl |
| Chrome | Screen locked | Shizuku | NOT TESTED | pending Phase 1 binder impl |
| Messaging app | Foreground | Standard | NOT TESTED | |
| Messaging app | Background | Standard | NOT TESTED | |
| Notes | Foreground | Standard | NOT TESTED | |
| Notes | Foreground | Shizuku | NOT TESTED | |
| Additional app | Foreground | Standard | NOT TESTED | |

## Edge Case Results

| Condition | Result | Notes |
|-----------|--------|-------|
| Shizuku not installed | NOT TESTED | Should report NEEDS_AUTHORIZATION |
| Shizuku stopped | NOT TESTED | Should report UNAVAILABLE without crash |
| Binder death | NOT TESTED | Should recover gracefully |
| Permission denied | NOT TESTED | Should report NEEDS_AUTHORIZATION |
| Phone rebooted (non-root Shizuku) | NOT TESTED | Should require manual Shizuku restart |

## Known Limitations / Blockers

1. Android background clipboard capture is restricted on API 29+ per official docs.
2. Shizuku IClipboard binder API not yet implemented (pending Phase 1 investigation).
3. No physical device test has been run in this environment.
4. JDK 21 is installed (spec requires Java 17 toolchain baseline); must verify
   AGP 9.4.0 + Kotlin 2.4.21 compatibility with JDK 21 before first build.

## Next Action

1. Connect Nothing Phone (3a) via USB/ADB.
2. Run `adb shell getprop` commands to record device environment above.
3. Run `./gradlew assembleDebug` and record output.
4. Install APK and run capture tests per table above.
5. Inspect Android IClipboard source for device's API level.
6. Update this file, ADR-001, and STATUS.md with real results.
7. Only then proceed to Phase 2.

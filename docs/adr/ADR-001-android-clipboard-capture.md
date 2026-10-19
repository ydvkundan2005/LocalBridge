# ADR-001 — Android Clipboard Capture Strategy

**Status:** PENDING EVIDENCE — Phase 1 investigation not yet complete.

## Context

LocalBridge requires background clipboard capture on Android.
Android 10+ (API 29+) restricts clipboard access to foreground apps or
the default IME.  The target device is Nothing Phone (3a); actual OS
build, API level, and behavior have not yet been verified on the physical device.

## Decision drivers

- 05_CLIPBOARD_SYNC.md §3: Shizuku adapter is the primary research path.
- 11_TESTING_AND_RULES.md §1 rule 4: Do not invent API signatures.
- 10_BUILD_PHASES.md Phase 1: Must record evidence from the actual device.

## Options considered

1. **Shizuku IClipboard binder** — Privileged clipboard listener via Shizuku.
   - Requires: Shizuku installed, ADB/Wireless Debugging authorization, correct
     binder/AIDL signatures for the target Android version.
   - Coverage: Potentially all apps including background/locked.
   - Risk: Hidden API changes per Android version; Shizuku must be manually
     restarted after non-root reboot.

2. **Standard ClipboardManager listener** — Public Android API.
   - Background read is denied on API 29+ from non-default-IME apps.
   - Coverage: Foreground only (documented in StandardClipboardCapture.kt).

3. **AccessibilityService fallback** — Per 05_CLIPBOARD_SYNC.md §3-C,
   only if dedicated test proves meaningful additional coverage without
   stealing focus, injecting UI, reading arbitrary screen text, or violating
   user expectations. Not included as default.

## Decision

**Phase 1 investigation must complete on the physical device before selecting.**

Required evidence before finalising this ADR:
- [ ] `adb shell getprop ro.build.version.release` output
- [ ] `adb shell getprop ro.build.version.sdk` output
- [ ] `adb shell getprop ro.nothing.os.version` or equivalent for Nothing OS build
- [ ] Security patch level
- [ ] Shizuku 12.2.0 installed and binder available
- [ ] IClipboard service descriptor and relevant method signatures verified from
      Android source for the target API level (not inferred)
- [ ] Test results: Chrome, messaging, Notes, one more app (foreground, background, locked)
- [ ] Reboot behavior with non-root Shizuku documented

## Consequences (to be filled after Phase 1 evidence)

_Leave blank until device tests have been run._

---
_This file must be updated with real device evidence before Phase 2 begins._

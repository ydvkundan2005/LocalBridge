# LocalBridge Device Validation Checklist

This checklist is a repeatable procedure for recording real-device behavior. It is a test plan, not evidence that a particular build has passed.

> **Current evidence status:** The repository's Phase 1 clipboard-feasibility matrix is marked **NOT TESTED**. Do not change a result to PASS until the exact build and device have been tested and the evidence has been recorded in [the Phase 1 evidence log](../test-evidence/phase-1.md).

## 1. Record the test environment

Record these values before testing:

| Field | Value to record |
|---|---|
| Test date and time | Local date, time, and time zone |
| LocalBridge build | Version, build identifier, and artifact filename |
| Android device | Manufacturer, model, Android version, API level, security patch |
| macOS device | Mac model, macOS version, CPU architecture |
| Network | Wi-Fi access point/router model when known; note whether devices use the same LAN |
| Artifact identity | SHA-256 of the exact APK or app package tested |
| Test operator | Person who performed the test |

Use the exact APK or macOS package intended for the test. Do not infer that an older release's result applies to a newer build.

## 2. Basic connection and discovery

For each run, record PASS, FAIL, or NOT RUN and include a short observation.

- [ ] Android and Mac are connected to the intended test network.
- [ ] The Mac receiver starts and advertises or displays its connection details as documented by the app.
- [ ] Android discovers or connects to the intended Mac.
- [ ] Disconnecting either side produces a clear state change.
- [ ] Reconnecting restores the session without requiring an unexplained app restart.

## 3. File-transfer checks

Use test files created specifically for validation. Do not use confidential, personal, or irreplaceable files.

| Direction | Suggested test case | Evidence |
|---|---|---|
| Android → Mac | Small file (for example, 1 MB) | Completion state, destination, file size |
| Android → Mac | Larger file (for example, 100 MB) | Completion state, elapsed time, file size |
| Mac → Android | Small file (for example, 1 MB) | Completion state, destination, file size |
| Mac → Android | Larger file (for example, 100 MB) | Completion state, elapsed time, file size |

For each transfer:

1. Record the source file size and SHA-256.
2. Start the transfer and note the displayed progress.
3. Wait for the receiving app to report completion.
4. Verify that the destination file exists and has the expected size.
5. Calculate the destination SHA-256 and compare it with the source.
6. Record whether cancellation and retry behave as expected.

A progress bar reaching 100% is not sufficient proof of a successful transfer. Record the final file and checksum result.

## 4. Text and clipboard workflows

Test each workflow separately. Do not place passwords, authentication codes, personal messages, or other sensitive content on the clipboard.

- [ ] Send a harmless text string from Android to Mac.
- [ ] Send a harmless text string from Mac to Android.
- [ ] Verify the received text exactly, including punctuation and Unicode characters.
- [ ] Verify the app's explicit/manual clipboard action while the app is foregrounded.
- [ ] Record background and screen-locked clipboard behavior separately.
- [ ] If a privileged helper such as Shizuku is involved, record its version, authorization state, and whether the test was run with or without it.

Android background clipboard access is restricted by the operating system. Do not describe background or locked-screen capture as supported unless it has been demonstrated on the recorded Android build and app version.

## 5. Lifecycle and interruption checks

Run these checks only after basic connection and transfer have passed.

- [ ] Switch away from the Android app and return.
- [ ] Lock and unlock the Android device.
- [ ] Turn Wi-Fi off and back on.
- [ ] Disconnect and reconnect the Mac receiver.
- [ ] Turn the app's master/service toggle off and confirm the documented stop behavior.
- [ ] Re-enable the service and test reconnection.
- [ ] Cancel an in-progress transfer and verify that no partial file is presented as a completed transfer.

Record app battery restrictions, background-service notifications, and any OS prompts that appear.

## 6. Failure and recovery checks

Where safe and practical, test expected failures:

- [ ] Permission denied or revoked.
- [ ] Device not on the same reachable network.
- [ ] Receiver unavailable.
- [ ] Connection interrupted during a transfer.
- [ ] Insufficient destination storage.
- [ ] App stopped or process killed by the operating system.

Record the user-visible message, relevant sanitized logs, and the recovery action. Never publish access tokens, private keys, personal file paths, public IP addresses, or private device identifiers in public issue reports.

## 7. Result format

For every test case, use one of these exact statuses:

- **PASS** — performed on the recorded build/device and met the expected result.
- **FAIL** — performed and did not meet the expected result; include reproduction steps.
- **NOT RUN** — not performed. Do not treat it as a pass.
- **BLOCKED** — could not be performed; explain the blocker.

A useful evidence entry includes:

```text
Test case:
Status: PASS / FAIL / NOT RUN / BLOCKED
LocalBridge build:
Android device and OS:
Mac model and macOS:
Date/time and time zone:
Steps performed:
Expected result:
Actual result:
File size / checksum (if applicable):
Sanitized log or evidence link:
```

## 8. Reporting and release decisions

- Keep raw test evidence separate from planned test cases.
- Preserve failed results; do not replace them with a summary that hides the failure.
- Repeat important checks after changes to networking, transfer protocol, background services, permissions, or packaging.
- A result applies only to the tested build, device, OS version, and conditions.
- Do not label a release production-ready solely because it builds or because a subset of tests passes.

# Protocol v1 Golden Fixtures

Test fixtures for protocol v1 as defined in 03_PROTOCOL.md.

Files prefixed with `INVALID_` are expected to be rejected by both platforms.
Files without the prefix are valid messages and must be parsed and accepted.

Both the Android and macOS implementations must test against these fixtures
before Phase 3 is marked complete (10_BUILD_PHASES.md Phase 3 checklist).

| File | Type | Expected result |
|------|------|-----------------|
| session_hello.json | session.hello | Parsed and accepted |
| clipboard_push_text.json | clipboard.push | Parsed and accepted |
| clipboard_ack_applied.json | clipboard.ack | Parsed and accepted |
| INVALID_unknown_type.json | unknown | Rejected with UNSUPPORTED_MESSAGE_TYPE |

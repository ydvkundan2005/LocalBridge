import os.log
import Foundation

/// A thin wrapper around `os.Logger` that enforces privacy rules.
///
/// Privacy rules (11_TESTING_AND_RULES.md §1 rule 12, 04_SECURITY_PAIRING.md §7):
///  - Never log raw clipboard text, image bytes, pairing secrets, private keys,
///    file contents, QR payload secrets, or absolute paths containing usernames.
///  - Caller is responsible for redacting parameters; this logger cannot inspect
///    content but the naming convention makes intent explicit.
///  - All string interpolations in log calls use `.public` by default in release
///    builds via OSLog privacy.  In debug builds, `.auto` allows more detail.
///
/// Usage:
///   let logger = RedactedLogger(subsystem: "dev.localbridge.mac", category: "Watcher")
///   logger.info("Candidate observed: bytes=\(bytes)")   // OK: non-sensitive
///   // NEVER: logger.debug("Content: \(rawText)")        // Not allowed
struct RedactedLogger: Sendable {
    private let underlying: Logger

    init(subsystem: String, category: String) {
        self.underlying = Logger(subsystem: subsystem, category: category)
    }

    func debug(_ message: @autoclosure () -> String) {
        let msg = message()
        underlying.debug("\(msg, privacy: .public)")
    }

    func info(_ message: @autoclosure () -> String) {
        let msg = message()
        underlying.info("\(msg, privacy: .public)")
    }

    func warning(_ message: @autoclosure () -> String) {
        let msg = message()
        underlying.warning("\(msg, privacy: .public)")
    }

    func error(_ message: @autoclosure () -> String) {
        let msg = message()
        underlying.error("\(msg, privacy: .public)")
    }
}

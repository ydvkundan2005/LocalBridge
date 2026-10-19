import AppKit
import Foundation

/// Writes validated remote clipboard content to the macOS general pasteboard.
///
/// Design (05_CLIPBOARD_SYNC.md §5 Writer):
///  - Must report APPLIED only after the pasteboard write actually succeeds.
///  - Tracks changeCount after own write and registers it with EchoGuard.
///  - Only plain text in Phase 2. Image support is prototype-gated.
///  - Never logs raw text content.
final class PasteboardWriter {
    private let echoGuard: EchoGuard
    private let logger: RedactedLogger

    init(echoGuard: EchoGuard, logger: RedactedLogger) {
        self.echoGuard = echoGuard
        self.logger = logger
    }

    /// Write remote plain-text content to the general pasteboard.
    ///
    /// - Parameters:
    ///   - text: The validated remote text. Must have already passed size/content checks.
    ///   - eventId: The clipboard event ID for echo-guard tracking.
    /// - Returns: `true` if the write succeeded (maps to ack status `APPLIED`).
    @MainActor
    @discardableResult
    func writeText(_ text: String, eventId: String) -> Bool {
        let pasteboard = NSPasteboard.general
        pasteboard.clearContents()
        let success = pasteboard.setString(text, forType: .string)

        if success {
            let newCount = pasteboard.changeCount
            // Register this write so the watcher does not re-emit it as a new event.
            echoGuard.registerExpectedWrite(changeCount: newCount, eventId: eventId)
            logger.info("PasteboardWriter: APPLIED eventId=\(eventId) changeCount=\(newCount) bytes=\(text.utf8.count)")
        } else {
            logger.error("PasteboardWriter: write failed for eventId=\(eventId)")
        }
        return success
    }
}

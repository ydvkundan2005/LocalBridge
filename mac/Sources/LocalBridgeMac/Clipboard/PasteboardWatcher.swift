import AppKit
import Foundation

/// A candidate clipboard item observed by the pasteboard watcher.
/// Mirrors ClipboardCandidate on Android; field semantics are from 03_PROTOCOL.md §3.
struct PasteboardCandidate: Sendable {
    let text: String
    let changeCount: Int
    let mimeType: String
    let byteLength: Int
    let capturedAt: Date
}

/// Polls NSPasteboard.general.changeCount on a timer to detect external changes.
///
/// Design (05_CLIPBOARD_SYNC.md §5 Watcher):
///  - Poll interval: 250 ms (initial target; tune only after profiling).
///  - Emit at most one candidate per changeCount increment.
///  - Do not read/send unless count changes.
///  - EchoGuard suppresses our own writes being re-emitted.
///  - Runs on the main thread (NSPasteboard requires main thread access).
///
@MainActor
final class PasteboardWatcher {
    /// Called when a new pasteboard candidate is observed.
    /// Invoked on the main thread.
    var onCandidate: ((PasteboardCandidate) -> Void)?

    private let echoGuard: EchoGuard
    private let logger: RedactedLogger
    private var timer: Timer?
    private var lastChangeCount: Int = NSPasteboard.general.changeCount
    private let pollInterval: TimeInterval = 0.250 // 250 ms per spec

    init(echoGuard: EchoGuard, logger: RedactedLogger) {
        self.echoGuard = echoGuard
        self.logger = logger
    }

    func start() {
        guard timer == nil else { return }
        // Capture initial count so the first poll does not fire for pre-existing content.
        lastChangeCount = NSPasteboard.general.changeCount
        timer = Timer.scheduledTimer(
            withTimeInterval: pollInterval,
            repeats: true
        ) { [weak self] _ in
            // Timer callback is non-isolated; hop to MainActor for poll() (Swift 6).
            Task { @MainActor [weak self] in
                self?.poll()
            }
        }
        logger.info("PasteboardWatcher started — pollInterval=\(pollInterval)s")
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        logger.info("PasteboardWatcher stopped")
    }

    // MARK: - Poll

    private func poll() {
        let pasteboard = NSPasteboard.general
        let currentCount = pasteboard.changeCount
        guard currentCount != lastChangeCount else { return }

        let previousCount = lastChangeCount
        lastChangeCount = currentCount

        // EchoGuard: check if this change was our own write
        if echoGuard.consumeExpectedWrite(changeCount: currentCount) {
            logger.debug("EchoGuard suppressed own write at changeCount=\(currentCount)")
            return
        }

        // Read only plain text in Phase 2. Image support per 05_CLIPBOARD_SYNC.md §2.
        guard let text = pasteboard.string(forType: .string) else {
            logger.debug("changeCount changed \(previousCount)→\(currentCount) but no plain text")
            return
        }

        guard !text.isEmpty else { return }

        let bytes = text.utf8.count
        // Enforce 1 MiB inline text limit (03_PROTOCOL.md §3)
        let maxBytes = 1 * 1024 * 1024
        guard bytes <= maxBytes else {
            logger.info("Pasteboard item oversized: \(bytes) > \(maxBytes) — ITEM_TOO_LARGE")
            return
        }

        let candidate = PasteboardCandidate(
            text: text,
            changeCount: currentCount,
            mimeType: "text/plain;charset=utf-8",
            byteLength: bytes,
            capturedAt: Date()
        )
        logger.debug("Pasteboard candidate: bytes=\(bytes) changeCount=\(currentCount)")
        onCandidate?(candidate)
    }
}

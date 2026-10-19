import Foundation

/// Prevents the pasteboard watcher from re-emitting content that LocalBridge
/// itself wrote to the pasteboard, which would cause an infinite sync loop.
///
/// Mechanism (05_CLIPBOARD_SYNC.md §6):
///  1. PasteboardWriter calls registerExpectedWrite() after a successful write,
///     providing the new changeCount.
///  2. PasteboardWatcher calls consumeExpectedWrite() on the next poll; if the
///     changeCount matches a registered entry within the suppression window, the
///     event is suppressed and the marker consumed.
///  3. Expired markers are deleted so future real user copies with the same
///     content hash are not permanently suppressed.
///
/// Suppression window default: 3 seconds (05_CLIPBOARD_SYNC.md §6, step 3).
/// Thread-safety: all methods must be called from the main thread.
final class EchoGuard {
    private struct WriteMarker {
        let changeCount: Int
        let eventId: String
        let expiresAt: Date
    }

    private var markers: [WriteMarker] = []
    private let suppressionWindow: TimeInterval = 3.0 // 3 seconds per spec

    /// Register a changeCount written by LocalBridge for echo suppression.
    @MainActor
    func registerExpectedWrite(changeCount: Int, eventId: String) {
        purgeExpired()
        markers.append(WriteMarker(
            changeCount: changeCount,
            eventId: eventId,
            expiresAt: Date().addingTimeInterval(suppressionWindow)
        ))
    }

    /// Check if a changeCount belongs to a LocalBridge write. Consumes the marker.
    /// Returns true if the event should be suppressed.
    @MainActor
    func consumeExpectedWrite(changeCount: Int) -> Bool {
        purgeExpired()
        if let index = markers.firstIndex(where: { $0.changeCount == changeCount }) {
            markers.remove(at: index)
            return true
        }
        return false
    }

    @MainActor
    private func purgeExpired() {
        let now = Date()
        markers.removeAll { $0.expiresAt <= now }
    }
}

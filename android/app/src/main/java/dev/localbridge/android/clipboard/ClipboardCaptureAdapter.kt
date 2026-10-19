package dev.localbridge.android.clipboard

/**
 * Internal interface that every capture adapter must implement.
 *
 * Behaviour contract (from 02_ARCHITECTURE.md §3 and 05_CLIPBOARD_SYNC.md §3):
 *  - start() / stop() may be called from any coroutine context but must
 *    co-ordinate internal state safely.
 *  - capabilityStatus() must reflect the real-time adapter state; it must not
 *    return AVAILABLE merely because permission was granted at some earlier time.
 *  - onCandidate is a callback set by ClipboardCaptureManager before start().
 *    Adapters call it when a new clipboard candidate is observed.
 *  - lastFailure() returns the most recent error description (never raw payload).
 *
 * Each adapter is isolated in its own class so that a failure in one does not
 * affect the others or crash the app (05_CLIPBOARD_SYNC.md §3-A note on
 * narrow Shizuku boundary).
 */
interface ClipboardCaptureAdapter {
    /** Human-readable name for diagnostics (e.g. "Shizuku", "Standard"). */
    val adapterName: String

    /** Called by manager to register the candidate callback before start(). */
    var onCandidate: ((ClipboardCandidate) -> Unit)?

    /** Begin listening for clipboard changes. Must be idempotent if already started. */
    suspend fun start()

    /** Stop listening. Must be idempotent if already stopped. */
    suspend fun stop()

    /** Returns current capability as of this call. */
    fun capabilityStatus(): CaptureCapabilityStatus

    /** Returns a redacted description of the last failure, or null if none. */
    fun lastFailure(): String?
}

package dev.localbridge.android.clipboard

/**
 * A clipboard item observed by a capture adapter and offered to
 * ClipboardCaptureManager for validation and forwarding.
 *
 * Fields mirror the protocol clipboard.push payload (03_PROTOCOL.md §3).
 * The manager is responsible for hashing and size checks before building
 * the wire message.
 *
 * @property rawText   Decoded plain-text content; null when unavailable.
 * @property mimeType  MIME type string as reported by the ClipData item.
 *                     The manager validates this against the v1 allow-list.
 * @property byteLength Estimated byte length from content (before encoding).
 *                      The manager recomputes from the actual UTF-8 bytes.
 * @property sourceLabel Short human-readable label for diagnostics (never
 *                       logged as raw sensitive content).
 * @property capturedAtMs Wall-clock epoch milliseconds at observation time.
 */
data class ClipboardCandidate(
    val rawText: String?,
    val mimeType: String,
    val byteLength: Long,
    val sourceLabel: String,
    val capturedAtMs: Long,
)

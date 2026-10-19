package dev.localbridge.android.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Standard (public API) clipboard capture adapter.
 *
 * DOCUMENTED LIMITATION (05_CLIPBOARD_SYNC.md §3-B):
 *   Android 10+ (API 29+) restricts background clipboard reads to apps that
 *   are the default IME or have focus.  This adapter can only capture clipboard
 *   changes reliably when the app is in the foreground.
 *
 *   Do NOT present this adapter as a general background-capture solution.
 *   capabilityStatus() returns LIMITED to reflect this constraint truthfully.
 *
 * This adapter uses ClipboardManager.OnPrimaryClipChangedListener which is
 * a public, documented Android API.  No hidden internals are used here.
 */
class StandardClipboardCapture(
    private val context: Context,
) : ClipboardCaptureAdapter {

    override val adapterName: String = "Standard"
    override var onCandidate: ((ClipboardCandidate) -> Unit)? = null

    private val clipboardManager: ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private var isListening = false
    private var lastError: String? = null

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged()
    }

    override suspend fun start() {
        if (isListening) return
        try {
            clipboardManager.addPrimaryClipChangedListener(listener)
            isListening = true
            lastError = null
            Log.d(TAG, "Standard adapter started (foreground-only capability)")
        } catch (e: Exception) {
            lastError = "Failed to register listener: ${e.javaClass.simpleName}"
            Log.w(TAG, "Standard adapter start failed", e)
        }
    }

    override suspend fun stop() {
        if (!isListening) return
        try {
            clipboardManager.removePrimaryClipChangedListener(listener)
        } catch (e: Exception) {
            Log.w(TAG, "Standard adapter stop error (ignored)", e)
        } finally {
            isListening = false
            Log.d(TAG, "Standard adapter stopped")
        }
    }

    /**
     * Returns LIMITED because Android 10+ restricts background reads.
     * This is the honest status regardless of whether the listener is registered.
     */
    override fun capabilityStatus(): CaptureCapabilityStatus =
        if (!isListening) CaptureCapabilityStatus.STOPPED
        else CaptureCapabilityStatus.LIMITED

    override fun lastFailure(): String? = lastError

    private fun onPrimaryClipChanged() {
        // IMPORTANT: on Android 10+, this callback may fire but reading
        // getPrimaryClip() from a background state may return null or throw.
        // We only attempt the read; if it fails we record and move on.
        val clip = try {
            clipboardManager.primaryClip
        } catch (e: Exception) {
            lastError = "Read denied after change event: ${e.javaClass.simpleName}"
            Log.w(TAG, "Clipboard read denied after OnPrimaryClipChanged — API ${Build.VERSION.SDK_INT}")
            return
        } ?: return // null means OS withheld content; do not synthesise data

        val item = clip.getItemAt(0) ?: return
        val text = item.coerceToText(context)?.toString() ?: return
        if (text.isBlank()) return

        val candidate = ClipboardCandidate(
            rawText = text,
            mimeType = "text/plain;charset=utf-8",
            byteLength = text.toByteArray(Charsets.UTF_8).size.toLong(),
            sourceLabel = clip.description?.label?.toString()?.take(40) ?: "unknown",
            capturedAtMs = System.currentTimeMillis(),
        )
        Log.d(TAG, "Standard adapter: candidate byteLength=${candidate.byteLength}")
        onCandidate?.invoke(candidate)
    }

    companion object {
        private const val TAG = "LB.StdCapture"
    }
}

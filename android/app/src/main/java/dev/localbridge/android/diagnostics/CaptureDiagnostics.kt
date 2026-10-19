package dev.localbridge.android.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import dev.localbridge.android.clipboard.AdapterSnapshot
import dev.localbridge.android.clipboard.CaptureCapabilityStatus
import dev.localbridge.android.clipboard.ClipboardCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Records capture events for Phase 1 investigation display and log output.
 *
 * PRIVACY RULES (11_TESTING_AND_RULES.md §1 rules 9, 12 and 04_SECURITY_PAIRING.md §7):
 *  - Do NOT log raw clipboard text content.
 *  - Log only: byte length, source label (truncated), MIME type, timestamp, status.
 *  - The history held here is in-memory only; it is never persisted in Phase 1.
 *  - Clear on stop.
 *
 * This class is Phase 1 scaffolding. In Phase 5 it will be replaced by the
 * persistent SQLite ClipboardItem repository.
 */
class CaptureDiagnostics(private val context: Context) {

    data class CaptureEvent(
        val capturedAtMs: Long,
        val byteLength: Long,
        val mimeType: String,
        val sourceLabel: String,
        val adapterName: String,
        val status: String, // e.g. "observed", "oversized", "denied"
    )

    private val _events = MutableStateFlow<List<CaptureEvent>>(emptyList())

    /** Observable list of capture events (newest first). Max 50 in Phase 1. */
    val events: StateFlow<List<CaptureEvent>> = _events

    private val _adapterSnapshot = MutableStateFlow<AdapterSnapshot?>(null)
    val adapterSnapshot: StateFlow<AdapterSnapshot?> = _adapterSnapshot

    /** Call whenever a ClipboardCandidate is observed by the active adapter. */
    fun recordCandidate(candidate: ClipboardCandidate, adapterName: String) {
        // Log only non-sensitive metadata — never raw text.
        Log.d(TAG, "Candidate observed: mimeType=${candidate.mimeType}, " +
                "byteLength=${candidate.byteLength}, source=${candidate.sourceLabel}")

        val event = CaptureEvent(
            capturedAtMs = candidate.capturedAtMs,
            byteLength = candidate.byteLength,
            mimeType = candidate.mimeType,
            sourceLabel = candidate.sourceLabel,
            adapterName = adapterName,
            status = "observed",
        )
        addEvent(event)
    }

    /** Record a candidate that exceeded size limits. */
    fun recordOversized(candidate: ClipboardCandidate, adapterName: String, limitBytes: Long) {
        Log.w(TAG, "Candidate OVERSIZED: byteLength=${candidate.byteLength} > limit=$limitBytes")
        val event = CaptureEvent(
            capturedAtMs = candidate.capturedAtMs,
            byteLength = candidate.byteLength,
            mimeType = candidate.mimeType,
            sourceLabel = candidate.sourceLabel,
            adapterName = adapterName,
            status = "oversized(limit=$limitBytes)",
        )
        addEvent(event)
    }

    /** Update the current adapter snapshot for display. */
    fun updateAdapterSnapshot(snapshot: AdapterSnapshot) {
        _adapterSnapshot.value = snapshot
    }

    fun clear() {
        _events.value = emptyList()
    }

    /**
     * Returns a summary of the device environment.
     * Used by the Diagnostics screen and by test-evidence/phase-1.md.
     * Do not include clipboard content or pairing secrets.
     */
    fun deviceEnvironmentSummary(): String = buildString {
        appendLine("=== LocalBridge Phase 1 Device Environment ===")
        appendLine("Manufacturer : ${Build.MANUFACTURER}")
        appendLine("Model        : ${Build.MODEL}")
        appendLine("Android API  : ${Build.VERSION.SDK_INT}")
        appendLine("Release      : ${Build.VERSION.RELEASE}")
        appendLine("Codename     : ${Build.VERSION.CODENAME}")
        appendLine("Build ID     : ${Build.DISPLAY}")
        appendLine("Security     : ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Board        : ${Build.BOARD}")
        appendLine("CPU ABI      : ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("SDK INT      : ${Build.VERSION.SDK_INT}")
        appendLine("Shizuku pkg  : ${isShizukuInstalled()}")
    }

    private fun isShizukuInstalled(): String = try {
        val pi = context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        "installed v${pi.versionName} (${pi.longVersionCode})"
    } catch (_: Exception) {
        "not installed"
    }

    private fun addEvent(event: CaptureEvent) {
        val current = _events.value.toMutableList()
        current.add(0, event) // newest first
        if (current.size > MAX_EVENTS) current.removeAt(current.lastIndex)
        _events.value = current
    }

    companion object {
        private const val TAG = "LB.Diagnostics"
        private const val MAX_EVENTS = 50
    }
}

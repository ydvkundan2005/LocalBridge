package dev.localbridge.android.clipboard

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Clipboard capture manager — coordinates adapter selection and lifecycle.
 *
 * Phase 1 scope: adapter selection, capability reporting, and diagnostics.
 * No networking; candidates are passed to [onCandidateObserved] for wiring
 * to protocol layer in Phase 3.
 *
 * Adapter priority (05_CLIPBOARD_SYNC.md §3):
 *  1. ShizukuClipboardCapture (experimental, will be gated on Phase 1 evidence)
 *  2. StandardClipboardCapture (limited — foreground only on Android 10+)
 *
 * The manager exposes [capabilityStatus] as a StateFlow so the UI layer can
 * react to real-time changes without polling.
 */
class ClipboardCaptureManager(
    private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val shizukuAdapter = ShizukuClipboardCapture(context)
    private val standardAdapter = StandardClipboardCapture(context)

    /** The adapter currently selected for capture. Null when stopped. */
    private var activeAdapter: ClipboardCaptureAdapter? = null

    private val _capabilityStatus =
        MutableStateFlow<CaptureCapabilityStatus>(CaptureCapabilityStatus.STOPPED)

    /** Real-time capability status; observe from UI/ViewModel. */
    val capabilityStatus: StateFlow<CaptureCapabilityStatus> = _capabilityStatus

    /**
     * Callback invoked when a candidate clipboard item is observed.
     * In Phase 3 this will be wired to the protocol sender.
     * In Phase 1 it can point to the diagnostics recorder.
     */
    var onCandidateObserved: ((ClipboardCandidate) -> Unit)? = null

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start capture using the highest-capability available adapter.
     *
     * Does nothing if already running.
     *
     * This must be called only when the master state is ON and the OS grants
     * the required permissions (07_MASTER_TOGGLE_STATE.md §2 ON transition).
     */
    fun startCapture() {
        if (activeAdapter != null) return
        scope.launch {
            val chosen = selectAdapter()
            chosen.onCandidate = { candidate -> onCandidateObserved?.invoke(candidate) }
            chosen.start()
            activeAdapter = chosen
            _capabilityStatus.value = chosen.capabilityStatus()
            Log.d(TAG, "Capture started via adapter=${chosen.adapterName}, status=${_capabilityStatus.value}")
        }
    }

    /**
     * Stop capture immediately.
     *
     * Must be called as the first step in the OFF transition
     * (07_MASTER_TOGGLE_STATE.md §2 OFF transition, step 3).
     */
    fun stopCapture() {
        scope.launch {
            val adapter = activeAdapter ?: return@launch
            adapter.stop()
            activeAdapter = null
            _capabilityStatus.value = CaptureCapabilityStatus.STOPPED
            Log.d(TAG, "Capture stopped via adapter=${adapter.adapterName}")
        }
    }

    /**
     * Returns a snapshot of the current capability, last failure, and adapter
     * name.  Used by [CaptureDiagnostics] and Settings screen.
     */
    fun diagnosticSnapshot(): AdapterSnapshot {
        val adapter = activeAdapter
        return AdapterSnapshot(
            adapterName = adapter?.adapterName ?: "none",
            status = adapter?.capabilityStatus() ?: CaptureCapabilityStatus.STOPPED,
            lastFailure = adapter?.lastFailure(),
        )
    }

    /** Release resources; call from the owning lifecycle owner's onDestroy. */
    fun destroy() {
        scope.cancel()
    }

    // -------------------------------------------------------------------------
    // Private
    // -------------------------------------------------------------------------

    /**
     * Choose the best available adapter.
     *
     * Phase 1 policy: always prefer Shizuku (which currently returns UNAVAILABLE
     * until its binder API is verified).  If Shizuku is structurally unavailable
     * (not installed), fall back to Standard.
     *
     * After Phase 1 evidence is collected, this logic will be updated based on
     * what the device actually supports (ADR-001).
     */
    private fun selectAdapter(): ClipboardCaptureAdapter {
        // Phase 1: Shizuku is the research target.  Standard is the fallback.
        // The selection will be refined after Phase 1 evidence per ADR-001.
        return if (isShizukuInstalled()) {
            Log.d(TAG, "Shizuku present — using ShizukuClipboardCapture (Phase 1 investigation mode)")
            shizukuAdapter
        } else {
            Log.d(TAG, "Shizuku not present — falling back to StandardClipboardCapture")
            standardAdapter
        }
    }

    private fun isShizukuInstalled(): Boolean = try {
        context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val TAG = "LB.CaptureManager"
    }
}

/** Immutable snapshot of adapter state for diagnostics. */
data class AdapterSnapshot(
    val adapterName: String,
    val status: CaptureCapabilityStatus,
    val lastFailure: String?,
)

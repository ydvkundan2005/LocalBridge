package dev.localbridge.android.clipboard

import android.content.Context
import android.util.Log

/**
 * Shizuku-backed clipboard capture adapter — Phase 1 stub.
 *
 * STATUS: Shizuku SDK dependency is intentionally OMITTED in Phase 1.
 *
 * Reason: The ShizukuProvider <provider> entry in AndroidManifest.xml requires
 * android.permission.INTERACT_ACROSS_USERS_FULL, which causes the OEM package
 * installer (MIUI, OxygenOS, One UI) to crash when sideloading the APK.
 * This adapter always returns UNAVAILABLE and the manager falls back to
 * StandardClipboardCapture automatically.
 *
 * Re-enable in Phase 2 after:
 *   1. Successful Phase 1 install confirmed on the target device.
 *   2. ShizukuProvider re-added to the manifest.
 *   3. shizuku-api and shizuku-provider re-added to build.gradle.kts.
 *   4. Binder API verified from official Android source for target OS version.
 *
 * Phase 1 investigation checklist (10_BUILD_PHASES.md):
 *   □ Record Android API level, OS build, security patch from device.
 *   □ Inspect Android framework source for IClipboard service descriptor.
 *   □ Confirm Shizuku 12.2.0 Binder.transact path or UserService equivalent.
 *   □ Test all failure modes: not installed, not running, permission denied.
 */
class ShizukuClipboardCapture(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : ClipboardCaptureAdapter {

    override val adapterName: String = "Shizuku"
    override var onCandidate: ((ClipboardCandidate) -> Unit)? = null

    private val lastError: String =
        "Phase 1: Shizuku SDK omitted — re-enable in Phase 2 after install confirmed"

    override suspend fun start() {
        // No-op in Phase 1: Shizuku SDK not linked.
        Log.d(TAG, "ShizukuClipboardCapture.start() called — Phase 1 stub, no-op")
    }

    override suspend fun stop() {
        // No-op in Phase 1.
        Log.d(TAG, "ShizukuClipboardCapture.stop() called — Phase 1 stub, no-op")
    }

    override fun capabilityStatus(): CaptureCapabilityStatus =
        CaptureCapabilityStatus.UNAVAILABLE

    override fun lastFailure(): String = lastError

    companion object {
        private const val TAG = "LB.ShizukuCapture"
    }
}

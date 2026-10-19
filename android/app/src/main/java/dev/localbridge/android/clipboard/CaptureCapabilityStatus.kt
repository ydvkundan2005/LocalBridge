package dev.localbridge.android.clipboard

/**
 * Describes whether the active capture adapter can currently observe
 * clipboard changes.  Matches the states defined in 05_CLIPBOARD_SYNC.md §3-D.
 *
 * These values are used by the UI layer to display honest status to the user.
 * The manager must never report AVAILABLE unless the listener is actually
 * registered and responding.
 */
enum class CaptureCapabilityStatus {
    /** Listener registered and confirmed working in the current environment. */
    AVAILABLE,

    /** Usable only in a documented subset of states/apps (e.g. foreground only). */
    LIMITED,

    /** User must complete a setup step (Shizuku install, permission grant, etc.). */
    NEEDS_AUTHORIZATION,

    /** This adapter cannot function on the current device/OS build. */
    UNAVAILABLE,

    /** Capture is intentionally stopped because master state is OFF. */
    STOPPED,
}

package dev.localbridge.android.network

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.localbridge.android.ui.QuickSyncTrampolineActivity
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LogcatClipboardMonitor — background clipboard detection without accessibility or root.
 *
 * HOW IT WORKS (the same trick used by Tasker / Join by joaomgcd):
 *  - Android's ClipboardService logs to logcat whenever any app reads or writes the clipboard.
 *  - Lines look like:  "ClipboardService: <pkg> pasted from your clipboard"
 *    or: "ClipboardService: Setting the clipboard from package <pkg>"
 *  - With READ_LOGS permission (granted once via ADB — persists until app is uninstalled),
 *    we can tail the logcat in real-time from within a background thread.
 *  - When we detect that ANY external app (not LocalBridge itself) has just been set as the
 *    clipboard owner, we launch QuickSyncTrampolineActivity — the invisible zero-UI activity
 *    that gains window focus, reads the clipboard legally, and pushes it to Mac.
 *
 * READ_LOGS is NOT a runtime permission — it cannot be granted by a user dialog. It must be
 * granted via ADB: `adb shell pm grant dev.localbridge.android android.permission.READ_LOGS`
 * This grant survives reboots but resets if the app is uninstalled.
 *
 * Lifecycle:
 *  - Created and started by ClipboardForegroundService when Master toggle is ON.
 *  - Stopped (process killed, stream closed) when Master toggle is OFF or service destroys.
 */
class LogcatClipboardMonitor(private val context: Context) {

    private val TAG = "LB.LogcatMonitor"

    /** Coroutine scope tied to this monitor's lifetime */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Tracks the last timestamp string seen, to avoid firing duplicate events */
    @Volatile private var lastEventTimestamp: String = ""

    /** True once we have confirmed READ_LOGS is available */
    @Volatile var hasPermission: Boolean = false
        private set

    /**
     * Start tailing logcat. Returns immediately; the work runs on an IO coroutine.
     * If READ_LOGS is not granted the coroutine exits cleanly after logging a warning.
     */
    fun start() {
        scope.launch {
            tailLogcat()
        }
        Log.i(TAG, "LogcatClipboardMonitor started")
    }

    /** Stop the monitor and cancel all coroutines. */
    fun stop() {
        scope.cancel()
        Log.i(TAG, "LogcatClipboardMonitor stopped")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private implementation
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun tailLogcat() = withContext(Dispatchers.IO) {
        try {
            // Run logcat, time format, only ClipboardService tag, level V+
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "time", "-s", "ClipboardService:V", "-T", "1")
            )
            hasPermission = true

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            Log.i(TAG, "Logcat stream open — watching for clipboard events")

            while (isActive) {
                line = reader.readLine() ?: break   // null = stream closed
                processLogLine(line)
            }

            process.destroy()
            Log.i(TAG, "Logcat stream closed")
        } catch (se: SecurityException) {
            hasPermission = false
            Log.w(TAG, "READ_LOGS permission not granted — logcat monitor inactive. " +
                    "Grant with: adb shell pm grant dev.localbridge.android android.permission.READ_LOGS")
        } catch (e: Exception) {
            if (isActive) {
                Log.w(TAG, "Logcat monitor error: ${e.message}")
            }
        }
    }

    /**
     * Inspect a single logcat line. We fire on two distinct signals:
     *
     * 1. "Setting the clipboard from package <X>" — ANY app just wrote to the clipboard.
     *    This fires even when the source app is in focus (normal copy/paste).
     *
     * 2. "Denying clipboard access to dev.localbridge.android" — The OS denied our
     *    background read. This means a clipboard event happened but we were blocked.
     *    We still need to fire the trampoline to read it from focus.
     *
     * We deliberately ignore lines from dev.localbridge.android as the setter, so we
     * don't loop when Mac → Android sync writes to our own clipboard.
     */
    private fun processLogLine(line: String) {
        val lower = line.lowercase()

        val isClipboardSet = lower.contains("setting the clipboard from package") ||
                lower.contains("primaryclipchanged") ||
                lower.contains("clipboard changed") ||
                lower.contains("clipboardservice: localbridge pasted") // our own read confirmation

        val isDenied = lower.contains("denying clipboard access to dev.localbridge.android")

        // Skip lines where LocalBridge itself is the SETTER (would cause an echo loop)
        val isOurOwnWrite = lower.contains("setting the clipboard from package dev.localbridge.android")

        if ((isClipboardSet && !isOurOwnWrite) || isDenied) {
            // Extract timestamp (first token, e.g. "10-19 01:50:01.096") to deduplicate
            val timestamp = line.substringBefore(" ", line).take(22)
            if (timestamp == lastEventTimestamp) return
            lastEventTimestamp = timestamp

            Log.d(TAG, "Clipboard event detected — launching trampoline. line=${line.take(120)}")
            launchTrampoline()
        }
    }

    /**
     * Launch QuickSyncTrampolineActivity in the background (new task, no UI shown).
     * It will gain focus, read the clipboard, send to Mac, and finish immediately.
     */
    private fun launchTrampoline() {
        try {
            val intent = Intent(context, QuickSyncTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                // Tell the trampoline this is an auto-trigger (not a manual tap)
                putExtra("trigger_source", "logcat_monitor")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch trampoline: ${e.message}")
        }
    }
}

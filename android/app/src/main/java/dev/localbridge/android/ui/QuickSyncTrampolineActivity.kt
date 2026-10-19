package dev.localbridge.android.ui

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import dev.localbridge.android.LocalBridgeApplication

/**
 * Invisible translucent trampoline activity.
 *
 * Triggered by:
 *  - The "⚡ Sync Now" notification action on the persistent foreground service notification.
 *  - The Quick Settings "Sync to Mac" tile.
 *  - The Home Screen Widget clipboard button (📋).
 *  - LogcatClipboardMonitor — automatically when ANY external app writes to the clipboard
 *    (requires READ_LOGS permission, granted once via ADB).
 *
 * Android 10+ strictly restricts primaryClip to the app with active window focus.
 * By waiting for onWindowFocusChanged(true) this activity legally acquires window focus,
 * reads the system clipboard, pushes it to Mac via WebSocket, shows a brief toast, and
 * finishes immediately with no exit animation so the user never sees it.
 *
 * Debounce: Back-to-back logcat events can trigger this activity many times in rapid
 * succession for a single clipboard change. A 1.5-second global debounce prevents
 * duplicate sends without suppressing a second genuine copy event shortly after.
 */
class QuickSyncTrampolineActivity : Activity() {

    private var hasExecuted = false
    private val handler = Handler(Looper.getMainLooper())
    private val fallbackRunnable = Runnable {
        if (!hasExecuted) {
            hasExecuted = true
            readAndSendClipboard("timeout_fallback")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Debounce: if this was triggered by the logcat monitor, check if we already
        // sent this clipboard item very recently (within DEBOUNCE_MS).
        val triggerSource = intent.getStringExtra("trigger_source") ?: "manual"
        val now = System.currentTimeMillis()
        if (triggerSource == "logcat_monitor") {
            val elapsed = now - lastLaunchMs
            if (elapsed < DEBOUNCE_MS) {
                Log.d(TAG, "Debounced logcat trigger (${elapsed}ms since last launch) — finishing immediately")
                finish()
                overridePendingTransition(0, 0)
                return
            }
        }
        lastLaunchMs = now

        // Ensure the window has no frame or animations
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )
        // Fallback: if focus event takes longer than 400ms, attempt capture anyway
        handler.postDelayed(fallbackRunnable, 400)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hasExecuted) {
            hasExecuted = true
            handler.removeCallbacks(fallbackRunnable)
            readAndSendClipboard("window_focus")
        }
    }

    private fun readAndSendClipboard(trigger: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.coerceToText(this)?.toString()
                if (!text.isNullOrEmpty()) {
                    val app = applicationContext as? LocalBridgeApplication
                    val client = app?.webSocketClient
                    if (client?.isPaired() == true) {
                        // Check deduplication — don't echo if this text is the same as last sent
                        if (text == lastSentText) {
                            Log.d(TAG, "Skipping duplicate clipboard content ($trigger)")
                            return
                        }
                        lastSentText = text
                        client.sendClipboard(text)
                        // Only show toast for manual triggers to avoid notification spam for auto-sync
                        val triggerSource = intent.getStringExtra("trigger_source") ?: "manual"
                        if (triggerSource != "logcat_monitor") {
                            Toast.makeText(this, "⚡ Copied text sent to Mac!", Toast.LENGTH_SHORT).show()
                        }
                        Log.i(TAG, "Sent ${text.length} chars to Mac ($trigger, source=$triggerSource)")
                    } else {
                        val triggerSource = intent.getStringExtra("trigger_source") ?: "manual"
                        if (triggerSource != "logcat_monitor") {
                            Toast.makeText(this, "LocalBridge: Mac is not connected", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val triggerSource = intent.getStringExtra("trigger_source") ?: "manual"
                    if (triggerSource != "logcat_monitor") {
                        Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                val triggerSource = intent.getStringExtra("trigger_source") ?: "manual"
                if (triggerSource != "logcat_monitor") {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync clipboard: ${e.message}")
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(fallbackRunnable)
    }

    companion object {
        private const val TAG = "LB.Trampoline"

        /** Suppress duplicate auto-triggers within this window (ms) */
        private const val DEBOUNCE_MS = 1500L

        /** Timestamp of the most recent launch of this activity */
        @Volatile
        private var lastLaunchMs: Long = 0L

        /** Last text successfully sent — prevents echo-back loops */
        @Volatile
        var lastSentText: String? = null
    }
}

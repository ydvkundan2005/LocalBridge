package dev.localbridge.android.network

import android.app.*
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import dev.localbridge.android.ui.MainActivity
import dev.localbridge.android.ui.QuickSyncTrampolineActivity

/**
 * Foreground service that keeps clipboard sync alive when the app is in the background.
 *
 * Two-layer strategy for Android 10+ (API 29+):
 *
 *  Layer 1 — Standard ClipboardManager listener:
 *    Registered on the main looper. On some ROMs / Android builds this still fires when our
 *    foreground-service process is visible (notification shown). When it fires and we can
 *    read the clip, we send directly without showing any UI.
 *
 *  Layer 2 — LogcatClipboardMonitor (primary path on Android 10+):
 *    Tails logcat (requires READ_LOGS, granted once via ADB) for ClipboardService events.
 *    When ANY external app writes to the clipboard, a ClipboardService log line appears.
 *    We detect this and auto-launch QuickSyncTrampolineActivity — the invisible activity
 *    that gains window focus, reads the clipboard legally, and pushes to Mac in ~50ms.
 *    This is the same mechanism used by Tasker and Join by joaomgcd.
 *
 * Permission required (one-time ADB command, survives reboot):
 *   adb shell pm grant dev.localbridge.android android.permission.READ_LOGS
 *
 * Lifecycle:
 *  - Started when Master toggle is turned ON and a paired WebSocket connection is active.
 *  - Stopped when Master toggle is turned OFF or the app is destroyed.
 *  - START_STICKY so Android restarts us after process death while toggle is ON.
 */
class ClipboardForegroundService : Service() {

    companion object {
        private const val TAG = "LB.FgService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "localbridge_clipboard_sync"

        /** Static callback set by MainActivity to receive captured text */
        @Volatile
        var onClipboardChanged: ((String) -> Unit)? = null

        fun start(context: Context) {
            val intent = Intent(context, ClipboardForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ClipboardForegroundService::class.java))
        }
    }

    private var clipboardManager: ClipboardManager? = null
    private var lastClipText: String? = null
    private var logcatMonitor: LogcatClipboardMonitor? = null

    // Layer 1: standard listener — fires when OS permits background clipboard read
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        try {
            val cm = clipboardManager ?: return@OnPrimaryClipChangedListener
            val clip = cm.primaryClip ?: return@OnPrimaryClipChangedListener  // may be null on Android 10+ in bg
            if (clip.itemCount == 0) return@OnPrimaryClipChangedListener

            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()
            if (text.isNullOrEmpty()) return@OnPrimaryClipChangedListener
            if (text == lastClipText) return@OnPrimaryClipChangedListener

            lastClipText = text
            Log.i(TAG, "Layer1: Clipboard captured in foreground service: ${text.length} chars")
            (applicationContext as? dev.localbridge.android.LocalBridgeApplication)?.webSocketClient?.sendClipboard(text)
            onClipboardChanged?.invoke(text)
        } catch (e: SecurityException) {
            // Android 10+ denies background reads — Layer 2 (logcat monitor) handles this
            Log.d(TAG, "Layer1: Clipboard read denied in background (expected on Android 10+) — logcat monitor will trigger trampoline")
        } catch (e: Exception) {
            Log.w(TAG, "Layer1: Error reading clipboard: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // Layer 1 — standard listener
        clipboardManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager?.addPrimaryClipChangedListener(clipListener)

        // Layer 2 — logcat monitor (requires READ_LOGS permission via ADB)
        logcatMonitor = LogcatClipboardMonitor(applicationContext)
        logcatMonitor?.start()

        Log.i(TAG, "Foreground service started; Layer1 listener + Layer2 logcat monitor running")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-ensure foreground state if the system killed and restarted us
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        clipboardManager = null
        logcatMonitor?.stop()
        logcatMonitor = null
        Log.i(TAG, "Foreground service destroyed; clipboard listener and logcat monitor removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Notification helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LocalBridge Clipboard Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps clipboard sync active in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val tapPending = PendingIntent.getActivity(this, 0, tapIntent, pendingFlags)

        // "⚡ Sync Now" action in the notification — manual trigger via trampoline
        val syncIntent = Intent(this, QuickSyncTrampolineActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("trigger_source", "notification_action")
        }
        val syncPending = PendingIntent.getActivity(this, 1, syncIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val syncAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_upload),
                "⚡ Sync Now",
                syncPending
            ).build()
            builder.addAction(syncAction)
        } else {
            @Suppress("DEPRECATION")
            builder.addAction(android.R.drawable.ic_menu_upload, "⚡ Sync Now", syncPending)
        }

        return builder
            .setContentTitle("LocalBridge Active — Auto-sync ON")
            .setContentText("Copies are sent to Mac automatically")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(tapPending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}

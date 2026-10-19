package dev.localbridge.android.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import dev.localbridge.android.LocalBridgeApplication

/**
 * Handles text highlighted in any app (ChatGPT, Chrome, WhatsApp, Notes, etc.)
 * via the system context menu ("Send to Mac").
 *
 * Android directly passes the highlighted text in [Intent.EXTRA_PROCESS_TEXT].
 * This does not require any permissions and does not require opening the main app UI.
 */
class ProcessTextSyncActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        try {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            if (!text.isNullOrEmpty()) {
                val app = applicationContext as? LocalBridgeApplication
                val client = app?.webSocketClient
                if (client?.isPaired() == true) {
                    client.sendClipboard(text)
                    Toast.makeText(this, "⚡ Sent to Mac!", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Sent ${text.length} chars directly from text selection to Mac")
                } else {
                    Toast.makeText(this, "LocalBridge: Mac is not connected", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "No text selected", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send selected text: ${e.message}")
        } finally {
            finish()
            overridePendingTransition(0, 0)
        }
    }

    companion object {
        private const val TAG = "LB.ProcessText"
    }
}

package dev.localbridge.android.network

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import dev.localbridge.android.ui.QuickSyncTrampolineActivity

/**
 * Quick Settings Tile allowing the user to sync their current clipboard to Mac
 * with 1 tap directly from the Android status bar / Quick Settings shade.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickSyncTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickSyncTrampolineActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

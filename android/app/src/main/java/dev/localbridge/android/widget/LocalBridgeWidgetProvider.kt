package dev.localbridge.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import dev.localbridge.android.R
import dev.localbridge.android.ui.MainActivity
import dev.localbridge.android.ui.QuickSyncTrampolineActivity

/**
 * Home Screen Widget featuring two horizontal buttons:
 *  - Left (Clipboard Icon): Instantly syncs current clipboard to paired Mac.
 *  - Right (Plus Icon): Directly opens file/media picker to send files to paired Mac.
 */
class LocalBridgeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            // 1. Clipboard icon -> QuickSyncTrampolineActivity (reads clipboard with focus, pushes to Mac)
            val clipIntent = Intent(context, QuickSyncTrampolineActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            val clipPending = PendingIntent.getActivity(context, 101, clipIntent, flags)

            // 2. Plus icon -> MainActivity with EXTRA_OPEN_FILE_PICKER
            val fileIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_OPEN_FILE_PICKER, true)
            }
            val filePending = PendingIntent.getActivity(context, 102, fileIntent, flags)

            val views = RemoteViews(context.packageName, R.layout.widget_local_bridge).apply {
                setOnClickPendingIntent(R.id.widget_btn_clipboard, clipPending)
                setOnClickPendingIntent(R.id.widget_btn_file, filePending)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

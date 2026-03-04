package com.tgstorage.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.tgstorage.MainActivity
import com.tgstorage.R

/**
 * Home screen widget for quick file upload.
 * Tapping opens the app with an intent extra that triggers the file picker.
 */
class QuickUploadWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_QUICK_UPLOAD
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val views = RemoteViews(context.packageName, R.layout.widget_quick_upload).apply {
                setOnClickPendingIntent(R.id.widget_icon, pendingIntent)
                setOnClickPendingIntent(R.id.widget_text, pendingIntent)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    companion object {
        const val ACTION_QUICK_UPLOAD = "com.tgstorage.ACTION_QUICK_UPLOAD"
    }
}

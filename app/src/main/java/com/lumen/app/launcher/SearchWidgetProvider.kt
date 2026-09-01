package com.lumen.app.launcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.lumen.app.MainActivity
import com.lumen.app.R

/**
 * The one-cell "Search Lumen" pill. Static content — no configuration, no
 * periodic updates; the tap fires the same open-search intent as the static
 * shortcut. SINGLE_TOP because the whole app is one activity: a running
 * instance takes the intent via onNewIntent instead of stacking a second copy.
 */
class SearchWidgetProvider : AppWidgetProvider() {

    // @spec SEARCH-ENTRY-003
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val intent = Intent(SearchLaunch.ACTION_OPEN_SEARCH)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        appWidgetIds.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_search_pill)
            views.setOnClickPendingIntent(R.id.widget_search_pill_root, pendingIntent)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

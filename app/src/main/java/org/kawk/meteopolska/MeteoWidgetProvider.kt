package org.kawk.meteopolska

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeteoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            ImageStore.deleteWidget(context, id)
        }
    }

    override fun onEnabled(context: Context) {
        Scheduler.ensurePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        Scheduler.cancelAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Constants.ACTION_REFRESH -> Scheduler.refreshNow(context)
            Constants.ACTION_CITY_CHANGED -> {
                val id = intent.getIntExtra(Constants.EXTRA_APPWIDGET_ID, -1)
                if (id >= 0) {
                    val mgr = AppWidgetManager.getInstance(context)
                    updateWidget(context, mgr, id)
                    Scheduler.refreshNow(context)
                }
            }
        }
    }

    companion object {

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, MeteoWidgetProvider::class.java)
            )
            for (id in ids) updateWidget(context, manager, id)
        }

        fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_meteo)

            val city = ImageStore.getCity(context, appWidgetId) ?: Cities.DEFAULT
            views.setTextViewText(R.id.meteo_city, city.name)

            val bitmap = ImageStore.loadOrSample(context, appWidgetId)
            if (bitmap != null) {
                views.setImageViewBitmap(R.id.meteo_image, bitmap)
                views.setViewVisibility(R.id.meteo_placeholder, android.view.View.GONE)
            } else {
                views.setViewVisibility(R.id.meteo_placeholder, android.view.View.VISIBLE)
            }

            val updatedAt = ImageStore.updatedAt(context, appWidgetId)
            val statusText = when {
                ImageStore.isRefreshing(context, appWidgetId) ->
                    context.getString(R.string.refreshing)
                ImageStore.hasImage(context, appWidgetId) && updatedAt > 0L -> {
                    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                    context.getString(R.string.updated_at, fmt.format(Date(updatedAt)))
                }
                else -> context.getString(R.string.sample_image)
            }
            views.setTextViewText(R.id.meteo_status, statusText)

            val refreshIntent = Intent(context, MeteoWidgetProvider::class.java).apply {
                action = Constants.ACTION_REFRESH
            }
            val refreshPending = PendingIntent.getBroadcast(
                context, appWidgetId, refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.meteo_refresh, refreshPending)
            views.setOnClickPendingIntent(R.id.meteo_image, refreshPending)

            val configIntent = Intent(context, CityConfigActivity::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val configPending = PendingIntent.getActivity(
                context, appWidgetId, configIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.meteo_city, configPending)

            manager.updateAppWidget(appWidgetId, views)
        }
    }
}

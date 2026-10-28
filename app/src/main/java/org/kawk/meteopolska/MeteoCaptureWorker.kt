package org.kawk.meteopolska

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class MeteoCaptureWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val manager = AppWidgetManager.getInstance(ctx)
        val ids = manager.getAppWidgetIds(
            ComponentName(ctx, MeteoWidgetProvider::class.java)
        )
        if (ids.isEmpty()) return Result.success()

        if (Constants.DEMO_STATIC) {
            for (id in ids) ImageStore.setRefreshing(ctx, id, true)
            MeteoWidgetProvider.refreshAll(ctx)
            delay(800)
            for (id in ids) ImageStore.setRefreshing(ctx, id, false)
            MeteoWidgetProvider.refreshAll(ctx)
            return Result.success()
        }

        for (id in ids) ImageStore.setRefreshing(ctx, id, true)
        MeteoWidgetProvider.refreshAll(ctx)

        return try {
            withContext(Dispatchers.IO) {
                val widgetCities = ids.toList().map { id ->
                    id to (ImageStore.getCity(ctx, id) ?: Cities.DEFAULT)
                }
                val byCity = widgetCities.groupBy({ it.second }, { it.first })
                for ((city, widgetIds) in byCity) {
                    try {
                        Log.d("MeteoCaptureWorker", "Fetching ${city.name} lat=${city.lat} lon=${city.lon}")
                        val raw = MeteogramFetcher.downloadRaw(city)
                        val forecast = MeteogramFetcher.parse(raw)
                        val days = MeteogramRenderer.dayCount(forecast)
                        for (wid in widgetIds) {
                            val day = ImageStore.getDay(ctx, wid).coerceIn(0, days - 1)
                            ImageStore.setDay(ctx, wid, day)
                            ImageStore.saveForecast(ctx, wid, raw)
                            val bitmap = MeteogramRenderer.render(city, forecast, day)
                            ImageStore.save(ctx, wid, bitmap)
                            Log.d("MeteoCaptureWorker", "Saved ${bitmap.width}x${bitmap.height} day $day for widget $wid")
                        }
                    } catch (t: Throwable) {
                        Log.e("MeteoCaptureWorker", "Fetch failed for ${city.name}", t)
                    }
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.success()
        } finally {
            for (id in ids) ImageStore.setRefreshing(ctx, id, false)
            MeteoWidgetProvider.refreshAll(ctx)
        }
    }
}

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
                        Log.d("MeteoCaptureWorker", "Fetching ${city.name} col=${city.col} row=${city.row}")
                        val bitmap = MeteogramFetcher.fetch(city.col, city.row)
                        Log.d("MeteoCaptureWorker", "Fetched bitmap: ${bitmap.width}x${bitmap.height}")
                        for (wid in widgetIds) {
                            ImageStore.save(ctx, wid, bitmap)
                            Log.d("MeteoCaptureWorker", "Saved for widget $wid")
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

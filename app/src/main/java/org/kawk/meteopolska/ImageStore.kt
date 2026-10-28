package org.kawk.meteopolska

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

object ImageStore {

    private fun file(context: Context, appWidgetId: Int) =
        File(context.filesDir, Constants.imageFile(appWidgetId))

    private fun prefs(context: Context) =
        context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, appWidgetId: Int, bitmap: Bitmap) {
        FileOutputStream(file(context, appWidgetId)).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        prefs(context).edit()
            .putLong(Constants.keyUpdatedAt(appWidgetId), System.currentTimeMillis())
            .apply()
    }

    fun load(context: Context, appWidgetId: Int): Bitmap? {
        val f = file(context, appWidgetId)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)
    }

    fun loadOrSample(context: Context, appWidgetId: Int): Bitmap? {
        load(context, appWidgetId)?.let { return it }
        return runCatching {
            context.resources.openRawResource(R.raw.sample_meteogram).use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()
    }

    fun hasImage(context: Context, appWidgetId: Int): Boolean =
        file(context, appWidgetId).exists()

    fun updatedAt(context: Context, appWidgetId: Int): Long =
        prefs(context).getLong(Constants.keyUpdatedAt(appWidgetId), 0L)

    fun setRefreshing(context: Context, appWidgetId: Int, value: Boolean) {
        prefs(context).edit()
            .putBoolean(Constants.keyRefreshing(appWidgetId), value)
            .apply()
    }

    fun isRefreshing(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean(Constants.keyRefreshing(appWidgetId), false)

    fun saveCity(context: Context, appWidgetId: Int, city: City) {
        prefs(context).edit()
            .putString(Constants.keyCity(appWidgetId), city.name)
            .apply()
    }

    fun getCity(context: Context, appWidgetId: Int): City? {
        val name = prefs(context).getString(Constants.keyCity(appWidgetId), null)
        return name?.let { Cities.byName(it) }
    }

    fun deleteWidget(context: Context, appWidgetId: Int) {
        file(context, appWidgetId).delete()
        prefs(context).edit()
            .remove(Constants.keyUpdatedAt(appWidgetId))
            .remove(Constants.keyRefreshing(appWidgetId))
            .remove(Constants.keyCity(appWidgetId))
            .apply()
    }
}

package org.kawk.meteopolska

object Constants {
    const val DEMO_STATIC = false

    const val MGRAM_BASE_URL =
        "https://old.meteo.pl/um/metco/mgram_pict.php?ntype=0u&row=%d&col=%d&lang=pl"
    const val LEGEND_URL = "https://old.meteo.pl/um/metco/leg_um_pl_cbase_256.png"

    const val REFRESH_INTERVAL_MIN = 30L

    const val PERIODIC_WORK = "meteo_periodic_capture"
    const val ONESHOT_WORK = "meteo_oneshot_capture"

    const val ACTION_REFRESH = "org.kawk.meteopolska.ACTION_REFRESH"
    const val ACTION_CITY_CHANGED = "org.kawk.meteopolska.ACTION_CITY_CHANGED"

    const val PREFS = "meteo_prefs"
    const val EXTRA_APPWIDGET_ID = "appwidget_id"

    fun imageFile(appWidgetId: Int) = "meteogram_$appWidgetId.png"
    fun keyUpdatedAt(appWidgetId: Int) = "updated_at_$appWidgetId"
    fun keyRefreshing(appWidgetId: Int) = "refreshing_$appWidgetId"
    fun keyCity(appWidgetId: Int) = "city_$appWidgetId"
}

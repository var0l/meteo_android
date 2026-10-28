package org.kawk.meteopolska

import android.graphics.Bitmap
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.max

/**
 * Downloads point forecast data from the ICM meteogram API (the backend of
 * the Meteo ICM app); [MeteogramRenderer] draws it.
 */
object MeteogramFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MISSING_THRESHOLD = -1e6

    class Series(val start: Long, val interval: Long, val values: DoubleArray)

    class Forecast(
        val runStart: Long,
        val series: Map<String, Series>,
        val row: Int? = null,
        val col: Int? = null,
    ) {
        operator fun get(key: String): Series? = series[key]
    }

    fun fetch(city: City, day: Int = 0): Bitmap = MeteogramRenderer.render(city, download(city), day)

    fun download(city: City): Forecast = parse(downloadRaw(city))

    /** Raw API response, cached per widget so day paging works offline. */
    fun downloadRaw(city: City): String {
        val model = Constants.MGRAM_MODEL
        val available = JSONObject(
            http("GET", "${Constants.MGRAM_API_BASE}/meteorograms/available", null)
        )
        val runs = available.getJSONArray(model)
        var latest = 0L
        for (i in 0 until runs.length()) latest = max(latest, runs.getLong(i))
        check(latest > 0) { "No $model runs available" }

        val body = JSONObject()
            .put("point", JSONObject().put("lat", city.lat.toString()).put("lon", city.lon.toString()))
            .put("date", latest)
            .toString()
        return http("POST", "${Constants.MGRAM_API_BASE}/meteorograms/$model", body)
    }

    fun parse(raw: String): Forecast {
        val data = JSONObject(NAN_REGEX.replace(raw, "null")).getJSONObject("data")

        val out = HashMap<String, Series>()
        var runStart = 0L
        var row: Int? = null
        var col: Int? = null
        for (key in data.keys()) {
            val obj = data.optJSONObject(key) ?: continue
            val arr = obj.optJSONArray("data") ?: continue
            val values = DoubleArray(arr.length()) { i ->
                val v = arr.optDouble(i, Double.NaN)
                if (v < MISSING_THRESHOLD) Double.NaN else v
            }
            out[key] = Series(
                obj.optString("first_timestamp").toLongOrNull() ?: continue,
                obj.optLong("interval", 3600L),
                values,
            )
            if (runStart == 0L) {
                runStart = runCatching { Instant.parse(obj.optString("fstart")).epochSecond }.getOrDefault(0L)
            }
            if (row == null) {
                val point = obj.optJSONObject("point")
                    ?: runCatching { JSONObject(obj.optString("point").replace('\'', '"')) }.getOrNull()
                row = point?.optInt("row", -1)?.takeIf { it >= 0 }
                col = point?.optInt("col", -1)?.takeIf { it >= 0 }
            }
        }
        check(out.isNotEmpty()) { "Empty forecast" }
        return Forecast(runStart, out, row, col)
    }

    private val NAN_REGEX = Regex("(?<![A-Za-z_\"])-?nan(?![A-Za-z_\"])", RegexOption.IGNORE_CASE)

    private fun http(method: String, urlStr: String, body: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = method
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MeteoPolskaWidget")
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code for $urlStr" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

package org.kawk.meteopolska

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Downloads point forecast data from the ICM meteogram API (the backend of
 * the Meteo ICM app) and renders it as a meteogram bitmap.
 */
object MeteogramFetcher {

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val MISSING_THRESHOLD = -1e6
    private val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    class Series(val start: Long, val interval: Long, val values: DoubleArray)

    class Forecast(val runStart: Long, val series: Map<String, Series>) {
        operator fun get(key: String): Series? = series[key]
    }

    fun fetch(city: City): Bitmap = render(city, download(city))

    fun download(city: City): Forecast {
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
        val raw = http("POST", "${Constants.MGRAM_API_BASE}/meteorograms/$model", body)
        val data = JSONObject(NAN_REGEX.replace(raw, "null")).getJSONObject("data")

        val out = HashMap<String, Series>()
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
        }
        check(out.isNotEmpty()) { "Empty forecast for ${city.name}" }
        return Forecast(latest, out)
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

    // ---------------------------------------------------------------- render

    private const val W = 1080
    private const val LEFT = 96f
    private const val RIGHT = 84f
    private const val HEADER_H = 140f
    private const val PANEL_H = 230f
    private const val CLOUD_H = 150f
    private const val PANEL_GAP = 46f
    private const val FOOTER_H = 50f

    private val C_TEMP = Color.rgb(220, 30, 30)
    private val C_TEMP_BAND = Color.argb(60, 220, 30, 30)
    private val C_DEW = Color.rgb(40, 70, 210)
    private val C_PRECIP = Color.rgb(30, 160, 40)
    private val C_HUM = Color.rgb(235, 120, 20)
    private val C_PRES = Color.rgb(20, 20, 20)
    private val C_PRES_FILL = Color.rgb(150, 220, 90)
    private val C_WIND = Color.rgb(20, 80, 170)
    private val C_GUST = Color.rgb(220, 30, 30)
    private val C_GRID = Color.rgb(205, 205, 205)
    private val C_NIGHT = Color.rgb(236, 236, 242)
    private val C_TEXT = Color.rgb(34, 34, 34)

    private class Plot(val canvas: Canvas, val n: Int, val start: Long, val interval: Long) {
        val plotW = W - LEFT - RIGHT
        fun x(i: Double): Float = LEFT + (i / (n - 1).coerceAtLeast(1) * plotW).toFloat()
        fun x(i: Int): Float = x(i.toDouble())
        fun time(i: Int): Instant = Instant.ofEpochSecond(start + i * interval)
    }

    fun render(city: City, f: Forecast): Bitmap {
        val temp = f["airtmp_point"] ?: error("Missing temperature")
        val n = temp.values.size
        val panels = 4
        val height = (HEADER_H + panels * (PANEL_H + PANEL_GAP) + CLOUD_H + PANEL_GAP + FOOTER_H).toInt()
        val bmp = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val p = Plot(canvas, n, temp.start, temp.interval)

        drawHeader(p, city, f)
        var top = HEADER_H
        drawTemperature(p, f, top); top += PANEL_H + PANEL_GAP
        drawPrecip(p, f, top); top += PANEL_H + PANEL_GAP
        drawPressure(p, f, top); top += PANEL_H + PANEL_GAP
        drawWind(p, f, top); top += PANEL_H + PANEL_GAP
        drawClouds(p, f, top, CLOUD_H)

        val foot = text(22f, Color.GRAY)
        canvas.drawText("Dane: ICM UW, model UM 4 km · czas lokalny", LEFT, height - 18f, foot)
        return bmp
    }

    private fun text(size: Float, color: Int = C_TEXT, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            textAlign = align
            isFakeBoldText = bold
        }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        strokeWidth = width
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun drawHeader(p: Plot, city: City, f: Forecast) {
        val c = p.canvas
        c.drawText(city.name, LEFT, 52f, text(42f, Color.rgb(26, 115, 232), bold = true))
        val run = DateTimeFormatter.ofPattern("dd.MM HH:mm 'UTC'", Locale.ROOT)
            .withZone(ZoneId.of("UTC")).format(Instant.ofEpochSecond(f.runStart))
        c.drawText("UM 4 km · start $run", W - RIGHT, 52f, text(24f, Color.GRAY, align = Paint.Align.RIGHT))

        val dayFmt = DateTimeFormatter.ofPattern("EEE dd.MM", Locale("pl")).withZone(ZONE)
        val dayPaint = text(24f, bold = true, align = Paint.Align.CENTER)
        var segStart = 0
        for (i in 1..p.n) {
            val newDay = i == p.n ||
                p.time(i).atZone(ZONE).toLocalDate() != p.time(i - 1).atZone(ZONE).toLocalDate()
            if (newDay) {
                val mid = (p.x(segStart) + p.x(i - 1)) / 2
                if (p.x(i - 1) - p.x(segStart) > 90) c.drawText(dayFmt.format(p.time(segStart)), mid, 100f, dayPaint)
                segStart = i
            }
        }
    }

    private fun localHour(p: Plot, i: Int) = p.time(i).atZone(ZONE).hour

    private fun drawFrame(p: Plot, top: Float, h: Float, title: String, yMin: Double, yMax: Double, step: Double) {
        val c = p.canvas
        val bottom = top + h
        // night shading
        var i = 0
        while (i < p.n) {
            if (localHour(p, i) >= 20 || localHour(p, i) < 6) {
                var j = i
                while (j + 1 < p.n && (localHour(p, j + 1) >= 20 || localHour(p, j + 1) < 6)) j++
                c.drawRect(p.x(max(0.0, i - 0.5)), top, p.x(min(p.n - 1.0, j + 0.5)), bottom, fill(C_NIGHT))
                i = j + 1
            } else i++
        }
        val grid = stroke(C_GRID, 1.5f)
        val label = text(20f, Color.DKGRAY, align = Paint.Align.RIGHT)
        var v = ceil(yMin / step) * step
        while (v <= yMax + 1e-9) {
            val y = yOf(v, yMin, yMax, top, h)
            c.drawLine(LEFT, y, W - RIGHT, y, grid)
            c.drawText(fmt(v), LEFT - 8f, y + 7f, label)
            v += step
        }
        val hourLbl = text(18f, Color.GRAY, align = Paint.Align.CENTER)
        val dayLine = stroke(Color.rgb(150, 150, 150), 2f)
        for (k in 0 until p.n) {
            val hr = localHour(p, k)
            if (hr % 6 == 0) {
                c.drawLine(p.x(k), top, p.x(k), bottom, if (hr == 0) dayLine else grid)
                c.drawText(hr.toString().padStart(2, '0'), p.x(k), bottom + 20f, hourLbl)
            }
        }
        c.drawRect(LEFT, top, W - RIGHT, bottom, stroke(Color.GRAY, 2f))
        c.drawText(title, LEFT, top - 10f, text(22f, bold = true))
    }

    private fun fmt(v: Double): String =
        if (v == floor(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

    private fun yOf(v: Double, yMin: Double, yMax: Double, top: Float, h: Float): Float =
        (top + h - (v - yMin) / (yMax - yMin) * h).toFloat()

    private fun range(values: List<DoubleArray?>, step: Double, minSpan: Double): Pair<Double, Double> {
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (arr in values) arr?.forEach { if (!it.isNaN()) { lo = min(lo, it); hi = max(hi, it) } }
        if (lo.isInfinite()) return 0.0 to minSpan
        var a = floor(lo / step) * step
        var b = ceil(hi / step) * step
        if (b - a < minSpan) { val mid = (a + b) / 2; a = floor((mid - minSpan / 2) / step) * step; b = a + minSpan }
        if (b == a) b = a + step
        return a to b
    }

    private fun niceStep(span: Double, ticks: Int = 5): Double {
        val raw = span / ticks
        val mag = Math.pow(10.0, floor(Math.log10(raw)))
        return listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    }

    private fun linePath(p: Plot, arr: DoubleArray, yMin: Double, yMax: Double, top: Float, h: Float): Path {
        val path = Path()
        var started = false
        for (i in arr.indices) {
            val v = arr[i]
            if (v.isNaN()) { started = false; continue }
            val x = p.x(i); val y = yOf(v.coerceIn(yMin, yMax), yMin, yMax, top, h)
            if (!started) path.moveTo(x, y) else path.lineTo(x, y)
            started = true
        }
        return path
    }

    private fun bandPath(p: Plot, lo: DoubleArray, hi: DoubleArray, yMin: Double, yMax: Double, top: Float, h: Float): Path {
        val path = Path()
        val n = min(lo.size, hi.size)
        for (i in 0 until n) {
            val y = yOf(hi[i].takeUnless { it.isNaN() } ?: yMin, yMin, yMax, top, h)
            if (i == 0) path.moveTo(p.x(i), y) else path.lineTo(p.x(i), y)
        }
        for (i in n - 1 downTo 0) path.lineTo(p.x(i), yOf(lo[i].takeUnless { it.isNaN() } ?: yMin, yMin, yMax, top, h))
        path.close()
        return path
    }

    private fun legend(p: Plot, top: Float, vararg items: Pair<String, Int>) {
        var x = W - RIGHT
        val paint = text(20f, align = Paint.Align.RIGHT)
        for ((label, color) in items.reversed()) {
            paint.color = color
            p.canvas.drawText(label, x, top - 10f, paint)
            x -= paint.measureText(label) + 24f
        }
    }

    private fun drawTemperature(p: Plot, f: Forecast, top: Float) {
        val t = f["airtmp_point"]!!.values
        val tMin = f["airtmp_min"]?.values
        val tMax = f["airtmp_max"]?.values
        val dew = f["dwptmp_point"]?.values
        val (a0, b0) = range(listOf(t, tMin, tMax, dew), 1.0, 6.0)
        val step = niceStep(b0 - a0)
        val a = floor(a0 / step) * step; val b = ceil(b0 / step) * step
        drawFrame(p, top, PANEL_H, "Temperatura (°C)", a, b, step)
        if (tMin != null && tMax != null) p.canvas.drawPath(bandPath(p, tMin, tMax, a, b, top, PANEL_H), fill(C_TEMP_BAND))
        if (dew != null) p.canvas.drawPath(
            linePath(p, dew, a, b, top, PANEL_H),
            stroke(C_DEW, 3f).apply { pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f) },
        )
        if (a < 0 && b > 0) {
            val y0 = yOf(0.0, a, b, top, PANEL_H)
            p.canvas.drawLine(LEFT, y0, W - RIGHT, y0, stroke(Color.rgb(90, 90, 200), 2f))
        }
        p.canvas.drawPath(linePath(p, t, a, b, top, PANEL_H), stroke(C_TEMP, 4.5f))
        legend(p, top, "temperatura" to C_TEMP, "punkt rosy" to C_DEW)
    }

    private fun drawPrecip(p: Plot, f: Forecast, top: Float) {
        val pr = f["pcpttl_aver"]?.values ?: DoubleArray(p.n)
        val prMax = f["pcpttl_max"]?.values
        val hum = f["realhum_aver"]?.values
        var peak = 0.0
        pr.forEach { if (!it.isNaN()) peak = max(peak, it) }
        val b = when {
            peak <= 1.0 -> 1.0
            peak <= 2.0 -> 2.0
            peak <= 5.0 -> 5.0
            else -> ceil(peak / 5) * 5
        }
        drawFrame(p, top, PANEL_H, "Opad (mm/h)", 0.0, b, b / 5)
        val barW = p.plotW / (p.n - 1) * 0.7f
        val barFill = fill(C_PRECIP)
        val maxFill = fill(Color.argb(70, 30, 160, 40))
        for (i in 0 until p.n) {
            val x = p.x(i)
            prMax?.getOrNull(i)?.takeIf { !it.isNaN() && it > 0.01 }?.let {
                p.canvas.drawRect(x - barW / 2, yOf(min(it, b), 0.0, b, top, PANEL_H), x + barW / 2, top + PANEL_H, maxFill)
            }
            val v = pr[i]
            if (!v.isNaN() && v > 0.01) {
                p.canvas.drawRect(x - barW / 2, yOf(min(v, b), 0.0, b, top, PANEL_H), x + barW / 2, top + PANEL_H, barFill)
            }
        }
        if (hum != null) {
            p.canvas.drawPath(linePath(p, hum, 0.0, 100.0, top, PANEL_H), stroke(C_HUM, 3f))
            val r = text(20f, C_HUM)
            for (v in listOf(0, 25, 50, 75, 100)) {
                p.canvas.drawText("$v", W - RIGHT + 8f, yOf(v.toDouble(), 0.0, 100.0, top, PANEL_H) + 7f, r)
            }
        }
        legend(p, top, "opad" to C_PRECIP, "wilgotność %" to C_HUM)
    }

    private fun drawPressure(p: Plot, f: Forecast, top: Float) {
        val pres = (f["slpres_point"] ?: return).values.map { it / 100.0 }.toDoubleArray()
        val (a0, b0) = range(listOf(pres), 1.0, 10.0)
        val step = niceStep(b0 - a0)
        val a = floor(a0 / step) * step; val b = ceil(b0 / step) * step
        drawFrame(p, top, PANEL_H, "Ciśnienie (hPa, poziom morza)", a, b, step)
        p.canvas.drawPath(bandPath(p, DoubleArray(pres.size) { a }, pres, a, b, top, PANEL_H), fill(Color.argb(140, Color.red(C_PRES_FILL), Color.green(C_PRES_FILL), Color.blue(C_PRES_FILL))))
        p.canvas.drawPath(linePath(p, pres, a, b, top, PANEL_H), stroke(C_PRES, 3.5f))
    }

    private fun drawWind(p: Plot, f: Forecast, top: Float) {
        val sp = f["wind10_sd_true_prev_point"]?.values ?: return
        val gust = f["wind_gust_max"]?.values
        val dir = f["wind10_dr_deg_true_prev_point"]?.values
        val (_, b0) = range(listOf(sp, gust), 5.0, 10.0)
        val b = max(10.0, b0)
        val step = niceStep(b)
        drawFrame(p, top, PANEL_H, "Wiatr 10 m (m/s)", 0.0, b, step)
        p.canvas.drawPath(bandPath(p, DoubleArray(sp.size), sp, 0.0, b, top, PANEL_H), fill(Color.argb(110, 20, 80, 170)))
        p.canvas.drawPath(linePath(p, sp, 0.0, b, top, PANEL_H), stroke(C_WIND, 3.5f))
        if (gust != null) p.canvas.drawPath(linePath(p, gust, 0.0, b, top, PANEL_H), stroke(C_GUST, 3f))
        if (dir != null) {
            val arrow = stroke(Color.rgb(30, 30, 30), 2.5f)
            val y = top + 22f
            for (i in 0 until p.n step 3) {
                val d = dir[i]
                if (d.isNaN()) continue
                // Meteorological direction is where wind blows FROM; arrow points downwind.
                val rad = Math.toRadians(d + 180.0)
                val dx = (sin(rad) * 14).toFloat(); val dy = (-cos(rad) * 14).toFloat()
                val cx = p.x(i)
                p.canvas.drawLine(cx - dx, y - dy, cx + dx, y + dy, arrow)
                val hx = cx + dx; val hy = y + dy
                val back = rad + Math.PI
                for (s in listOf(-0.5, 0.5)) {
                    p.canvas.drawLine(hx, hy, hx + (sin(back + s) * 8).toFloat(), hy - (cos(back + s) * 8).toFloat(), arrow)
                }
            }
        }
        legend(p, top, "prędkość" to C_WIND, "porywy" to C_GUST)
    }

    private fun drawClouds(p: Plot, f: Forecast, top: Float, h: Float) {
        val layers = listOf(
            "wysokie" to f["cldhigh_aver"]?.values,
            "średnie" to f["cldmed_aver"]?.values,
            "niskie" to f["cldlow_aver"]?.values,
        )
        val c = p.canvas
        c.drawText("Zachmurzenie", LEFT, top - 10f, text(22f, bold = true))
        val rowH = h / 4
        val label = text(18f, Color.DKGRAY, align = Paint.Align.RIGHT)
        val cell = p.plotW / (p.n - 1)
        layers.forEachIndexed { r, (name, arr) ->
            val y0 = top + r * rowH
            c.drawText(name, LEFT - 8f, y0 + rowH / 2 + 6f, label)
            if (arr != null) for (i in 0 until p.n) {
                val v = arr[i].takeUnless { it.isNaN() } ?: 0.0
                val g = (255 - v.coerceIn(0.0, 1.0) * 175).toInt()
                c.drawRect(p.x(i) - cell / 2, y0, p.x(i) + cell / 2, y0 + rowH - 2, fill(Color.rgb(g, g, min(255, g + 15))))
            }
        }
        val totTop = top + 3 * rowH
        c.drawText("razem", LEFT - 8f, totTop + rowH / 2 + 6f, label)
        f["cldtot_aver"]?.values?.let { tot ->
            for (i in 0 until p.n) {
                val v = (tot[i].takeUnless { it.isNaN() } ?: 0.0).coerceIn(0.0, 1.0)
                val bh = (rowH - 2) * v.toFloat()
                c.drawRect(p.x(i) - cell / 2, totTop + rowH - bh, p.x(i) + cell / 2, totTop + rowH, fill(Color.rgb(110, 110, 125)))
            }
        }
        c.drawRect(LEFT - cell / 2, top, W - RIGHT + cell / 2, top + h, stroke(Color.GRAY, 2f))
        val hourLbl = text(18f, Color.GRAY, align = Paint.Align.CENTER)
        for (k in 0 until p.n) {
            val hr = localHour(p, k)
            if (hr % 6 == 0) c.drawText(hr.toString().padStart(2, '0'), p.x(k), top + h + 20f, hourLbl)
        }
    }
}

package org.kawk.meteopolska

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin

/**
 * Draws the widget meteogram in the style of the classic ICM UM meteogram:
 * header, 60 h overview, day table, 24 h detail of the selected day and an
 * annotated legend. Drawn at the old image's pixel size; the widget's
 * ImageView scales it up like the original picture.
 */
object MeteogramRenderer {

    private const val W = 540f
    private const val L = 46f
    private const val R = 50f
    private const val PW = W - L - R
    private const val HOUR = 3600L

    private val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    private val PL = Locale("pl", "PL")

    private val BG = Color.rgb(255, 251, 240)
    private val NIGHT = Color.rgb(224, 224, 224)
    private val BLUE_T = Color.rgb(32, 32, 144)
    private val HEAD = Color.rgb(20, 20, 120)
    private val HUM = Color.rgb(224, 96, 42)
    private val RED = Color.rgb(255, 0, 0)
    private val FEEL = Color.rgb(0, 0, 255)
    private val AIR_BAND = Color.rgb(255, 200, 208)
    private val AIR_BAND_TXT = Color.rgb(255, 154, 170)
    private val FEEL_BAND = Color.rgb(168, 216, 248)
    private val FEEL_BAND_TXT = Color.rgb(122, 184, 232)
    private val GROUND = Color.rgb(165, 42, 42)
    private val DEW = Color.rgb(112, 96, 208)
    private val RAIN_MAX = Color.rgb(0, 224, 0)
    private val RAIN_AVG = Color.rgb(0, 160, 0)
    private val SNOW_MAX = Color.rgb(64, 96, 255)
    private val SNOW_AVG = Color.rgb(32, 48, 192)
    private val SEL = Color.rgb(0, 0, 192)
    private val BASE = Color.rgb(0, 128, 0)
    private val GREY_TXT = Color.rgb(96, 96, 96)

    private const val HEADER_H = 32f
    private const val OVERVIEW_H = 150f
    private const val TABLE_H = 36f
    private const val DETAIL_TH = 170f
    private const val DETAIL_PH = 120f
    private const val DETAIL_H = 30f + DETAIL_TH + 8f + DETAIL_PH + 18f
    private const val LEGEND_H = 196f
    private const val FOOTER_H = 18f

    /** Hourly forecast values; NaN where missing. */
    private class Src(val t0: Long, val n: Int, val synthetic: Boolean, private val get: (String, Int) -> Double) {
        operator fun invoke(key: String, i: Int): Double = if (i in 0 until n) get(key, i) else Double.NaN
        fun ts(i: Int) = t0 + i * HOUR
        fun utcHour(i: Int) = Math.floorMod(ts(i), 86400L) / HOUR
        fun localHour(i: Int) = Instant.ofEpochSecond(ts(i)).atZone(ZONE).hour
        val days: List<Int> = (0 until n).filter { it == 0 || localHour(it) == 0 }.filter { n - it >= 3 }
    }

    private class Area(val x0: Float, val y0: Float, val w: Float, val h: Float, val from: Int, val to: Int) {
        val bw = w / (to - from)
        fun x(i: Int) = x0 + (i - from + 0.5f) * bw
        fun xt(src: Src, t: Long) = x0 + ((t - src.t0).toFloat() / HOUR - from + 0.5f) * bw
    }

    private fun src(f: MeteogramFetcher.Forecast): Src {
        val temp = f["airtmp_point"] ?: error("Missing temperature")
        val t0 = temp.start
        return Src(t0, temp.values.size, false) { key, i ->
            val s = f[key] ?: return@Src Double.NaN
            val idx = ((t0 + i * HOUR - s.start) / s.interval).toInt()
            if (idx in s.values.indices) s.values[idx] else Double.NaN
        }
    }

    fun dayCount(f: MeteogramFetcher.Forecast): Int = src(f).days.size

    fun render(city: City, f: MeteogramFetcher.Forecast, day: Int): Bitmap {
        val s = src(f)
        val sel = day.coerceIn(0, s.days.size - 1)
        val height = HEADER_H + OVERVIEW_H + TABLE_H + DETAIL_H + LEGEND_H + FOOTER_H
        val bmp = Bitmap.createBitmap(W.toInt(), ceil(height).toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        val d = Drawer(c, s, city.lat, city.lon)
        var y = 0f
        d.header(city, f, sel, y); y += HEADER_H
        d.overview(sel, y); y += OVERVIEW_H
        d.dayTable(sel, y); y += TABLE_H
        d.detail(sel, y); y += DETAIL_H
        legend(c, s, city, y); y += LEGEND_H
        d.footer(y)
        return bmp
    }

    private fun fmt(v: Double): String =
        if (abs(v - round(v)) < 1e-6) round(v).toInt().toString() else String.format(Locale.US, "%.1f", v)

    private fun tzName(t: Long) =
        if (ZONE.rules.isDaylightSavings(Instant.ofEpochSecond(t))) "CEST" else "CET"

    /** Approximate sunrise/sunset (UTC epoch seconds) for the UTC day containing [t]. */
    private fun sun(t: Long, lat: Double, lon: Double): Pair<Long, Long> {
        val n = Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).dayOfYear
        val r = PI / 180
        val dec = 23.44 * sin(2 * PI * (284 + n) / 365.0) * r
        val b = 2 * PI * (n - 81) / 364.0
        val eot = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val cosH = ((sin(-0.833 * r) - sin(lat * r) * sin(dec)) / (cos(lat * r) * cos(dec))).coerceIn(-1.0, 1.0)
        val h = acos(cosH) / r
        val noon = 12 - lon / 15 - eot / 60
        val mid = Math.floorDiv(t, 86400L) * 86400L
        return Pair(mid + ((noon - h / 15) * HOUR).toLong(), mid + ((noon + h / 15) * HOUR).toLong())
    }

    private class Drawer(val c: Canvas, val s: Src, val lat: Double, val lon: Double) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val crisp = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.BLACK }
        val dash = Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.BLACK
            pathEffect = DashPathEffect(floatArrayOf(3f, 2f), 0f)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; textSize = 12f }

        fun txt(
            str: String, x: Float, y: Float, color: Int = Color.BLACK, size: Float = 12f,
            align: Paint.Align = Paint.Align.LEFT, bold: Boolean = false,
        ): Float {
            text.color = color; text.textSize = size; text.textAlign = align; text.isFakeBoldText = bold
            c.drawText(str, x, y, text)
            return text.measureText(str)
        }

        fun rot(x: Float, y: Float, lines: List<String>, color: Int = BLUE_T, size: Float = 12f) {
            lines.forEachIndexed { k, str ->
                val xx = x + k * 13f
                c.save(); c.rotate(-90f, xx, y); txt(str, xx, y, color, size, Paint.Align.CENTER); c.restore()
            }
        }

        fun hl(x1: Float, x2: Float, y: Float) {
            val yy = floor(y) + 0.5f; c.drawLine(x1, yy, x2, yy, dash)
        }

        fun vl(x: Float, y1: Float, y2: Float, solid: Boolean = false) {
            val xx = floor(x) + 0.5f
            crisp.strokeWidth = 1f; crisp.color = Color.BLACK
            c.drawLine(xx, y1, xx, y2, if (solid) crisp else dash)
        }

        fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
            fill.color = color; c.drawRect(x, y, x + w, y + h, fill)
        }

        fun frame(a: Area) {
            crisp.strokeWidth = 2f; crisp.color = Color.BLACK
            c.drawRect(a.x0, a.y0, a.x0 + a.w, a.y0 + a.h, crisp)
        }

        fun box(x: Float, y: Float, w: Float, h: Float, color: Int, width: Float) {
            stroke.color = color; stroke.strokeWidth = width
            c.drawRect(x, y, x + w, y + h, stroke)
        }

        fun nights(a: Area, fixed: List<Pair<Int, Int>>?) {
            rect(a.x0, a.y0, a.w, a.h, Color.WHITE)
            if (fixed != null) {
                for ((p, q) in fixed) rect(a.x0 + (p - a.from) * a.bw, a.y0, (q - p) * a.bw, a.h, NIGHT)
                return
            }
            val t0 = s.ts(a.from) - 1800; val t1 = s.ts(a.to) - 1800
            var d = t0 - 86400
            while (d < t1 + 86400) {
                val set = sun(d, lat, lon).second; val rise = sun(d + 86400, lat, lon).first
                val p = max(set, t0); val q = min(rise, t1)
                if (q > p) rect(a.xt(s, p), a.y0, a.xt(s, q) - a.xt(s, p), a.h, NIGHT)
                d += 86400
            }
        }

        fun vgrid(a: Area, step: Int) {
            for (i in a.from until a.to) if (s.utcHour(i) % step == 0L) vl(a.x(i), a.y0, a.y0 + a.h)
            if (!s.synthetic) for (d in s.days) if (d > a.from && d < a.to) vl(a.x(d) - a.bw / 2, a.y0, a.y0 + a.h, true)
        }

        fun line(a: Area, key: String, color: Int, width: Float, y: (Double) -> Float) {
            val path = Path(); var pen = false
            for (i in a.from until a.to) {
                val v = s(key, i)
                if (v.isNaN()) { pen = false; continue }
                if (pen) path.lineTo(a.x(i), y(v)) else path.moveTo(a.x(i), y(v))
                pen = true
            }
            stroke.color = color; stroke.strokeWidth = width; c.drawPath(path, stroke)
        }

        fun band(a: Area, loKey: String, hiKey: String, color: Int, y: (Double) -> Float) {
            val idx = (a.from until a.to).filter { !s(loKey, it).isNaN() && !s(hiKey, it).isNaN() }
            if (idx.size < 2) return
            val path = Path()
            idx.forEachIndexed { k, i -> if (k == 0) path.moveTo(a.x(i), y(s(hiKey, i))) else path.lineTo(a.x(i), y(s(hiKey, i))) }
            for (i in idx.reversed()) path.lineTo(a.x(i), y(s(loKey, i)))
            path.close(); fill.color = color; c.drawPath(path, fill)
        }

        fun finite(keys: List<String>, a: Area) =
            keys.flatMap { k -> (a.from until a.to).map { s(k, it) } }.filter { !it.isNaN() }

        fun tempPanel(
            a: Area, noAxis: Boolean = false, lines: Int = 6, step: Int = 3, lw: Float = 2.5f,
            fixedNights: List<Pair<Int, Int>>? = null,
        ): (Double) -> Float {
            val vals = finite(listOf("airtmp_min", "airtmp_max", "wchill_min", "grdtmp_min", "grdtmp_max", "dwptmp_point", "airtmp_point"), a)
            val mn = vals.minOrNull() ?: 0.0; val mx = vals.maxOrNull() ?: 10.0
            val st = listOf(1.0, 2.0, 5.0, 10.0).firstOrNull { (mx - mn) / it <= lines } ?: 10.0
            val lo = floor(mn / st) * st
            val hi = max(ceil(mx / st) * st, lo + st)
            val y = { v: Double -> (a.y0 + a.h - (v - lo) / (hi - lo) * a.h).toFloat() }
            nights(a, fixedNights)
            band(a, "wchill_min", "wchill_max", FEEL_BAND, y)
            band(a, "airtmp_min", "airtmp_max", AIR_BAND, y)
            var t = lo
            while (t <= hi + 1e-9) {
                hl(a.x0, a.x0 + a.w, y(t))
                if (!noAxis) {
                    txt(fmt(t), a.x0 - 4, y(t) + 4, align = Paint.Align.RIGHT)
                    txt(fmt(t), a.x0 + a.w + 4, y(t) + 4)
                }
                t += st
            }
            vgrid(a, step)
            crisp.strokeWidth = 1f; crisp.color = GROUND
            for (i in a.from until a.to) {
                val g0 = s("grdtmp_min", i); val g1 = s("grdtmp_max", i)
                if (!g0.isNaN() && !g1.isNaN()) {
                    val xx = floor(a.x(i)) + 0.5f; c.drawLine(xx, y(g0), xx, y(g1), crisp)
                }
            }
            for (i in a.from until a.to) {
                val v = s("dwptmp_point", i)
                if (!v.isNaN()) rect(a.x(i) - 1.5f, y(v) - 1.5f, 3f, 3f, DEW)
            }
            line(a, "wchill_point", FEEL, lw, y)
            line(a, "airtmp_point", RED, lw, y)
            frame(a)
            return y
        }

        fun rainPanel(
            a: Area, noAxis: Boolean = false, top: Double? = null, step: Int = 3, lw: Float = 2.2f,
            fixedNights: List<Pair<Int, Int>>? = null, sparse: Boolean = false,
        ): Pair<(Double) -> Float, (Double) -> Float> {
            val mx = finite(listOf("pcpttl_max"), a).maxOrNull() ?: 0.0
            val scale = top ?: listOf(1.0, 2.0, 5.0, 10.0, 20.0, 50.0).firstOrNull { it >= mx } ?: 50.0
            val py = { v: Double -> (a.y0 + a.h - min(v, scale) / scale * a.h).toFloat() }
            val hy = { v: Double -> (a.y0 + a.h - (max(v, 25.0) - 25) / 75 * a.h).toFloat() }
            nights(a, fixedNights)
            for (k in 0..5) {
                val v = scale * k / 5
                hl(a.x0, a.x0 + a.w, py(v))
                if (!noAxis && (!sparse || k == 0 || k == 5)) {
                    txt(fmt(v), a.x0 - 4, py(v) + 4, align = Paint.Align.RIGHT)
                    txt((25 + 15 * k).toString(), a.x0 + a.w + 4, py(v) + 4, HUM)
                }
            }
            vgrid(a, step)
            val bw = max(3f, a.bw * 0.75f); val bottom = a.y0 + a.h
            for (i in a.from until a.to) {
                val snow = s("pcpttl_type_max", i) >= 2
                val m = s("pcpttl_max", i); val v = s("pcpttl_aver", i)
                if (!v.isNaN() && v > 0.004) {
                    val top0 = min(py(v), bottom - 2)
                    rect(a.x(i) - bw / 2, top0, bw, bottom - top0, if (snow) SNOW_AVG else RAIN_AVG)
                }
                if (!m.isNaN() && m > 0.004) {
                    rect(floor(a.x(i)) - 1, py(m), 2f, bottom - py(m), if (snow) SNOW_MAX else RAIN_MAX)
                    if (m > scale) rect(a.x(i) - 2.5f, a.y0 + 2, 5f, 5f, RED)
                }
                val storm = s("storm_max", i)
                if (!storm.isNaN() && storm > 0) {
                    val cy = py(if (m.isNaN()) 0.0 else m) - 5
                    fill.color = if (storm > 50) Color.rgb(255, 32, 32) else Color.rgb(255, 208, 0)
                    c.drawCircle(a.x(i), cy, 3.5f, fill)
                    stroke.color = Color.BLACK; stroke.strokeWidth = 0.8f; c.drawCircle(a.x(i), cy, 3.5f, stroke)
                }
            }
            line(a, "realhum_aver", HUM, lw, hy)
            crisp.strokeWidth = 2f; crisp.color = BASE; c.drawLine(a.x0, bottom - 1, a.x0 + a.w, bottom - 1, crisp)
            frame(a)
            return Pair(py, hy)
        }

        fun hm(t: Long) = Instant.ofEpochSecond(t).atZone(ZONE).format(DateTimeFormatter.ofPattern("HH:mm"))
        fun dd(i: Int) = Instant.ofEpochSecond(s.ts(i)).atZone(ZONE).format(DateTimeFormatter.ofPattern("dd.MM"))
        fun dn(i: Int) = Instant.ofEpochSecond(s.ts(i)).atZone(ZONE)
            .format(DateTimeFormatter.ofPattern("EEE", PL)).replace(".", "")
        fun p2(h: Number) = h.toString().padStart(2, '0')
        fun dayLabel(k: Int) = "${if (k == 0) "dziś" else dn(s.days[k])}, ${dd(s.days[k])}"
        fun dayEnd(k: Int) = s.days.getOrNull(k + 1) ?: s.n

        fun dms(v: Double): String {
            val a = abs(v); val d = a.toInt(); val m = ((a - d) * 60).toInt(); val sec = round(((a - d) * 60 - m) * 60).toInt()
            return "$d°${p2(m)}'${p2(sec)}\""
        }

        fun header(city: City, f: MeteogramFetcher.Forecast, sel: Int, y0: Float) {
            val noon = s.ts(s.days[sel]) + 12 * HOUR
            val (rise, set) = sun(noon, lat, lon)
            val tz = tzName(noon)
            txt("wschód słońca: ${hm(rise)} $tz", 4f, y0 + 13, HEAD, 11.5f)
            txt("zachód słońca: ${hm(set)} $tz", 4f, y0 + 27, HEAD, 11.5f)
            txt("${dms(city.lon)}E, ${dms(city.lat)}N", W / 2, y0 + 13, HEAD, 11.5f, Paint.Align.CENTER)
            if (f.row != null && f.col != null) {
                txt("(row=${f.row}, col=${f.col})", W / 2, y0 + 27, HEAD, 11.5f, Paint.Align.CENTER)
            }
            txt("model UM 4 km", W - 4, y0 + 13, HEAD, 11.5f, Paint.Align.RIGHT)
            if (f.runStart > 0) {
                val run = Instant.ofEpochSecond(f.runStart).atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("dd.MM HH"))
                txt("start: $run UTC", W - 4, y0 + 27, HEAD, 11.5f, Paint.Align.RIGHT)
            }
        }

        fun overview(sel: Int, y0: Float) {
            val tA = Area(L, y0 + 16, PW, 66f, 0, s.n)
            val rA = Area(L, y0 + 86, PW, 46f, 0, s.n)
            tempPanel(tA, lines = 3, step = 6, lw = 1.8f)
            rainPanel(rA, step = 6, lw = 1.6f, sparse = true)
            for (i in 0 until s.n) if (s.utcHour(i) % 6 == 0L) {
                txt(p2(s.localHour(i)), tA.x(i), y0 + 12, size = 11f, align = Paint.Align.CENTER)
                txt(p2(s.utcHour(i)), tA.x(i), y0 + 146, size = 11f, align = Paint.Align.CENTER)
            }
            val tz = tzName(s.t0)
            txt(tz, 2f, y0 + 12, size = 11f); txt(tz, W - 2, y0 + 12, size = 11f, align = Paint.Align.RIGHT)
            txt("UTC", 2f, y0 + 146, size = 11f); txt("UTC", W - 2, y0 + 146, size = 11f, align = Paint.Align.RIGHT)
            val sa = s.days[sel]; val sb = min(sa + 24, s.n)
            box(L + sa * tA.bw, y0 + 14, (sb - sa) * tA.bw, 120f, SEL, 2.5f)
            rot(10f, y0 + 49, listOf("temp.", "(°C)"), size = 10.5f)
            rot(10f, y0 + 109, listOf("opad"), size = 10.5f)
            rot(W - 24, y0 + 49, listOf("(°C)"), size = 10.5f)
            rot(W - 24, y0 + 109, listOf("(%)"), size = 10.5f)
        }

        fun dayTable(sel: Int, y0: Float) {
            val count = s.days.size
            val first = (sel - 1).coerceIn(0, max(0, count - 3))
            val cw = (W - 8) / 3
            for (slot in 0 until min(3, count)) {
                val k = first + slot
                val a = s.days[k]; val b = dayEnd(k)
                val hi = (a until b).map { s("airtmp_max", it) }.filter { !it.isNaN() }.maxOrNull()
                val lo = (a until b).map { s("airtmp_min", it) }.filter { !it.isNaN() }.minOrNull()
                val rain = (a until b).map { s("pcpttl_aver", it) }.filter { !it.isNaN() }.sum()
                val prob = (a until b).map { s("pcpttlprob_point", it) }.filter { !it.isNaN() }.maxOrNull() ?: 0.0
                val x = 4 + slot * cw
                rect(x + 0.5f, y0 + 1.5f, cw - 1, 32f, if (k == sel) NIGHT else Color.WHITE)
                crisp.strokeWidth = 1f; crisp.color = Color.BLACK
                c.drawRect(x + 0.5f, y0 + 1.5f, x + cw - 0.5f, y0 + 33.5f, crisp)
                txt(dayLabel(k), x + 5, y0 + 14, bold = true)
                var xx = x + 5
                xx += txt("max ${hi?.let { round(it).toInt() } ?: "–"}° ", xx, y0 + 29, RED)
                xx += txt("min ${lo?.let { round(it).toInt() } ?: "–"}° ", xx, y0 + 29, FEEL)
                xx += txt(String.format(Locale.US, "%.1f mm ", rain), xx, y0 + 29, RAIN_AVG)
                txt("${round(prob).toInt()}%", xx, y0 + 29)
            }
        }

        fun detail(sel: Int, y0: Float) {
            val from = s.days[sel]; val to = min(from + 24, s.n)
            val tA = Area(L, y0 + 30, PW, DETAIL_TH, from, to)
            val rA = Area(L, y0 + 30 + DETAIL_TH + 8, PW, DETAIL_PH, from, to)
            tempPanel(tA, lines = 7); rainPanel(rA)
            txt("${dn(from)}, ${dd(from)}", W / 2, y0 + 12, align = Paint.Align.CENTER)
            for (i in from until to) if (s.utcHour(i) % 3 == 0L) {
                txt(p2(s.localHour(i)), tA.x(i), y0 + 26, align = Paint.Align.CENTER)
                txt(p2(s.utcHour(i)), tA.x(i), y0 + DETAIL_H - 4, align = Paint.Align.CENTER)
            }
            val tz = tzName(s.ts(from))
            txt(tz, 4f, y0 + 26); txt(tz, W - 4, y0 + 26, align = Paint.Align.RIGHT)
            txt("UTC", 4f, y0 + DETAIL_H - 4); txt("UTC", W - 4, y0 + DETAIL_H - 4, align = Paint.Align.RIGHT)
            rot(12f, y0 + 30 + DETAIL_TH / 2, listOf("temperatura", "(°C)"))
            rot(W - 26, y0 + 30 + DETAIL_TH / 2, listOf("(°C)", "temperatura"))
            rot(12f, y0 + 38 + DETAIL_TH + DETAIL_PH / 2, listOf("opad", "(mm/h)"))
            rot(W - 26, y0 + 38 + DETAIL_TH + DETAIL_PH / 2, listOf("(%)", "wilgotność wzgl."))
        }

        /** Label followed by a leader line ending at the element it explains. */
        fun lead(label: String, color: Int, ly: Float, x0: Float, xe: Float, px: Float? = null, py: Float? = null) {
            val tw = txt(label, x0, ly + 4, color)
            if (px == null || py == null) return
            stroke.color = color; stroke.strokeWidth = 1f
            val path = Path().apply { moveTo(x0 + tw + 3, ly); lineTo(xe, ly); lineTo(px, py) }
            c.drawPath(path, stroke)
        }

        fun footer(y0: Float) {
            txt("meteo.pl · model UM 4 km", 30f, y0 + 13, BLUE_T, 11.5f)
            txt("dane: ICM, Uniwersytet Warszawski", W - 30, y0 + 13, BLUE_T, 11.5f, Paint.Align.RIGHT)
        }
    }

    /** Static sample used by the legend, like the old ICM legend picture. */
    private fun sample(t0: Long): Src {
        val air = doubleArrayOf(9.0, 8.0, 7.0, 5.5, 4.0, 3.0, 2.0, 1.5, 1.0, 0.5, 0.0, 0.5)
        val m = HashMap<String, DoubleArray>()
        m["airtmp_point"] = air
        m["airtmp_max"] = DoubleArray(12) { air[it] + 1.6 }
        m["airtmp_min"] = DoubleArray(12) { air[it] - 1.6 }
        m["wchill_point"] = DoubleArray(12) { air[it] - 3 }
        m["wchill_max"] = DoubleArray(12) { air[it] - 1.6 }
        m["wchill_min"] = DoubleArray(12) { air[it] - 4.6 }
        m["grdtmp_max"] = DoubleArray(12) { air[it] + if (it < 4) 7 else 2 }
        m["grdtmp_min"] = DoubleArray(12) { air[it] - if (it < 4) 4 else 3 }
        m["dwptmp_point"] = DoubleArray(12) { air[it] - 4 }
        m["pcpttl_aver"] = doubleArrayOf(.6, 1.1, 1.2, .9, .5, .1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        m["pcpttl_max"] = doubleArrayOf(1.3, 2.2, 2.6, 1.6, .9, .2, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        m["pcpttl_type_max"] = doubleArrayOf(1.0, 1.0, 1.0, 2.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        m["storm_max"] = doubleArrayOf(0.0, 30.0, 80.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        m["realhum_aver"] = doubleArrayOf(70.0, 78.0, 86.0, 90.0, 92.0, 93.0, 93.0, 94.0, 95.0, 94.0, 95.0, 96.0)
        return Src(t0, 12, true) { k, i -> m[k]?.get(i) ?: 0.0 }
    }

    private fun legend(c: Canvas, real: Src, city: City, y0: Float) {
        val s = sample(real.t0)
        val d = Drawer(c, s, city.lat, city.lon)
        val nightIdx = listOf(2 to 10)
        d.crisp.strokeWidth = 1f; d.crisp.color = Color.BLACK
        c.drawLine(0f, y0 + 1, W, y0 + 1, d.crisp)
        val cw = W / 2

        val ta = Area(cw - 112, y0 + 28, 84f, 118f, 0, 12)
        val ty = d.tempPanel(ta, noAxis = true, lw = 2f, fixedNights = nightIdx)
        for ((i, h) in listOf(1 to "20", 5 to "02", 10 to "08")) d.txt(h, ta.x(i), y0 + 22, size = 11f, align = Paint.Align.CENTER)
        val xe = ta.x0 - 6
        d.lead("Czas lokalny", Color.BLACK, y0 + 12, 4f, xe, ta.x(1), y0 + 14)
        d.lead("Temperatura:", Color.BLACK, y0 + 32, 4f, xe)
        d.lead("punkt środkowy", RED, y0 + 46, 4f, xe, ta.x(2), ty(s("airtmp_point", 2)))
        d.lead("max/min", AIR_BAND_TXT, y0 + 59, 4f, xe, ta.x(3), ty(s("airtmp_max", 3)) + 1)
        d.lead("odczuwalna", FEEL, y0 + 73, 4f, xe, ta.x(4), ty(s("wchill_point", 4)))
        d.lead("max/min", FEEL_BAND_TXT, y0 + 86, 4f, xe, ta.x(5), ty(s("wchill_min", 5)) - 1)
        d.lead("Temp. gruntu max/min", GROUND, y0 + 104, 4f, xe, ta.x(1), ty(s("grdtmp_max", 1)) + 4)
        d.lead("Temp. punktu rosy", DEW, y0 + 122, 4f, xe, ta.x(7), ty(s("dwptmp_point", 7)))
        d.lead("Noc (szare tło)", GREY_TXT, y0 + 140, 4f, xe, ta.x(8), ta.y0 + ta.h - 8)

        val ra = Area(W - 100, y0 + 28, 84f, 118f, 0, 12)
        val (py, hy) = d.rainPanel(ra, noAxis = true, top = 3.0, lw = 2f, fixedNights = nightIdx)
        val xe2 = ra.x0 - 6; val x2 = cw + 6
        d.lead("Opad:", Color.BLACK, y0 + 32, x2, xe2)
        d.lead("deszcz: max", Color.rgb(0, 192, 0), y0 + 46, x2, xe2, ra.x(2), py(2.4))
        d.lead("średni", RAIN_AVG, y0 + 59, x2, xe2, ra.x(1) + 2, py(0.6))
        d.lead("śnieg: max / średni", SNOW_AVG, y0 + 73, x2, xe2, ra.x(3), py(1.2))
        d.lead("Wystąpienie burzy", Color.BLACK, y0 + 87, x2, xe2, ra.x(2) + 3, py(2.6) - 5)
        d.lead("Wilgotność względna", HUM, y0 + 104, x2, xe2, ra.x(7), hy(93.0))
        d.lead("(skala prawa, %)", HUM, y0 + 117, x2, xe2)
        d.lead("Północ = pionowa linia", Color.BLACK, y0 + 134, x2, xe2)

        d.txt("Ramka na wykresie 60 h = dzień pokazany niżej · ◀ ▶ zmiana dnia", 4f, y0 + 166, BLUE_T, 11.5f)
        d.txt("Dni: max/min temp. (°C), suma opadu (mm), prawdopodobieństwo opadu (%)", 4f, y0 + 182, BLUE_T, 11.5f)
    }
}

package org.kawk.meteopolska

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import java.net.HttpURLConnection
import java.net.URL

object MeteogramFetcher {

    private const val GAP_PX = 14
    private const val MAX_WIDTH_PX = 1200
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun fetch(col: Int, row: Int): Bitmap {
        val mgramUrl = Constants.MGRAM_BASE_URL.format(row, col)
        val meteogram = download(mgramUrl)
        val legend = download(Constants.LEGEND_URL)

        val mgCropped = trimWhite(cropBanner(meteogram))
        if (mgCropped != meteogram) meteogram.recycle()
        val legCropped = trimWhite(legend)
        if (legCropped != legend) legend.recycle()

        val composed = compose(mgCropped, legCropped)
        if (composed != mgCropped) mgCropped.recycle()
        if (composed != legCropped) legCropped.recycle()
        return composed
    }

    private fun download(urlStr: String): Bitmap {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android) MeteoPolskaWidget")
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP $code for $urlStr")
            }
            return conn.inputStream.use { input ->
                BitmapFactory.decodeStream(input)
                    ?: throw IllegalStateException("decode failed for $urlStr")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun cropBanner(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val scanH = minOf(200, h)
        val row = IntArray(w)
        var bannerBottom = 0
        for (y in 0 until scanH) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var blue = 0
            var tot = 0
            var x = 0
            while (x < w) {
                val c = row[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                tot++
                if (b > 90 && b > r + 20 && g < b) blue++
                x += 4
            }
            if (tot > 0 && blue > tot / 2) bannerBottom = y
        }
        val top = if (bannerBottom > 0) bannerBottom + 1 else 0
        if (top <= 0 || top >= h) return src
        return Bitmap.createBitmap(src, 0, top, w, h - top)
    }

    private fun trimWhite(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var idx = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if ((255 - r) + (255 - g) + (255 - b) > 12) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                idx++
            }
        }
        if (maxX < minX || maxY < minY) return src
        val cw = maxX - minX + 1
        val ch = maxY - minY + 1
        if (cw == w && ch == h) return src
        return Bitmap.createBitmap(src, minX, minY, cw, ch)
    }

    private val SECTION_BOUNDS = floatArrayOf(
        0.0f, 0.1895f, 0.3344f, 0.4745f, 0.6181f, 0.6807f, 0.8204f, 1.0f
    )
    private const val SMALL_GAP_PX = 4

    /**
     * Composition: meteogram on top (full width), legend split into 7 natural
     * sections arranged 3-left + 4-right below. Asterisk removed, borders
     * trimmed, semi-transparency applied to white areas.
     */
    private fun compose(meteogram: Bitmap, legend: Bitmap): Bitmap {
        val mgW = meteogram.width
        val mgH = meteogram.height

        val legNoAsterisk = removeAsterisk(legend)
        val legW = legNoAsterisk.width
        val legH = legNoAsterisk.height

        val targetHalfW = (mgW - GAP_PX) / 2

        val sections = ArrayList<Bitmap>(7)
        for (i in 0 until 7) {
            val y1 = (SECTION_BOUNDS[i] * legH).toInt()
            val y2 = (SECTION_BOUNDS[i + 1] * legH).toInt().coerceAtMost(legH)
            if (y2 <= y1) continue
            val raw = Bitmap.createBitmap(legNoAsterisk, 0, y1, legW, y2 - y1)
            val trimmed = trimWhite(raw)
            if (trimmed != raw) raw.recycle()
            val sw = targetHalfW
            val sh = (trimmed.height * sw / trimmed.width.toFloat()).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(trimmed, sw, sh, true)
            if (scaled != trimmed) trimmed.recycle()
            val semi = applySemiTransparency(scaled)
            if (semi != scaled) scaled.recycle()
            sections.add(semi)
        }

        if (legNoAsterisk != legend) legNoAsterisk.recycle()

        var leftH = 0
        for (i in 0 until minOf(3, sections.size)) leftH += sections[i].height + SMALL_GAP_PX
        var rightH = 0
        for (i in minOf(3, sections.size) until sections.size) rightH += sections[i].height + SMALL_GAP_PX
        val legendRowH = maxOf(leftH, rightH)

        val width = mgW
        val height = mgH + GAP_PX + legendRowH

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)

        canvas.drawBitmap(meteogram, null, Rect(0, 0, mgW, mgH), null)

        val leftCount = minOf(3, sections.size)
        var yOff = mgH + GAP_PX
        for (i in 0 until leftCount) {
            val s = sections[i]
            canvas.drawBitmap(s, null, Rect(0, yOff, s.width, yOff + s.height), null)
            yOff += s.height + SMALL_GAP_PX
        }

        yOff = mgH + GAP_PX
        for (i in leftCount until sections.size) {
            val s = sections[i]
            canvas.drawBitmap(
                s, null,
                Rect(targetHalfW + GAP_PX, yOff,
                     targetHalfW + GAP_PX + s.width, yOff + s.height),
                null,
            )
            yOff += s.height + SMALL_GAP_PX
        }

        for (s in sections) s.recycle()

        if (width > MAX_WIDTH_PX) {
            val scale = MAX_WIDTH_PX.toFloat() / width
            val sh = (height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(out, MAX_WIDTH_PX, sh, true)
            if (scaled != out) out.recycle()
            return scaled
        }
        return out
    }

    /**
     * Detect and remove the asterisk footer from the legend by finding
     * a gap of >8 white rows near the bottom that separates the main
     * content from the asterisk.
     */
    private fun removeAsterisk(legend: Bitmap): Bitmap {
        val w = legend.width
        val h = legend.height
        val pixels = IntArray(w * h)
        legend.getPixels(pixels, 0, w, 0, 0, w, h)

        val rowContent = IntArray(h)
        for (y in 0 until h) {
            var count = 0
            val rowStart = y * w
            for (x in 0 until w) {
                val c = pixels[rowStart + x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if ((255 - r) + (255 - g) + (255 - b) > 12) count++
            }
            rowContent[y] = count
        }

        var lastContent = h - 1
        while (lastContent >= 0 && rowContent[lastContent] < 3) lastContent--
        if (lastContent < 0) return legend

        var y = lastContent
        while (y >= 0) {
            if (rowContent[y] < 3) {
                var gapLen = 0
                while (y - gapLen >= 0 && rowContent[y - gapLen] < 3) gapLen++
                if (gapLen > 8) {
                    return Bitmap.createBitmap(legend, 0, 0, w, y - gapLen + 1)
                }
                y -= gapLen
            } else {
                y--
            }
        }

        return legend
    }

    /**
     * Apply semi-transparency: white/near-white pixels get alpha ~200,
     * content pixels stay opaque (255).
     */
    private fun applySemiTransparency(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val whiteness = minOf(r, g, b)
            val alpha = (200 + (255 - whiteness) * 55 / 255).coerceIn(200, 255)
            pixels[i] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}

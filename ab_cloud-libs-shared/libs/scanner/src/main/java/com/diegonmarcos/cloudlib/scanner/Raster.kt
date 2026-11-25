package com.diegonmarcos.cloudlib.scanner

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** #1839 an ARGB pixel grid with no Android type in it, so the whole scan pipeline runs in the JVM suite. */
class Raster(val w: Int, val h: Int, val px: IntArray = IntArray(w * h) { WHITE }) {
    init { require(px.size == w * h) { "pixel count ${px.size} is not ${w}x$h" } }

    operator fun get(x: Int, y: Int): Int = px[y * w + x]

    /** Bilinear sample at a real position; outside the grid is paper white. */
    fun sample(x: Double, y: Double): Int {
        if (x < -0.5 || y < -0.5 || x > w - 0.5 || y > h - 0.5) return WHITE
        val fx = floor(x).toInt(); val fy = floor(y).toInt()
        val tx = x - fx; val ty = y - fy
        val x0 = fx.coerceIn(0, w - 1); val x1 = (fx + 1).coerceIn(0, w - 1)
        val y0 = fy.coerceIn(0, h - 1); val y1 = (fy + 1).coerceIn(0, h - 1)
        val a = px[y0 * w + x0]; val b = px[y0 * w + x1]; val c = px[y1 * w + x0]; val d = px[y1 * w + x1]
        var out = 0xFF shl 24
        for (shift in intArrayOf(16, 8, 0)) {
            val top = ((a shr shift) and 255) * (1 - tx) + ((b shr shift) and 255) * tx
            val bot = ((c shr shift) and 255) * (1 - tx) + ((d shr shift) and 255) * tx
            out = out or ((top * (1 - ty) + bot * ty).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    /** The perspective correction: the [q] region of this image flattened to an [outW] x [outH] page. Null when [q] is degenerate. */
    fun warp(q: Quad, outW: Int, outH: Int): Raster? {
        val rect = listOf(P(0.0, 0.0), P(outW - 1.0, 0.0), P(outW - 1.0, outH - 1.0), P(0.0, outH - 1.0))
        val hm = Homography.solve(rect, q.points) ?: return null
        val out = IntArray(outW * outH)
        for (y in 0 until outH) for (x in 0 until outW) {
            val p = Homography.map(hm, x.toDouble(), y.toDouble())
            out[y * outW + x] = sample(p.x, p.y)
        }
        return Raster(outW, outH, out)
    }

    /** Turned [quarters] x 90 degrees clockwise. */
    fun rotateQuarters(quarters: Int): Raster {
        val k = ((quarters % 4) + 4) % 4
        if (k == 0) return this
        val nw = if (k == 2) w else h; val nh = if (k == 2) h else w
        val out = IntArray(nw * nh)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (k) { 1 -> (h - 1 - y) to x; 2 -> (w - 1 - x) to (h - 1 - y); else -> y to (w - 1 - x) }
            out[ny * nw + nx] = px[y * w + x]
        }
        return Raster(nw, nh, out)
    }

    /** Turned by [deg] clockwise about the centre, same canvas, white where nothing comes from (the deskew). */
    fun rotate(deg: Double): Raster {
        if (deg == 0.0) return this
        val r = Math.toRadians(deg); val c = cos(r); val s = sin(r)
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val dx = x - cx; val dy = y - cy
            // inverse map: rotate the output point by -deg
            out[y * w + x] = sample(cx + dx * c + dy * s, cy - dx * s + dy * c)
        }
        return Raster(w, h, out)
    }

    /** Scaled down so the longer side is at most [maxSide] (self when it already is). */
    fun scaledToMax(maxSide: Int): Raster {
        val longest = max(w, h)
        if (longest <= maxSide) return this
        val f = maxSide.toDouble() / longest
        val nw = max(1, (w * f).roundToInt()); val nh = max(1, (h * f).roundToInt())
        val out = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) out[y * nw + x] = sample((x + 0.5) / f - 0.5, (y + 0.5) / f - 0.5)
        return Raster(nw, nh, out)
    }

    fun luma(i: Int): Int = lumaOf(px[i])

    companion object {
        const val WHITE = -1 // 0xFFFFFFFF
        fun lumaOf(p: Int): Int = (299 * ((p shr 16) and 255) + 587 * ((p shr 8) and 255) + 114 * (p and 255)) / 1000
        fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}

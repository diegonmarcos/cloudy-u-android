package com.diegonmarcos.cloudlib.scanner

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** #1839 a point in image pixels (x right, y down). */
data class P(val x: Double, val y: Double)

/**
 * #1839 the document's four corners in the source image, clockwise from the top-left. The one value the Frame step
 * edits: automatic detection fills it, the handles move it, the perspective correction reads it. Pure.
 */
data class Quad(val tl: P, val tr: P, val br: P, val bl: P) {
    val points: List<P> get() = listOf(tl, tr, br, bl)

    /** The size of the flattened page: the longer of each pair of opposite edges, so nothing is squeezed. */
    fun outputSize(): Pair<Int, Int> {
        val w = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y))
        val h = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y))
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    fun scaled(sx: Double, sy: Double): Quad = Quad(P(tl.x * sx, tl.y * sy), P(tr.x * sx, tr.y * sy), P(br.x * sx, br.y * sy), P(bl.x * sx, bl.y * sy))

    /** Twice the signed area (shoelace); the sign says the winding. */
    private fun cross2(): Double = points.indices.sumOf { i -> val a = points[i]; val b = points[(i + 1) % 4]; a.x * b.y - b.x * a.y }

    fun area(): Double = kotlin.math.abs(cross2()) / 2

    /** A page that can be flattened: every turn the same way (no bow-tie, no dent) and a real area. */
    fun isConvex(): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            val z = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val s = if (z > 0) 1 else if (z < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return area() > 1.0
    }

    /** The tilt of the top and bottom edges against the horizontal, in degrees (clockwise positive, y down). */
    fun edgeTiltDegrees(): Double {
        val top = Math.toDegrees(atan2(tr.y - tl.y, tr.x - tl.x))
        val bottom = Math.toDegrees(atan2(br.y - bl.y, br.x - bl.x))
        return (top + bottom) / 2
    }

    /** The same corners after the image is turned [quarters] x 90 degrees clockwise ([w] x [h] is the image BEFORE the turn). */
    fun rotatedQuarters(quarters: Int, w: Int, h: Int): Quad {
        var q = this; var cw = w.toDouble(); var ch = h.toDouble()
        repeat(((quarters % 4) + 4) % 4) {
            fun r(p: P) = P(ch - 1 - p.y, p.x)
            q = Quad(r(q.bl), r(q.tl), r(q.tr), r(q.br))
            val t = cw; cw = ch; ch = t
        }
        return q
    }

    companion object {
        /** Any four points as a Quad in reading order: top-left has the smallest x+y, bottom-right the largest, the other two split on x-y. */
        fun ordered(pts: List<P>): Quad {
            require(pts.size == 4) { "a document has four corners" }
            val tl = pts.minBy { it.x + it.y }
            val br = pts.maxBy { it.x + it.y }
            val tr = pts.maxBy { it.x - it.y }
            val bl = pts.minBy { it.x - it.y }
            return Quad(tl, tr, br, bl)
        }

        /** The whole frame, [inset] of each side in from the edge (0..0.5). */
        fun full(w: Int, h: Int, inset: Double = 0.0): Quad {
            val x0 = w * inset; val y0 = h * inset; val x1 = w - 1 - w * inset; val y1 = h - 1 - h * inset
            return Quad(P(x0, y0), P(x1, y0), P(x1, y1), P(x0, y1))
        }
    }
}

/** #1839 the projective map between two quads (the perspective correction). Pure. */
object Homography {
    /** The 3x3 matrix (row-major, h22 = 1) taking [src] corners to [dst] corners, or null when the four points are degenerate. */
    fun solve(src: List<P>, dst: List<P>): DoubleArray? {
        require(src.size == 4 && dst.size == 4)
        val m = Array(8) { DoubleArray(9) }
        for (i in 0 until 4) {
            val (x, y) = src[i]; val (u, v) = dst[i]
            m[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
            m[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
        }
        for (c in 0 until 8) {
            var p = c
            for (r in c + 1 until 8) if (kotlin.math.abs(m[r][c]) > kotlin.math.abs(m[p][c])) p = r
            if (kotlin.math.abs(m[p][c]) < 1e-9) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until 8) if (r != c) {
                val f = m[r][c] / m[c][c]
                if (f != 0.0) for (k in c until 9) m[r][k] -= f * m[c][k]
            }
        }
        return DoubleArray(9) { if (it < 8) m[it][8] / m[it][it] else 1.0 }
    }

    fun map(h: DoubleArray, x: Double, y: Double): P {
        val d = h[6] * x + h[7] * y + h[8]
        return P((h[0] * x + h[1] * y + h[2]) / d, (h[3] * x + h[4] * y + h[5]) / d)
    }
}

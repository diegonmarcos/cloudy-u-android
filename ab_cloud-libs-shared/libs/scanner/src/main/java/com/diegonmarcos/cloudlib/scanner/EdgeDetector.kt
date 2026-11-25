package com.diegonmarcos.cloudlib.scanner


/**
 * #1839 automatic document edge detection and deskew, pure (no OpenCV, no Play Services; the repo has neither).
 * Detection: the picture is shrunk, split light from dark with Otsu's threshold, and the document is the biggest blob
 * of either side that does not wrap the frame (a table does, a sheet of paper does not); its four extreme points are the
 * corners. When nothing plausible is found the frame itself (slightly inset) is offered for the manual handles.
 */
object EdgeDetector {
    private const val WORK = 220
    private const val MIN_AREA = 0.08

    fun findQuad(src: Raster): Quad {
        val small = src.scaledToMax(WORK)
        val fallback = Quad.full(src.w, src.h, 0.03)
        if (small.w < 8 || small.h < 8) return fallback
        val g = IntArray(small.px.size) { small.luma(it) }
        val t = otsu(g)
        var best: Pair<Double, List<P>>? = null
        for (light in booleanArrayOf(true, false)) {
            val mask = BooleanArray(g.size) { (g[it] > t) == light }
            val blob = largestBlob(mask, small.w, small.h) ?: continue
            val frac = blob.area.toDouble() / g.size
            if (frac < MIN_AREA || frac > 0.97) continue
            val score = frac - 0.3 * blob.sidesTouched
            if (best == null || score > best.first) best = score to blob.extremes
        }
        val corners = best?.second ?: return fallback
        val sx = src.w.toDouble() / small.w; val sy = src.h.toDouble() / small.h
        val q = Quad.ordered(corners).scaled(sx, sy)
        return if (q.isConvex()) q else fallback
    }

    /** #1908 the live-capture variant: the document's corners, or null when nothing plausible is in view (the manual fallback frame is not a detection). */
    fun findQuadOrNull(src: Raster): Quad? {
        val q = findQuad(src)
        return if (q == Quad.full(src.w, src.h, 0.03)) null else q
    }

    /** The angle to rotate the page by (clockwise degrees, -15..15) that makes its text lines horizontal. */
    fun deskewDegrees(src: Raster, range: Int = 15): Double {
        val small = src.scaledToMax(300)
        val g = IntArray(small.px.size) { small.luma(it) }
        val mean = g.average()
        val ink = ArrayList<Int>()
        for (i in g.indices) if (g[i] < mean * 0.8) ink.add(i)
        if (ink.size < 20) return 0.0
        var bestScore = -1.0; var bestDeg = 0.0
        val bins = small.w + small.h
        var d = -range.toDouble()
        while (d <= range + 1e-9) {
            val r = Math.toRadians(d); val s = Math.sin(r); val c = Math.cos(r)
            val hist = IntArray(bins + 1)
            for (i in ink) {
                val x = (i % small.w).toDouble(); val y = (i / small.w).toDouble()
                hist[(y * c - x * s + small.w).toInt().coerceIn(0, bins)]++
            }
            var score = 0.0
            for (n in hist) score += n.toDouble() * n
            if (score > bestScore) { bestScore = score; bestDeg = d }
            d += 0.5
        }
        return -bestDeg
    }

    private fun otsu(g: IntArray): Int {
        val hist = IntArray(256); for (v in g) hist[v.coerceIn(0, 255)]++
        val total = g.size.toDouble()
        var sum = 0.0; for (i in 0 until 256) sum += i * hist[i]
        var wB = 0.0; var sB = 0.0; var best = -1.0; var thr = 128
        for (i in 0 until 256) {
            wB += hist[i]; if (wB == 0.0) continue
            val wF = total - wB; if (wF == 0.0) break
            sB += i * hist[i]
            val mB = sB / wB; val mF = (sum - sB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; thr = i }
        }
        return thr
    }

    private class Blob(val area: Int, val sidesTouched: Int, val extremes: List<P>)

    private fun largestBlob(mask: BooleanArray, w: Int, h: Int): Blob? {
        val seen = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var best: Blob? = null
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var area = 0
            var minS = Int.MAX_VALUE; var maxS = Int.MIN_VALUE; var minD = Int.MAX_VALUE; var maxD = Int.MIN_VALUE
            var tl = 0; var br = 0; var tr = 0; var bl = 0
            var top = false; var bottom = false; var left = false; var right = false
            while (sp > 0) {
                val i = stack[--sp]; area++
                val x = i % w; val y = i / w
                if (x == 0) left = true; if (x == w - 1) right = true; if (y == 0) top = true; if (y == h - 1) bottom = true
                if (x + y < minS) { minS = x + y; tl = i }; if (x + y > maxS) { maxS = x + y; br = i }
                if (x - y > maxD) { maxD = x - y; tr = i }; if (x - y < minD) { minD = x - y; bl = i }
                if (x > 0 && mask[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && mask[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (best == null || area > best.area) {
                fun p(i: Int) = P((i % w).toDouble(), (i / w).toDouble())
                best = Blob(area, listOf(top, bottom, left, right).count { it }, listOf(p(tl), p(tr), p(br), p(bl)))
            }
        }
        return best
    }
}

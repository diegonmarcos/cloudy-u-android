package com.diegonmarcos.cloudlib.pdf

/** A page's media box in PDF units (origin bottom-left) and its display rotation (0, 90, 180, 270 clockwise). */
data class PdfPageSize(val width: Float, val height: Float, val originX: Float = 0f, val originY: Float = 0f, val rotation: Int = 0)

/** A point in PDF user space. */
data class PdfPoint(val x: Float, val y: Float)

/** The one conversion the editor needs: a touch on the DISPLAYED page to a point in PDF space. */
object PdfGeometry {
    /** [u] and [v] are fractions of the displayed page, (0,0) top-left, (1,1) bottom-right. */
    fun toPdf(u: Float, v: Float, page: PdfPageSize): PdfPoint {
        val a = u.coerceIn(0f, 1f)
        val b = v.coerceIn(0f, 1f)
        val w = page.width
        val h = page.height
        return when (((page.rotation % 360) + 360) % 360) {
            90 -> PdfPoint(page.originX + b * w, page.originY + a * h)
            180 -> PdfPoint(page.originX + (1 - a) * w, page.originY + b * h)
            270 -> PdfPoint(page.originX + (1 - b) * w, page.originY + (1 - a) * h)
            else -> PdfPoint(page.originX + a * w, page.originY + (1 - b) * h)
        }
    }

    /** The displayed size of the page, width and height swapped for a quarter turn. */
    fun displaySize(page: PdfPageSize): Pair<Float, Float> =
        if (((page.rotation % 360) + 360) % 360 % 180 == 90) page.height to page.width else page.width to page.height

    /** The order of the pages after moving the one at [from] to [to] (indexes of the current pages, in the new order). */
    fun moved(count: Int, from: Int, to: Int): List<Int> {
        val order = (0 until count).toMutableList()
        if (from !in 0 until count || to !in 0 until count) return order
        order.add(to, order.removeAt(from))
        return order
    }
}

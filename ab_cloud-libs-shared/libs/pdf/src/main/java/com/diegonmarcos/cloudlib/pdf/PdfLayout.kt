package com.diegonmarcos.cloudlib.pdf

/**
 * Where every page sits in the reader's scroll space (#577), as pure arithmetic with no Android
 * type so PdfLayoutTest runs it on the CI runner.
 *
 * Coordinates are "content space": zoom 1, origin at the top-left of the first slot. The view
 * multiplies by its zoom and adds its scroll offset; nothing here knows either.
 *
 * Two modes share one model, a stack of vertical SLOTS:
 *  - continuous: a page is fitted to the view WIDTH and its slot is the page plus [gap];
 *  - paged: a page is fitted whole inside the view and its slot is exactly the view's height,
 *    the page centred in it, so "one page per screen" is just "scroll to a slot boundary".
 *
 * Page sizes are in PDF points and start out as the first page's size. A 3,000-page document
 * opens at once on that guess and the true sizes are fed in by [setSize] as a background scan
 * reads them; a uniform document never shifts, a mixed one is re-anchored by the view.
 */
class PdfLayout(
    val pageCount: Int,
    private val viewW: Int,
    private val viewH: Int,
    private val paged: Boolean,
    private val gap: Float,
    guessW: Float,
    guessH: Float
) {
    private val ptW = FloatArray(pageCount) { guessW.coerceAtLeast(1f) }
    private val ptH = FloatArray(pageCount) { guessH.coerceAtLeast(1f) }
    private val slotTops = FloatArray(pageCount + 1)

    /** Total height of the stack at zoom 1. */
    var totalHeight = 0f
        private set

    init { rebuild() }

    /** Adopts every size [other] has learned, so a mode or view-size change forgets nothing. */
    fun copySizesFrom(other: PdfLayout) {
        val n = Math.min(pageCount, other.pageCount)
        for (i in 0 until n) {
            ptW[i] = other.ptW[i]
            ptH[i] = other.ptH[i]
        }
        rebuild()
    }

    /** Records a page's true size in points; true when it differs enough to move the layout. */
    fun setSize(index: Int, w: Float, h: Float): Boolean {
        if (index < 0 || index >= pageCount) return false
        val nw = w.coerceAtLeast(1f)
        val nh = h.coerceAtLeast(1f)
        if (Math.abs(nw - ptW[index]) < 0.5f && Math.abs(nh - ptH[index]) < 0.5f) return false
        ptW[index] = nw
        ptH[index] = nh
        return true
    }

    /** Recomputes every slot top. O(pages): call once per batch of [setSize], not per page. */
    fun rebuild() {
        var y = 0f
        for (i in 0 until pageCount) {
            slotTops[i] = y
            y += slotHeight(i)
        }
        slotTops[pageCount] = y
        totalHeight = y
    }

    /** Pixels per PDF point at zoom 1. */
    private fun scaleOf(i: Int): Float =
        if (paged) Math.min(viewW / ptW[i], viewH / ptH[i]) else viewW / ptW[i]

    fun pageWidth(i: Int): Float = ptW[i] * scaleOf(i)

    fun pageHeight(i: Int): Float = ptH[i] * scaleOf(i)

    fun pageLeft(i: Int): Float = (viewW - pageWidth(i)) / 2f

    fun slotTop(i: Int): Float = slotTops[i]

    fun slotHeight(i: Int): Float = if (paged) viewH.toFloat() else pageHeight(i) + gap

    fun pageTop(i: Int): Float = slotTops[i] + (slotHeight(i) - pageHeight(i)) / 2f

    /** The slot holding content-space [y]; clamped so it is always a real page. */
    fun pageAt(y: Float): Int {
        if (pageCount == 0) return 0
        if (y <= 0f) return 0
        if (y >= totalHeight) return pageCount - 1
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (slotTops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }
}

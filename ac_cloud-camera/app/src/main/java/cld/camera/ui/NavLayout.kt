package cld.camera.ui

/**
 * The camera screen's vertical layout as plain numbers: the mode nav sits at the very bottom (lifted only by the
 * navigation-bar inset) and the preview is laid out ABOVE its top edge, never under it. activity_main.xml says
 * the same thing with `layout_above` (main_frame above camera_mode_tabs); the unit test pins the geometry for
 * many window shapes and the instrumented test measures the real views against [Box.intersects].
 */
object NavLayout {
    /** A rectangle in window pixels; [right] and [bottom] are exclusive edges. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top

        /** True when the two share any area (touching edges do not count). */
        fun intersects(o: Box): Boolean = left < o.right && o.left < right && top < o.bottom && o.top < bottom
    }

    /** The nav: full width, [navHeight] tall, its bottom edge [bottomInset] above the window's bottom. */
    fun navBounds(windowW: Int, windowH: Int, navHeight: Int, bottomInset: Int): Box {
        val bottom = windowH - bottomInset
        return Box(0, bottom - navHeight, windowW, bottom)
    }

    /** The frame the preview may use: below the top inset, ending at the nav's top edge. */
    fun frameBounds(windowW: Int, windowH: Int, topInset: Int, navHeight: Int, bottomInset: Int): Box {
        val navTop = navBounds(windowW, windowH, navHeight, bottomInset).top
        return Box(0, topInset, windowW, maxOf(topInset, navTop))
    }

    /** The preview inside [frame]: the largest [ratioW]:[ratioH] box that fits, top-aligned, centred sideways. */
    fun previewBounds(frame: Box, ratioW: Int, ratioH: Int): Box {
        if (frame.width <= 0 || frame.height <= 0 || ratioW <= 0 || ratioH <= 0) return Box(frame.left, frame.top, frame.left, frame.top)
        var w = frame.width
        var h = w.toLong() * ratioH / ratioW
        if (h > frame.height) { h = frame.height.toLong(); w = (h * ratioW / ratioH).toInt() }
        val left = frame.left + (frame.width - w) / 2
        return Box(left, frame.top, left + w, frame.top + h.toInt())
    }
}

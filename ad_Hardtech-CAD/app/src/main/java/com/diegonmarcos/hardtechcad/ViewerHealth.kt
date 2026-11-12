package com.diegonmarcos.hardtechcad

import kotlin.math.abs

/**
 * #1658 what the viewer does with the two real measurements of a shown model: the share of the WebGL framebuffer the page painted (its own
 * read-back) and the share of the viewer's rectangle ON THE SCREEN that is not background (a PixelCopy of the window, taken by ui/ModelViewer.kt).
 * A model that is in the framebuffer and not on the screen is a presentation failure of the WebView, invisible to the page: the viewer then steps
 * to the next presentation. Pure JVM (no Android class), so every rule is unit-tested.
 */
object ViewerHealth {
    /** The viewer's background (#0E1116), the colour of an empty stage. */
    const val BACKGROUND: Int = 0xFF0E1116.toInt()
    /** A pixel counts as drawn when its channels differ from the background by more than this in total. */
    const val TOLERANCE: Int = 24
    /** Below this share of non-background pixels the screen shows no model. */
    const val MIN_SCREEN_PCT: Double = 0.5
    /** The page reports "the canvas drew nothing" below this share of its framebuffer. */
    const val MIN_PAINTED_PCT: Double = 0.1
    /** A viewer narrower or lower than this (px) has no surface to draw on. */
    const val MIN_SIDE: Int = 16

    /** How the rendered frame reaches the screen, in fallback order; [js] is the mode name the page's `cadPresent` takes. */
    enum class Present(val js: String, val label: String) {
        WEBGL("webgl", "the WebGL canvas"),
        CANVAS2D("2d", "the 2D canvas (the WebGL layer did not reach the screen)"),
        HOST("host", "an image drawn by the app (the WebView did not reach the screen)"),
    }

    fun sized(w: Int, h: Int): Boolean = w >= MIN_SIDE && h >= MIN_SIDE

    /** The share (0..100) of [pixels] (ARGB) that are not the viewer background; -1 for no pixels. */
    fun coverage(pixels: IntArray, background: Int = BACKGROUND): Double {
        if (pixels.isEmpty()) return -1.0
        val br = background shr 16 and 0xFF; val bg = background shr 8 and 0xFF; val bb = background and 0xFF
        var n = 0
        for (p in pixels) if (abs((p shr 16 and 0xFF) - br) + abs((p shr 8 and 0xFF) - bg) + abs((p and 0xFF) - bb) > TOLERANCE) n++
        return 100.0 * n / pixels.size
    }

    /** True when the page painted the model and the screen shows none of it. A screen share below 0 is "not measured": never a failure. */
    fun lost(paintedPct: Double, screenPct: Double): Boolean = paintedPct >= MIN_PAINTED_PCT && screenPct >= 0 && screenPct < MIN_SCREEN_PCT

    /** The presentation to try next, or null: the model is on the screen, the screen was not measured, or every presentation was tried. */
    fun next(present: Present, paintedPct: Double, screenPct: Double): Present? =
        if (!lost(paintedPct, screenPct)) null else Present.entries.getOrNull(present.ordinal + 1)

    /**
     * #1664 the presentation a viewer STARTS in. A fallback the viewer had to step to is remembered for the build it was measured on ([build]), so the
     * next model opens straight in it; a different build (an update) starts again on the GPU path (the WebGL canvas), so a fallback is never permanent.
     */
    fun start(remembered: String?, rememberedBuild: String?, build: String): Present =
        if (remembered.isNullOrBlank() || rememberedBuild != build) Present.WEBGL else Present.entries.firstOrNull { it.js == remembered } ?: Present.WEBGL

    /**
     * #1664 what a gesture on the viewer becomes, in every presentation: the HOST takes the touch over the viewer and forwards it to the page's camera.
     * One finger orbits ([dx], [dy] in CSS px), two or more pan by the centroid's move and zoom by the spread ratio ([zoom] > 1 = closer).
     * Returns the page calls to run, in order; an empty list for a move that changes nothing.
     */
    fun gesture(pointers: Int, dx: Float, dy: Float, zoom: Float): List<String> {
        val f = { v: Float -> "%.2f".format(java.util.Locale.ROOT, v) }
        if (pointers <= 0) return emptyList()
        if (pointers == 1) return if (dx == 0f && dy == 0f) emptyList() else listOf("window.cadOrbit&&window.cadOrbit(${f(dx)},${f(dy)})")
        return buildList {
            if (dx != 0f || dy != 0f) add("window.cadPan&&window.cadPan(${f(dx)},${f(dy)})")
            if (zoom > 0f && zoom != 1f && zoom.isFinite()) add("window.cadZoom&&window.cadZoom(${"%.4f".format(java.util.Locale.ROOT, zoom)})")
        }
    }

    /** #1664 how often the app pulls the page's frame in the 'host' presentation: fast while a gesture moves the model, slow when it is still. */
    fun pullDelayMs(msSinceGesture: Long): Long = if (msSinceGesture in 0 until 1200) 40L else 200L

    private fun pct(v: Double): String = if (v < 0) "not measured" else "%.1f%%".format(java.util.Locale.ROOT, v)

    /** The one-line state under the viewer: what was rendered, how it is presented and what the screen really shows, or where it stopped. */
    fun line(state: String, stage: String, present: Present, triangles: Long, w: Int, h: Int, paintedPct: Double, screenPct: Double, gl: String, error: String = ""): String = when (state) {
        "failed" -> "Not rendered: " + error.ifBlank { "unknown" } + (if (stage.isNotBlank()) " (viewer stage: $stage)" else "")
        "shown" -> listOfNotNull(
            "Rendered: $triangles triangles",
            if (w > 0 && h > 0) "canvas ${w}x$h" else null,
            "painted " + pct(paintedPct),
            "on screen " + pct(screenPct) + (if (lost(paintedPct, screenPct)) " (NOT VISIBLE)" else ""),
            "through " + present.label,
            gl.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        "store-only" -> "No render: this format is store-only"
        else -> "Loading" + (if (stage.isNotBlank()) ": $stage" else "…")
    }
}

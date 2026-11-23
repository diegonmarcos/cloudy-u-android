package com.diegonmarcos.cloudcanvas.draw

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * #1865 the Draw page's brush rail and brush dynamics. Pure model (no Android type), so the JVM suite runs every function here.
 * The rail lists the freehand brushes top to bottom under the size and opacity bars; the tools grid lists every [Tool].
 */
object Brushes {
    data class RailBrush(val id: String, val label: String, val tool: Tool)

    /** The vertical rail: pencil, marker, brush, airbrush, ink, smudge, eraser. */
    val rail: List<RailBrush> = listOf(
        RailBrush("pencil", "Pencil", Tool.Pencil),
        RailBrush("marker", "Marker", Tool.Marker),
        RailBrush("brush", "Brush", Tool.Brush),
        RailBrush("airbrush", "Airbrush", Tool.Airbrush),
        RailBrush("ink", "Ink", Tool.Ink),
        RailBrush("smudge", "Smudge", Tool.Smudge),
        RailBrush("eraser", "Eraser", Tool.Eraser),
    )

    /** The tools grid: every tool of the page, the rail's brushes included, in declaration order. */
    val grid: List<Tool> get() = Tool.entries

    /** Ink: a slow segment draws wide, a fast one thin (0.4 to 1.4 times the size), never under one pixel. */
    fun inkWidth(size: Float, segmentLength: Float): Float {
        val speed = (segmentLength / (size * 4f).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (size * (1.4f - speed)).coerceAtLeast(1f)
    }

    data class Dab(val x: Float, val y: Float, val r: Float)

    /**
     * Airbrush: a spray of small dabs around each point of the stroke, inside a disc of radius [size], denser with more pressure.
     * Deterministic for a given stroke and [seed] (a linear congruential generator), so the replay of a layer and the PNG are exact.
     */
    fun airbrushDabs(pts: List<Pt>, size: Float, seed: Long = 1865L): List<Dab> {
        var s = seed xor 0x5DEECE66DL
        fun next(): Float { s = (s * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1); return (s ushr 24).toFloat() / (1L shl 24).toFloat() }
        val out = ArrayList<Dab>()
        val r = (size * 0.06f).coerceAtLeast(0.7f)
        for (p in pts) {
            val n = ((6 + size / 4f) * p.p.coerceIn(0.1f, 1f)).toInt().coerceIn(1, 40)
            repeat(n) {
                val d = size * sqrt(next())
                val a = 2.0 * PI * next()
                out.add(Dab(p.x + (d * cos(a)).toFloat(), p.y + (d * sin(a)).toFloat(), r))
            }
        }
        return out
    }

    /** ARGB linear blend: [t] 0 is [a], 1 is [b]. Smudge carries the colour under the finger with it this way. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(sh: Int): Int { val x = (a ushr sh) and 0xFF; val y = (b ushr sh) and 0xFF; return (x + (y - x) * k + 0.5f).toInt().coerceIn(0, 255) }
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** How much of the newly touched colour the smudge picks up per dab. */
    const val SMUDGE_PICKUP = 0.35f
}

/** #1865 the colour wheel: hue around the ring (0 at three o'clock, clockwise on screen, as a sweep gradient draws it), saturation outwards. */
object ColorWheel {
    /** Hue (0..360) and saturation (0..1) at the screen point ([x], [y]) of a wheel centred at ([cx], [cy]) with [radius]. */
    fun hueSat(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val dx = x - cx
        val dy = y - cy
        var h = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (h < 0f) h += 360f
        val sat = (hypot(dx, dy) / radius.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (h % 360f) to sat
    }

    /** The screen point of ([hue], [sat]) on that wheel: the inverse of [hueSat], used to draw the picker's thumb. */
    fun pointOf(hue: Float, sat: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
        val a = Math.toRadians(hue.toDouble())
        val d = sat.coerceIn(0f, 1f) * radius
        return (cx + (d * cos(a)).toFloat()) to (cy + (d * sin(a)).toFloat())
    }
}

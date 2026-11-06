package com.diegonmarcos.superapp.vision

import kotlin.math.pow

/** A declared colour look: per-channel gain and lift, a contrast about mid-grey, a gamma, and an optional mono pass. */
data class LutDecl(
    val gamma: Float, val contrast: Float,
    val gain: List<Float>, val lift: List<Float>, val mono: Boolean,
)

/** A real look-up table: 256 entries per channel, applied to ARGB pixels. */
class ColorLut(val r: IntArray, val g: IntArray, val b: IntArray, val mono: Boolean) {
    /** Maps every pixel in place (alpha untouched). */
    fun apply(px: IntArray) {
        for (i in px.indices) {
            val c = px[i]
            var rr = (c shr 16) and 255
            var gg = (c shr 8) and 255
            var bb = c and 255
            if (mono) { val y = (299 * rr + 587 * gg + 114 * bb) / 1000; rr = y; gg = y; bb = y }
            px[i] = (c and 0xFF000000.toInt()) or (r[rr] shl 16) or (g[gg] shl 8) or b[bb]
        }
    }

    companion object {
        val IDENTITY = LutDecl(1f, 1f, listOf(1f, 1f, 1f), listOf(0f, 0f, 0f), false)

        fun from(d: LutDecl): ColorLut {
            fun channel(gain: Float, lift: Float) = IntArray(256) { i ->
                var v = i / 255f
                v = (v - 0.5f) * d.contrast + 0.5f
                v = v * gain + lift
                v = v.coerceIn(0f, 1f).toDouble().pow(1.0 / d.gamma).toFloat()
                Math.round(v.coerceIn(0f, 1f) * 255f)
            }
            return ColorLut(channel(d.gain[0], d.lift[0]), channel(d.gain[1], d.lift[1]), channel(d.gain[2], d.lift[2]), d.mono)
        }
    }
}

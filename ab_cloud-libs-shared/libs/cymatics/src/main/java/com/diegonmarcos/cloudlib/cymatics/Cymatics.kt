package com.diegonmarcos.cloudlib.cymatics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * Cymatics: the Chladni figure of a square plate driven at a frequency. The plate's mode (n, m) is picked from the frequency (one mode
 * per semitone, ordered by n^2 + m^2 as the plate's resonances are), and between two semitones the two modes are blended, so the pattern is
 * the same every time for one frequency, different for two different frequencies, and symmetric (mirror in x, in y and across the diagonal).
 * The sand gathers on the nodal lines, where the amplitude is near zero.
 *
 * #1892 moved here, unchanged, from Cloud Canvas Play (#1865, audio/Studio.kt) so Cloud Calc's sound meter draws the same plate.
 */
object Cymatics {
    /** The plate modes (n, m) with n > m >= 1, ordered by n^2 + m^2, then n. 128 of them: one per MIDI-range semitone. */
    val MODES: List<Pair<Int, Int>> = buildList {
        for (n in 2..40) for (m in 1 until n) add(n to m)
    }.sortedWith(compareBy<Pair<Int, Int>> { it.first * it.first + it.second * it.second }.thenBy { it.first }).take(128)

    /** Fractional semitones above A0 (27.5 Hz); the mode index runs on it. */
    fun semitonesAboveA0(freq: Double): Double = 12 * ln(freq.coerceAtLeast(1e-6) / 27.5) / ln(2.0)

    /** The mode index (fractional) of a frequency: semitones above A0, clamped to the table. */
    fun modeIndex(freq: Double): Double = semitonesAboveA0(freq).coerceIn(0.0, (MODES.size - 1).toDouble())

    /** The plate mode (n, m) a frequency sits on (the table entry at the floor of its fractional index). */
    fun modeOf(freq: Double): Pair<Int, Int> = MODES[Math.floor(modeIndex(freq)).toInt()]

    /** The symmetric Chladni amplitude of mode (n, m) at (x, y) in [-1, 1]^2: cos(n pi x) cos(m pi y) + cos(m pi x) cos(n pi y). */
    fun amplitude(n: Int, m: Int, x: Double, y: Double): Double =
        cos(n * PI * x) * cos(m * PI * y) + cos(m * PI * x) * cos(n * PI * y)

    /**
     * The |amplitude| of the plate at [freq] on a [size] x [size] grid (row-major, normalised to 0..1, 0 on a nodal line).
     * The phase [t] (0..1) animates the plate without moving its nodes: the standing wave only breathes.
     */
    fun pattern(freq: Double, size: Int, t: Double = 0.0): FloatArray = blend(listOf(freq to 1.0), size, t)

    /**
     * The plate driven by several frequencies at once (Looping Play: the dominant frequencies of the music, weighted by their level):
     * the weighted sum of each frequency's figure, normalised. Each frequency blends the two modes around its fractional index.
     */
    fun blend(freqs: List<Pair<Double, Double>>, size: Int, t: Double = 0.0): FloatArray {
        val out = FloatArray(size * size)
        if (size <= 0 || freqs.isEmpty()) return out
        val breathe = 0.85 + 0.15 * sin(2 * PI * t)
        val acc = DoubleArray(size * size)
        for ((f, w) in freqs) {
            val k = modeIndex(f)
            val i0 = Math.floor(k).toInt()
            val i1 = minOf(i0 + 1, MODES.size - 1)
            val frac = k - i0
            val (n0, m0) = MODES[i0]
            val (n1, m1) = MODES[i1]
            for (r in 0 until size) {
                val y = if (size == 1) 0.0 else -1.0 + 2.0 * r / (size - 1)
                for (c in 0 until size) {
                    val x = if (size == 1) 0.0 else -1.0 + 2.0 * c / (size - 1)
                    acc[r * size + c] += w * ((1 - frac) * amplitude(n0, m0, x, y) + frac * amplitude(n1, m1, x, y))
                }
            }
        }
        var max = 0.0
        for (v in acc) max = maxOf(max, abs(v))
        if (max <= 0.0) return out
        for (i in acc.indices) out[i] = (abs(acc[i]) / max * breathe).toFloat()
        return out
    }

    /** The grid cells where the sand settles: amplitude under [threshold]. */
    fun nodes(pattern: FloatArray, threshold: Float = 0.08f): Int = pattern.count { it < threshold }
}

package com.diegonmarcos.cloudcalc.sound

import com.diegonmarcos.cloudlib.cymatics.Cymatics
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * #1892 the sound meter's Cymatics figure: the live spectrum to the Chladni plate of libs:cymatics-core (the one pattern implementation,
 * shared with Cloud Canvas Play). The dominant frequency and the strongest partials pick the plate's modes (Cymatics.modeOf), the level
 * sets the amplitude, and [Smoother] eases both between readings so the figure follows the sound without flicker. Pure, so the JVM suite
 * checks it: the same spectrum gives the same field, another dominant frequency gives another field, silence gives a flat plate.
 */
object CymaticsMap {
    /** The plate's drive: partials as (Hz, relative level 0..1), strongest first, and the overall amplitude 0..1. */
    data class Drive(val partials: List<Pair<Double, Double>>, val amplitude: Double) {
        /** The plate mode (n, m) of every partial, strongest first. */
        fun modes(): List<Pair<Int, Int>> = partials.map { Cymatics.modeOf(it.first) }
    }

    val SILENT = Drive(emptyList(), 0.0)

    /** A level at or under this (dBFS) is silence; the amplitude rises linearly to 1 at [LOUD_DB]. */
    const val QUIET_DB = -80.0
    const val LOUD_DB = -10.0

    fun amplitude(levelDb: Double): Double = ((levelDb - QUIET_DB) / (LOUD_DB - QUIET_DB)).coerceIn(0.0, 1.0)

    /**
     * Up to [count] strongest spectral peaks of [spectrumDb] (single-sided dBFS, bin k = k * sampleRate / (2 * size)) above [minHz], as
     * (Hz refined by a parabola through the peak, level relative to the strongest 0..1 in amplitude), strongest first. A peak within a
     * semitone of a stronger one is skipped, and one under [floor] of the strongest (amplitude ratio) is dropped; a spectrum with
     * nothing above [QUIET_DB] has no partials.
     */
    fun partials(spectrumDb: DoubleArray, sampleRate: Int, count: Int = 3, minHz: Double = 20.0, floor: Double = 0.1): List<Pair<Double, Double>> {
        if (spectrumDb.size < 4) return emptyList()
        val binHz = sampleRate.toDouble() / (spectrumDb.size * 2)
        val lo = maxOf(1, (minHz / binHz).toInt())
        val peaks = (lo until spectrumDb.size - 1)
            .filter { k -> spectrumDb[k] > spectrumDb[k - 1] && spectrumDb[k] >= spectrumDb[k + 1] && spectrumDb[k] > QUIET_DB }
            .sortedByDescending { spectrumDb[it] }
        val top = peaks.firstOrNull() ?: return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (k in peaks) {
            if (out.size >= count) break
            val rel = 10.0.pow((spectrumDb[k] - spectrumDb[top]) / 20)
            if (rel < floor) break
            val a = spectrumDb[k - 1]; val b = spectrumDb[k]; val c = spectrumDb[k + 1]
            val den = a - 2 * b + c
            val d = if (den < 0) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
            val hz = (k + d) * binHz
            if (out.any { abs(12 * ln(hz / it.first) / ln(2.0)) < 1.0 }) continue
            out.add(hz to rel)
        }
        return out
    }

    /** The drive of one reading: its strongest partials and its level as amplitude. */
    fun drive(spectrumDb: DoubleArray, sampleRate: Int, levelDb: Double, count: Int = 3): Drive {
        val amp = amplitude(levelDb)
        if (amp <= 0.0) return SILENT
        return Drive(partials(spectrumDb, sampleRate, count), amp)
    }

    /**
     * The plate field for [drive] on a [size] x [size] grid, 0..1 per cell, 0 on a nodal line (sand), 1 flat: the shared plate of
     * Cymatics.blend, raised toward 1 as the amplitude falls (field = plate + (1 - amplitude) * (1 - plate)). No partials or no amplitude is a
     * flat plate (all 1), so silence draws no figure.
     */
    fun field(drive: Drive, size: Int): FloatArray {
        if (drive.partials.isEmpty() || drive.amplitude <= 0.0) return FloatArray(size * size) { 1f }
        val plate = Cymatics.blend(drive.partials, size)
        val slack = (1.0 - drive.amplitude).toFloat()
        return FloatArray(plate.size) { plate[it] + slack * (1f - plate[it]) }
    }

    /**
     * Eases the drive between readings: each partial slot (strongest first) glides in log frequency and level by [alpha] per update, a
     * partial that vanishes fades out, a new one appears at its target, and the amplitude follows the level the same way. Feed it a
     * reading at a time; the same sequence always gives the same output.
     */
    class Smoother(private val alpha: Double = 0.35, private val count: Int = 3) {
        private val hz = DoubleArray(count)
        private val lv = DoubleArray(count)
        private var amp = 0.0

        fun update(target: Drive): Drive {
            amp += alpha * (target.amplitude - amp)
            if (amp < 0.01) amp = 0.0
            for (i in 0 until count) {
                val t = target.partials.getOrNull(i)
                if (t != null) {
                    if (lv[i] <= 0.0) { hz[i] = t.first; lv[i] = t.second }
                    else {
                        hz[i] = exp(ln(hz[i]) + alpha * (ln(t.first) - ln(hz[i])))
                        lv[i] += alpha * (t.second - lv[i])
                    }
                } else {
                    lv[i] *= (1 - alpha)
                    if (lv[i] < 0.02) lv[i] = 0.0
                }
            }
            val slots = (0 until count).filter { lv[it] > 0.0 }.sortedByDescending { lv[it] }
            return Drive(slots.map { hz[it] to lv[it] }, amp)
        }
    }
}

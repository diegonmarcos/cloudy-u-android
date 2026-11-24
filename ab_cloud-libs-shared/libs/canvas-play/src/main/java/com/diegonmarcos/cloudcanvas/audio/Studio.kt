package com.diegonmarcos.cloudcanvas.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * #1865 Play as a studio: the pure half (no Android type), so the JVM suite runs every function here.
 *   Pitch          note to frequency, frequency to note, note names with octave labels (C4 = MIDI 60)
 *   LoopRegion     Looping Play: the region of the loop that repeats, its timing, its wrap and the overdub merge
 *   Arrangement    the arranger's track and clip model: instrument tracks (their recorded notes as coloured clips) and audio tracks
 *                  (microphone takes as coloured clips)
 *   Cymatics       the Chladni standing-wave pattern of a frequency, and of a weighted set of dominant frequencies
 *   Spectrum       the dominant frequencies of a block of the generated music (FFT peak picking)
 */

/** Equal temperament at A4 = 440 Hz (MIDI 69). */
object Pitch {
    const val A4 = 440.0
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun freq(midi: Int, a4: Double = A4): Double = a4 * 2.0.pow((midi - 69) / 12.0)

    /** The nearest MIDI note of a frequency (fractional part rounded). */
    fun note(freq: Double, a4: Double = A4): Int = (69 + 12 * ln(freq / a4) / ln(2.0)).roundToInt()

    /** Fractional semitones above A0 (27.5 Hz); the cymatics mode index runs on it. */
    fun semitonesAboveA0(freq: Double): Double = 12 * ln(freq.coerceAtLeast(1e-6) / 27.5) / ln(2.0)

    /** C4 for 60, A#3 for 58: the octave label the keyboard prints on every C. */
    fun name(midi: Int): String = NAMES[Math.floorMod(midi, 12)] + (Math.floorDiv(midi, 12) - 1)

    fun isBlack(midi: Int): Boolean = NAMES[Math.floorMod(midi, 12)].length == 2
}

/**
 * Looping Play: the region [startBeat, endBeat) of the loop plays again and again. A transport beat (it only grows) maps to a beat inside
 * the region; [window] finds the events a block must start, wrapping the region end; [merge] folds an overdub take into a track.
 */
data class LoopRegion(val startBeat: Double, val endBeat: Double) {
    init { require(endBeat > startBeat) { "an empty loop region" } }

    val lengthBeats: Double get() = endBeat - startBeat

    /** The beat inside the region of a transport beat that started at the region start. */
    fun position(transportBeat: Double): Double = startBeat + floorModD(transportBeat, lengthBeats)

    /** How many times the region has wrapped by [transportBeat] (the pass number, 0 for the first pass). */
    fun pass(transportBeat: Double): Int = Math.floor(transportBeat / lengthBeats).toInt()

    /** True when the window [fromBeat, fromBeat+len) of transport beats crosses the region end. */
    fun wraps(fromBeat: Double, len: Double): Boolean = floorModD(fromBeat, lengthBeats) + len > lengthBeats

    /**
     * The events inside the region whose start falls in the transport window [fromBeat, fromBeat+len), with their offset in beats from the
     * window start; an event in the region is found again on every pass. Events outside the region never play.
     */
    fun window(events: List<NoteEvent>, fromBeat: Double, len: Double): List<Pair<NoteEvent, Double>> {
        if (len <= 0.0) return emptyList()
        val p0 = floorModD(fromBeat, lengthBeats)
        val out = ArrayList<Pair<NoteEvent, Double>>()
        for (e in events) {
            if (e.beat < startBeat || e.beat >= endBeat) continue
            val rel = e.beat - startBeat
            var k = rel - p0
            if (k < 0) k += lengthBeats
            while (k < len) { out.add(e to k); k += lengthBeats }
        }
        out.sortBy { it.second }
        return out
    }

    /** The region snapped to whole beats inside a loop of [loopBeats] beats, at least one beat long. */
    fun clampTo(loopBeats: Int): LoopRegion {
        val s = Math.floor(startBeat).coerceIn(0.0, loopBeats - 1.0)
        val e = Math.ceil(endBeat).coerceIn(s + 1.0, loopBeats.toDouble())
        return LoopRegion(s, e)
    }

    companion object {
        fun whole(loopBeats: Int) = LoopRegion(0.0, loopBeats.toDouble())

        private fun floorModD(a: Double, m: Double): Double { val r = a % m; return if (r < 0) r + m else r }

        /**
         * The overdub merge: [take] (the notes played during a pass) folded into [existing]. A take note that lands on a note already
         * there (same instrument and key, starts within [epsilon] beats) replaces it only when it is louder; every other note is kept;
         * notes are kept inside the region (a start past the end wraps to the region start) and the result is sorted by beat.
         */
        fun merge(existing: List<NoteEvent>, take: List<NoteEvent>, region: LoopRegion, epsilon: Double = 0.06): List<NoteEvent> {
            val out = ArrayList(existing)
            for (t0 in take) {
                val b = if (t0.beat >= region.endBeat || t0.beat < region.startBeat) region.position(t0.beat - region.startBeat) else t0.beat
                val t = t0.copy(beat = b)
                val i = out.indexOfFirst { it.inst == t.inst && it.note == t.note && abs(it.beat - t.beat) <= epsilon }
                if (i < 0) out.add(t) else if (t.vel > out[i].vel) out[i] = t
            }
            out.sortWith(compareBy<NoteEvent> { it.beat }.thenBy { it.note })
            return out
        }
    }
}

/** The arranger: tracks top to bottom, each a row of coloured clips on the timeline (in beats). */
object Arrangement {
    enum class Kind { Instrument, Audio }

    data class Clip(val trackId: Int, val startBeat: Double, val lenBeats: Double, val name: String, val color: Long, val notes: Int = 0) {
        val endBeat: Double get() = startBeat + lenBeats
    }

    data class Row(val trackId: Int, val name: String, val kind: Kind, val color: Long, val clips: List<Clip>)

    /** A microphone take: [frames] mono samples at the engine rate, starting at [startBeat] of the loop, on audio track [trackId]. */
    data class AudioTake(val id: Int, val trackId: Int, val startBeat: Double, val lenBeats: Double, val name: String)

    /** The clip colours, by track (the reference studio's bright lane colours), so a track keeps its colour as clips come and go. */
    val COLORS: List<Long> = listOf(0xFFD7F23CL, 0xFFF2468CL, 0xFF52E852L, 0xFF4FE0E8L, 0xFFFFA63DL, 0xFFA68CFFL, 0xFFFF6B5EL, 0xFF6BB8FFL)

    fun colorOf(trackId: Int): Long = COLORS[Math.floorMod(trackId - 1, COLORS.size)]

    /** Audio tracks are numbered after the instrument tracks: the first audio track is id [AUDIO_BASE] + 1. */
    const val AUDIO_BASE = 100

    /**
     * An instrument track's notes as clips: notes closer than [gapBeats] (from the end of one to the start of the next) join one clip;
     * each clip is named after the track's instrument mix and counts its notes. No note is outside a clip.
     */
    fun clipsOf(trackId: Int, name: String, events: List<NoteEvent>, gapBeats: Double = 1.0): List<Clip> {
        if (events.isEmpty()) return emptyList()
        val sorted = events.sortedBy { it.beat }
        val out = ArrayList<Clip>()
        var s = sorted[0].beat
        var e = sorted[0].beat + sorted[0].lenBeats
        var n = 1
        for (i in 1 until sorted.size) {
            val ev = sorted[i]
            if (ev.beat - e > gapBeats) {
                out.add(Clip(trackId, s, e - s, name, colorOf(trackId), n))
                s = ev.beat; e = ev.beat + ev.lenBeats; n = 1
            } else {
                e = maxOf(e, ev.beat + ev.lenBeats); n++
            }
        }
        out.add(Clip(trackId, s, e - s, name, colorOf(trackId), n))
        return out
    }

    /** What an instrument track plays, for its clip names: "Piano", "Drums + Synth saw". */
    fun instrumentName(events: List<NoteEvent>): String =
        events.map { if (it.inst == Inst.Synth) "Synth " + it.wave.label.lowercase() else it.inst.label }.distinct().joinToString(" + ").ifEmpty { "Empty" }

    /** The arranger rows: every mixer track as an instrument row, then every audio track with its takes. */
    fun rows(mixer: MixerState, takes: List<AudioTake>): List<Row> {
        val inst = mixer.tracks.map { t -> Row(t.id, t.name, Kind.Instrument, colorOf(t.id), clipsOf(t.id, instrumentName(t.events), t.events)) }
        val audio = takes.groupBy { it.trackId }.toSortedMap().map { (id, ts) ->
            Row(id, "Audio ${id - AUDIO_BASE}", Kind.Audio, colorOf(id - AUDIO_BASE + 4), ts.map { Clip(id, it.startBeat, it.lenBeats, it.name, colorOf(id - AUDIO_BASE + 4)) })
        }
        return inst + audio
    }

    /** The ruler: one tick per beat of a loop of [loopBeats], a bar label ("1", "2", ...) on every bar's first beat. */
    fun ruler(loopBeats: Int, beatsPerBar: Int = 4): List<Pair<Int, String?>> =
        (0 until loopBeats).map { b -> b to (if (b % beatsPerBar == 0) (b / beatsPerBar + 1).toString() else null) }
}

/**
 * Cymatics: the Chladni figure of a square plate driven at a frequency (one mode per semitone, blended between two; the same every time for
 * one frequency, symmetric; the sand gathers on the nodal lines). #1892 the math moved, unchanged, to libs:cymatics-core so Cloud Calc's
 * sound meter draws the same plate; this object delegates to it, so the call sites and their tests are as before.
 */
object Cymatics {
    private val core = com.diegonmarcos.cloudlib.cymatics.Cymatics

    /** The plate modes (n, m) with n > m >= 1, ordered by n^2 + m^2, then n. 128 of them: one per MIDI-range semitone. */
    val MODES: List<Pair<Int, Int>> = core.MODES

    /** The mode index (fractional) of a frequency: semitones above A0, clamped to the table. */
    fun modeIndex(freq: Double): Double = core.modeIndex(freq)

    /** The symmetric Chladni amplitude of mode (n, m) at (x, y) in [-1, 1]^2. */
    fun amplitude(n: Int, m: Int, x: Double, y: Double): Double = core.amplitude(n, m, x, y)

    /** The |amplitude| of the plate at [freq] on a [size] x [size] grid (row-major, normalised to 0..1, 0 on a nodal line). */
    fun pattern(freq: Double, size: Int, t: Double = 0.0): FloatArray = core.pattern(freq, size, t)

    /** The plate driven by several weighted frequencies at once (Looping Play): the normalised weighted sum of each figure. */
    fun blend(freqs: List<Pair<Double, Double>>, size: Int, t: Double = 0.0): FloatArray = core.blend(freqs, size, t)

    /** The grid cells where the sand settles: amplitude under [threshold]. */
    fun nodes(pattern: FloatArray, threshold: Float = 0.08f): Int = core.nodes(pattern, threshold)
}

/** The dominant frequencies of a block of the generated music: a Hann-windowed FFT and its strongest separated peaks. */
object Spectrum {
    /** In-place radix-2 FFT of ([re], [im]); the length must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0) { "the FFT length must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }

    /**
     * Up to [count] dominant frequencies of [samples] (mono, the first power-of-two length of them) at [sampleRate], as (Hz, level 0..1),
     * strongest first; peaks closer than a semitone to a stronger one are skipped, and nothing under [floor] of the strongest is reported.
     * Silence gives an empty list. The peak bin is refined by parabolic interpolation.
     */
    fun dominant(samples: FloatArray, sampleRate: Int, count: Int = 3, floor: Double = 0.1): List<Pair<Double, Double>> {
        var n = 1
        while (n * 2 <= samples.size) n *= 2
        if (n < 64) return emptyList()
        val re = DoubleArray(n) { i -> samples[i] * (0.5 - 0.5 * cos(2 * PI * i / (n - 1))) }
        val im = DoubleArray(n)
        fft(re, im)
        val mag = DoubleArray(n / 2) { k -> sqrt(re[k] * re[k] + im[k] * im[k]) }
        val lo = maxOf(1, (20.0 * n / sampleRate).toInt())
        val peaks = (lo until n / 2 - 1).filter { k -> mag[k] > mag[k - 1] && mag[k] >= mag[k + 1] }.sortedByDescending { mag[it] }
        val top = peaks.firstOrNull()?.let { mag[it] } ?: return emptyList()
        if (top < 1e-6) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (k in peaks) {
            if (out.size >= count || mag[k] < floor * top) break
            val a = mag[k - 1]; val b = mag[k]; val c = mag[k + 1]
            val d = (a - c) / (2 * (a - 2 * b + c)).let { if (it == 0.0) 1e-12 else it }
            val hz = (k + d.coerceIn(-0.5, 0.5)) * sampleRate / n
            if (out.any { abs(12 * ln(hz / it.first) / ln(2.0)) < 1.0 }) continue
            out.add(hz to mag[k] / top)
        }
        return out
    }
}

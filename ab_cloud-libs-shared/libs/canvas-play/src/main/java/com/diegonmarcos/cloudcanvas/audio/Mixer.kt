package com.diegonmarcos.cloudcanvas.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One recorded note of a loop: its position and length in BEATS inside the loop, so a tempo change re-times it. */
data class NoteEvent(val beat: Double, val inst: Inst, val note: Int, val lenBeats: Double, val vel: Float = 0.9f, val wave: Wave = Wave.Sine)

data class Track(
    val id: Int,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val events: List<NoteEvent> = emptyList(),
) {
    val name: String get() = "Track $id"
    val hasLoop: Boolean get() = events.isNotEmpty()
}

/** The mixer: 4..8 tracks, each with volume, pan, mute, solo and its loop. Pure state: the audible rule and the pan law live here. */
data class MixerState(val tracks: List<Track>) {
    val anySolo: Boolean get() = tracks.any { it.solo }

    /** A track sounds when it is not muted and, if any track is soloed, when it is one of them (a mute beats a solo on the same track). */
    fun audible(id: Int): Boolean {
        val t = tracks.firstOrNull { it.id == id } ?: return false
        return !t.muted && (!anySolo || t.solo)
    }

    /** The left and right gain of a track: volume through a constant-power pan law, zero when it is not audible. */
    fun gains(id: Int): Pair<Float, Float> {
        val t = tracks.firstOrNull { it.id == id } ?: return 0f to 0f
        if (!audible(id)) return 0f to 0f
        val a = (t.pan.coerceIn(-1f, 1f) + 1f) * (PI.toFloat() / 4f)
        return t.volume * cos(a) * SQRT2 to t.volume * sin(a) * SQRT2
    }

    private fun map(id: Int, f: (Track) -> Track) = copy(tracks = tracks.map { if (it.id == id) f(it) else it })

    fun volume(id: Int, v: Float) = map(id) { it.copy(volume = v.coerceIn(0f, 1f)) }
    fun pan(id: Int, p: Float) = map(id) { it.copy(pan = p.coerceIn(-1f, 1f)) }
    fun mute(id: Int, on: Boolean) = map(id) { it.copy(muted = on) }
    fun solo(id: Int, on: Boolean) = map(id) { it.copy(solo = on) }
    fun addEvent(id: Int, e: NoteEvent) = map(id) { it.copy(events = it.events + e) }
    /** #1865 Looping Play's overdub: the take folded into the track's notes by [LoopRegion.merge] (a louder duplicate replaces, nothing is lost). */
    fun overdub(id: Int, take: List<NoteEvent>, region: LoopRegion) = map(id) { it.copy(events = LoopRegion.merge(it.events, take, region)) }
    fun clear(id: Int) = map(id) { it.copy(events = emptyList()) }

    /** Grows or shrinks to [n] tracks (clamped to 4..8); shrinking drops the highest ids. */
    fun withCount(n: Int): MixerState {
        val c = n.coerceIn(MIN_TRACKS, MAX_TRACKS)
        return if (c <= tracks.size) copy(tracks = tracks.take(c)) else copy(tracks = tracks + (tracks.size + 1..c).map { Track(it) })
    }

    companion object {
        const val MIN_TRACKS = 4
        const val MAX_TRACKS = 8
        private const val SQRT2 = 1.41421356f
        fun initial(n: Int = MIN_TRACKS) = MixerState(emptyList()).withCount(n)
    }
}

/**
 * Tempo and loop length to frames. A loop is [bars] bars of [beatsPerBar] beats at [bpm]. #1865 Looping Play: the transport repeats the
 * region [regionStart, regionEnd) (beats; a negative end is the loop end), so by default the whole loop repeats, as before.
 */
class LoopClock(val bpm: Int, val bars: Int, val sampleRate: Int, val beatsPerBar: Int = 4, val regionStart: Double = 0.0, regionEnd: Double = -1.0) {
    val framesPerBeat: Double get() = sampleRate * 60.0 / bpm
    val loopBeats: Int get() = bars * beatsPerBar
    val loopFrames: Long get() = (loopBeats * framesPerBeat).toLong()
    val regionEnd: Double = if (regionEnd < 0) (bars * beatsPerBar).toDouble() else regionEnd
    /** The frames of one pass of the region (the whole loop by default). */
    val regionFrames: Long get() = if (regionStart == 0.0 && regionEnd == loopBeats.toDouble()) loopFrames else ((regionEnd - regionStart) * framesPerBeat).toLong()
    private val regionStartFrame: Long get() = beatToFrame(regionStart)

    fun beatToFrame(beat: Double): Long = (beat * framesPerBeat).toLong()
    fun frameToBeat(frame: Long): Double = frame / framesPerBeat

    /** The position inside the loop of a transport frame (inside the region: the transport starts at its start and wraps at its end). */
    fun loopPos(frame: Long): Long = regionStartFrame + Math.floorMod(frame, regionFrames)

    /** The beat inside the loop of a transport frame, snapped to the [grid] (in beats, 0 = no snap); a snap onto the region end wraps to its start. */
    fun recordBeat(frame: Long, grid: Double = 0.25): Double {
        var b = frameToBeat(loopPos(frame))
        if (grid > 0.0) b = Math.round(b / grid) * grid
        return if (b >= regionEnd) b - (regionEnd - regionStart) else b
    }

    /** The events whose loop position falls in the transport window [from, from+count), as (event, offset inside the window), wrapping the region end; events outside the region do not play. */
    fun window(events: List<NoteEvent>, from: Long, count: Int): List<Pair<NoteEvent, Int>> {
        val len = regionFrames
        if (len <= 0 || count <= 0) return emptyList()
        val p0 = loopPos(from) - regionStartFrame
        val out = ArrayList<Pair<NoteEvent, Int>>()
        for (e in events) {
            if (e.beat < regionStart || e.beat >= regionEnd) continue
            val ef = beatToFrame(e.beat) - regionStartFrame
            if (ef in p0 until p0 + count) out.add(e to (ef - p0).toInt())
            else if (p0 + count > len && ef + len in p0 until p0 + count) out.add(e to (ef + len - p0).toInt())
        }
        return out
    }

    /** The beat boundaries (whole beats) starting inside the window, as (beat index in the bar, offset inside the window). */
    fun clicks(from: Long, count: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var k = Math.ceil(from / framesPerBeat).toLong()
        while (true) {
            val f = (k * framesPerBeat).toLong()
            if (f >= from + count) break
            if (f >= from) out.add(Math.floorMod(k, beatsPerBar.toLong()).toInt() to (f - from).toInt())
            k++
        }
        return out
    }
}

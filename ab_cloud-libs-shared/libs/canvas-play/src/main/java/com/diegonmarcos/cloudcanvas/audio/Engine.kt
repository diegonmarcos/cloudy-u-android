package com.diegonmarcos.cloudcanvas.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Everything the transport and the mixer hold; the UI reads it, the engine thread reads and updates it. */
data class Session(
    val mixer: MixerState = MixerState.initial(),
    val bpm: Int = 100,
    val bars: Int = 2,
    val metronome: Boolean = true,
    val playing: Boolean = false,
    val recording: Boolean = false,
    val selected: Int = 1,
    val inst: Inst = Inst.Piano,
    val wave: Wave = Wave.Saw,
)

/**
 * The synth and mixer core. [render] fills a stereo block; it knows no Android class, so the live path (AudioOut) and the WAV export
 * (OfflineMix) run the same code. The UI thread calls [noteOn], [noteOff] and edits [session]; the audio thread calls [render].
 *
 * #1809 two engines play the notes: with [sf] set (the SoundFont engine, FluidSynth + GeneralUser GS) every piano, drum and synth note
 * goes to its General MIDI channel ([GmMap]: one output group per track), rendered sample-exact in segments between the block's note
 * events; with [sf] null the Classic voices of Synth.kt and Samples.kt play (#1787). Both meet in the same per-track mixer (volume, pan,
 * mute, solo) and the same master [MasterLimiter] (it replaced a tanh on every sample, which coloured every chord). The audio thread
 * allocates nothing per block: the delay and effects buffers are reused, and a stolen voice fades over 5 ms instead of being cut.
 */
class MixEngine(val sampleRate: Int) {
    val session = MutableStateFlow(Session())

    /** The SoundFont engine, or null for Classic. Set from any thread; the audio thread reads it once per block. */
    @Volatile var sf: SfBackend? = null
        set(v) { field?.allNotesOff(); field = v; sfAudible = null }
    /** The declaration [sf] was built from (channel layout, drum keys). */
    @Volatile var sfDecl: SfDecl? = null

    private class LiveNote(val on: Boolean, val inst: Inst, val wave: Wave, val note: Int, val track: Int, val vel: Float)
    private class Active(val track: Int, val voice: Voice, val key: Int = -1, var offAt: Long = Long.MAX_VALUE, var offSent: Boolean = false)
    private class Held(val startFrame: Long, val startBeat: Double, val track: Int, val inst: Inst, val wave: Wave, val note: Int, val vel: Float)

    private val live = ConcurrentLinkedQueue<LiveNote>()
    private val voices = ArrayList<Active>()
    private val fading = ArrayList<Active>()
    private val held = HashMap<Int, Held>()
    private val bufs = Array(MixerState.MAX_TRACKS + 1) { FloatArray(0) }
    private var bufLen = 0
    private var scratch = FloatArray(0)
    private val limiter = MasterLimiter(sampleRate)

    // the SoundFont side: the block's MIDI events (offset-sorted before rendering) and the planes the synth renders into
    private val sfEvents = SfEventList()
    private var planes = FloatArray(0)
    private var planeStride = 0
    private var sfAudible: BooleanArray? = null
    /** The size of the block being rendered: a voice started inside it learns its offset as blockN - the frames it is asked for. */
    private var blockN = 0

    /** The transport position in frames (advances while playing) and the always-running sample clock (voice lifetimes). */
    @Volatile var frame: Long = 0
        private set
    private var now: Long = 0
    private var recordFrom: Long = -1

    fun update(f: (Session) -> Session) = session.update(f)

    fun noteOn(inst: Inst, wave: Wave, note: Int, vel: Float = 0.9f) {
        live.add(LiveNote(true, inst, wave, note, session.value.selected, vel))
    }

    fun noteOff(inst: Inst, note: Int) {
        live.add(LiveNote(false, inst, Wave.Sine, note, session.value.selected, 0f))
    }

    fun play() = update { it.copy(playing = true) }

    fun stop() {
        update { it.copy(playing = false, recording = false) }
        frame = 0
        recordFrom = -1
    }

    /** Record one loop pass into the selected track (it starts the transport when stopped); pressing it again overdubs. */
    fun record() {
        update { it.copy(playing = true, recording = true) }
        recordFrom = frame
    }

    private fun key(inst: Inst, note: Int) = inst.ordinal * 1000 + note

    /** The voice of one note: a SoundFont note when [backend] plays, else the Classic voice. */
    private fun voiceFor(backend: SfBackend?, decl: SfDecl?, track: Int, inst: Inst, note: Int, wave: Wave, vel: Float): Voice =
        if (backend != null && decl != null) SfVoice(GmMap.channel(decl, track, inst, wave), GmMap.key(decl, inst, note), GmMap.velocity(vel), inst == Inst.Drums)
        else Voices.create(inst, note, wave, vel, sampleRate)

    /** Renders [n] stereo frames into [left] and [right] (overwritten). */
    fun render(left: FloatArray, right: FloatArray, n: Int) {
        val s = session.value
        val backend = sf
        val decl = sfDecl
        blockN = n
        val clock = LoopClock(s.bpm, s.bars, sampleRate)
        if (bufLen < n) { bufLen = n; for (i in bufs.indices) bufs[i] = FloatArray(n); scratch = FloatArray(n) }
        for (i in 0 until n) { left[i] = 0f; right[i] = 0f }
        for (b in bufs) java.util.Arrays.fill(b, 0, minOf(n, b.size), 0f)
        sfEvents.clear()

        // live notes from the UI
        while (true) {
            val ln = live.poll() ?: break
            val k = key(ln.inst, ln.note)
            if (ln.on) {
                voices.add(Active(ln.track, voiceFor(backend, decl, ln.track, ln.inst, ln.note, ln.wave, ln.vel), key = k))
                if (s.recording) held[k] = Held(frame, clock.recordBeat(frame), ln.track, ln.inst, ln.wave, ln.note, ln.vel)
            } else {
                voices.firstOrNull { it.key == k && !it.offSent }?.let { off(it, 0) }
                val h = held.remove(k)
                if (h != null) {
                    val len = (clock.frameToBeat(frame - h.startFrame)).coerceIn(0.05, clock.loopBeats.toDouble())
                    update { cur -> cur.copy(mixer = cur.mixer.addEvent(h.track, NoteEvent(h.startBeat, h.inst, h.note, len, h.vel, h.wave))) }
                }
            }
        }
        // a drum is a hit, not a held note: it is recorded at once with a fixed length and its pad release then finds nothing held
        held.entries.removeAll { (_, h) ->
            if (h.inst == Inst.Drums) {
                update { cur -> cur.copy(mixer = cur.mixer.addEvent(h.track, NoteEvent(h.startBeat, h.inst, h.note, 0.25, h.vel, h.wave))) }
                true
            } else false
        }

        if (s.playing) {
            for (t in s.mixer.tracks) {
                if (!s.mixer.audible(t.id)) continue
                for ((e, off) in clock.window(t.events, frame, n)) {
                    val v = voiceFor(backend, decl, t.id, e.inst, e.note, e.wave, e.vel)
                    voices.add(Active(t.id, DelayVoice(off, v), offAt = now + off + clock.beatToFrame(e.lenBeats)))
                }
            }
            if (s.metronome) for ((beatInBar, off) in clock.clicks(frame, n)) {
                voices.add(Active(0, DelayVoice(off, ClickVoice(beatInBar == 0, sampleRate)), offSent = true))
            }
        }

        // polyphony (#1787): at most MAX_VOICES sound at once; past that the oldest voice is stolen. #1809 a stolen Classic voice fades
        // out over 5 ms (a cut mid-waveform is a click); a stolen SoundFont note is released (FluidSynth runs its own release)
        while (voices.size > MAX_VOICES) {
            val a = voices.removeAt(0)
            if (unwrap(a.voice) is SfVoice) { if (!a.offSent) off(a, 0) }
            else fading.add(Active(a.track, FadeVoice(a.voice, sampleRate), offSent = true))
        }

        // mix every voice into its track's buffer (track 0 = the metronome, heard at unit gain)
        val iter = voices.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            if (!a.offSent && a.offAt <= now + n) off(a, (a.offAt - now).coerceIn(0L, (n - 1).toLong()).toInt())
            val buf = bufs[a.track.coerceIn(0, MixerState.MAX_TRACKS)]
            if (!a.voice.mix(buf, n)) iter.remove()
        }
        val fi = fading.iterator()
        while (fi.hasNext()) { val a = fi.next(); if (!a.voice.mix(bufs[a.track.coerceIn(0, MixerState.MAX_TRACKS)], n)) fi.remove() }

        if (backend != null) renderSoundFont(backend, s, n)

        for (t in s.mixer.tracks) {
            val (gl, gr) = s.mixer.gains(t.id)
            if (gl == 0f && gr == 0f) continue
            val b = bufs[t.id]
            for (i in 0 until n) { left[i] += b[i] * gl; right[i] += b[i] * gr }
            if (backend != null && t.id <= backend.groups) {
                val pl = (2 * (t.id - 1)) * planeStride
                val pr = pl + planeStride
                for (i in 0 until n) { left[i] += planes[pl + i] * gl; right[i] += planes[pr + i] * gr }
            }
        }
        if (backend != null) {
            // the effects returns (reverb and chorus), once for every track; a muted track sends nothing (its channels' CC7 is 0)
            val fx0 = 2 * backend.groups * planeStride
            for (k in 0 until 4 step 2) {
                val pl = fx0 + k * planeStride
                val pr = pl + planeStride
                for (i in 0 until n) { left[i] += planes[pl + i]; right[i] += planes[pr + i] }
            }
        }
        val click = bufs[0]
        for (i in 0 until n) { left[i] += click[i]; right[i] += click[i] }
        limiter.process(left, right, n)

        now += n
        if (s.playing) {
            frame += n
            // a recording lasts one loop pass
            if (s.recording && recordFrom >= 0 && frame - recordFrom >= clock.loopFrames) {
                update { it.copy(recording = false) }
                recordFrom = -1
            }
        }
    }

    private fun off(a: Active, offset: Int) {
        val v = unwrap(a.voice)
        if (v is SfVoice) v.release(offset) else a.voice.noteOff()
        a.offSent = true
    }

    private fun unwrap(v: Voice): Voice = if (v is DelayVoice) v.inner else v

    /** The block's SoundFont part: the planes cleared, then rendered in segments, each event applied at its own frame. */
    private fun renderSoundFont(backend: SfBackend, s: Session, n: Int) {
        val nPlanes = 2 * backend.groups + 4
        if (planeStride < n || planes.size < nPlanes * planeStride) { planeStride = max(n, planeStride); planes = FloatArray(nPlanes * planeStride) }
        for (p in 0 until nPlanes) java.util.Arrays.fill(planes, p * planeStride, p * planeStride + n, 0f)
        syncAudible(backend, s)
        sfEvents.sort()
        var at = 0
        var ei = 0
        while (at < n) {
            while (ei < sfEvents.size && sfEvents.offset[ei] <= at) {
                if (sfEvents.on[ei]) backend.noteOn(sfEvents.chan[ei], sfEvents.key[ei], sfEvents.vel[ei]) else backend.noteOff(sfEvents.chan[ei], sfEvents.key[ei])
                ei++
            }
            val next = if (ei < sfEvents.size) sfEvents.offset[ei] else n
            val len = (minOf(next, n) - at).coerceAtLeast(1)
            backend.process(planes, planeStride, at, len)
            at += len
        }
        while (ei < sfEvents.size) { if (sfEvents.on[ei]) backend.noteOn(sfEvents.chan[ei], sfEvents.key[ei], sfEvents.vel[ei]) else backend.noteOff(sfEvents.chan[ei], sfEvents.key[ei]); ei++ }
    }

    /** A track the mixer silences (mute, or another track's solo) gets channel volume 0 on all its channels, so it feeds no reverb either. */
    private fun syncAudible(backend: SfBackend, s: Session) {
        val decl = sfDecl ?: return
        val prev = sfAudible ?: BooleanArray(backend.groups) { true }.also { a -> for (t in 1..backend.groups) setVolume(backend, decl, t, true); sfAudible = a }
        for (t in 1..backend.groups) {
            val a = t > s.mixer.tracks.size || s.mixer.audible(t)
            if (a != prev[t - 1]) { prev[t - 1] = a; setVolume(backend, decl, t, a) }
        }
    }

    private fun setVolume(backend: SfBackend, decl: SfDecl, track: Int, audible: Boolean) {
        for (k in decl.kinds.indices) backend.cc(k * decl.groups + track - 1, 7, if (audible) 100 else 0)
    }

    /** The voices sounding now (live, scheduled and the metronome's). */
    val voiceCount: Int get() = voices.size

    companion object {
        /** The engine's polyphony: the most voices mixed at once. */
        const val MAX_VOICES = 64
    }

    /** One SoundFont note: its note-on goes out the first time it is mixed (at its frame in the block), its note-off at [release]. */
    private inner class SfVoice(val chan: Int, val key: Int, val vel: Int, val drum: Boolean) : Voice {
        private var started = false
        private var released = false
        override fun mix(out: FloatArray, n: Int): Boolean {
            if (!started) { started = true; sfEvents.add(blockN - n, true, chan, key, vel) }
            return !released
        }
        fun release(offset: Int) {
            if (released) return
            released = true
            // a drum hit is not damped by its pad release: GM kits ring out (their own note-off is ignored by most kits anyway)
            if (started && !drum) sfEvents.add(offset, false, chan, key, 0)
            else if (!started) { started = true }
        }
    }

    /** Starts [inner] after [delay] silent samples of the current block (#1809: through the engine's reused scratch buffer, no allocation). */
    private inner class DelayVoice(private var delay: Int, val inner: Voice) : Voice {
        override fun noteOff() = inner.noteOff()
        override fun mix(out: FloatArray, n: Int): Boolean {
            if (delay >= n) { delay -= n; return true }
            val d = delay
            delay = 0
            if (d == 0) return inner.mix(out, n)
            val tmp = scratch
            java.util.Arrays.fill(tmp, 0, n - d, 0f)
            val alive = inner.mix(tmp, n - d)
            for (i in 0 until n - d) out[d + i] += tmp[i]
            return alive
        }
    }
}

/** The block's SoundFont note events, offset-sorted before the segments are rendered. Parallel arrays: no object per event. */
internal class SfEventList {
    var size = 0
        private set
    var offset = IntArray(64); var on = BooleanArray(64); var chan = IntArray(64); var key = IntArray(64); var vel = IntArray(64)

    fun clear() { size = 0 }

    fun add(o: Int, isOn: Boolean, c: Int, k: Int, v: Int) {
        if (size == offset.size) {
            val m = size * 2
            offset = offset.copyOf(m); on = on.copyOf(m); chan = chan.copyOf(m); key = key.copyOf(m); vel = vel.copyOf(m)
        }
        offset[size] = o; on[size] = isOn; chan[size] = c; key[size] = k; vel[size] = v; size++
    }

    /** A stable insertion sort by offset (a block holds a handful of events): a note-on and its note-off at one frame keep their order. */
    fun sort() {
        for (i in 1 until size) {
            var j = i
            while (j > 0 && offset[j - 1] > offset[j]) { swap(j - 1, j); j-- }
        }
    }

    private fun swap(a: Int, b: Int) {
        offset[a] = offset[b].also { offset[b] = offset[a] }; on[a] = on[b].also { on[b] = on[a] }
        chan[a] = chan[b].also { chan[b] = chan[a] }; key[a] = key[b].also { key[b] = key[a] }; vel[a] = vel[b].also { vel[b] = vel[a] }
    }
}

/** A stolen voice: [inner] keeps playing under a 5 ms linear fade to silence, then ends (no click). */
class FadeVoice(private val inner: Voice, sr: Int) : Voice {
    private val len = (sr * 0.005).toInt().coerceAtLeast(1)
    private var t = 0
    private var tmp = FloatArray(0)
    override fun mix(out: FloatArray, n: Int): Boolean {
        if (tmp.size < n) tmp = FloatArray(n)
        java.util.Arrays.fill(tmp, 0, n, 0f)
        val alive = inner.mix(tmp, n)
        for (i in 0 until n) {
            if (t >= len) return false
            out[i] += tmp[i] * (1f - t.toFloat() / len)
            t++
        }
        return alive && t < len
    }
}

/**
 * #1809 the master stage: a peak limiter with an instant attack and an 80 ms release, ceiling -1.5 dBFS. It only works when the mix
 * would go over (the gain staging keeps a dense chord below it: canvas-soundfont-render.yml); below the ceiling the signal passes
 * untouched, where the tanh it replaced bent every sample. tools/sf-render/render.cpp mirrors it line for line.
 */
class MasterLimiter(sampleRate: Int) {
    val ceiling: Float = 10f.pow(-1.5f / 20f)
    private val release = exp(-1.0 / (sampleRate * 0.08)).toFloat()
    var gain = 1f
        private set

    fun process(left: FloatArray, right: FloatArray, n: Int) {
        for (i in 0 until n) {
            val peak = max(Math.abs(left[i]), Math.abs(right[i]))
            val target = if (peak > ceiling) ceiling / peak else 1f
            gain = if (target < gain) target else target + (gain - target) * release
            left[i] *= gain; right[i] *= gain
        }
    }
}

/** Renders the mix of a session offline, bar-exact, for the WAV export. */
object OfflineMix {
    /** [repeats] passes of the loop plus a one second tail, 16-bit interleaved stereo; the metronome is left out. #1809 [sf] plays the
     *  notes when given (the SoundFont engine, a synth of its own at [sampleRate]), else the Classic voices. */
    fun render(source: Session, sampleRate: Int, repeats: Int = 1, block: Int = 512, sf: SfBackend? = null, decl: SfDecl? = null): ShortArray {
        val e = MixEngine(sampleRate)
        if (sf != null && decl != null) { e.sfDecl = decl; e.sf = sf }
        e.update { source.copy(playing = true, recording = false, metronome = false) }
        val s = e.session.value
        val loop = LoopClock(s.bpm, s.bars, sampleRate).loopFrames
        val total = loop * repeats.coerceAtLeast(1) + sampleRate
        val out = ShortArray((total * 2).toInt())
        val l = FloatArray(block)
        val r = FloatArray(block)
        var done = 0L
        while (done < total) {
            val n = minOf(block.toLong(), total - done).toInt()
            e.render(l, r, n)
            for (i in 0 until n) {
                out[((done + i) * 2).toInt()] = (l[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                out[((done + i) * 2 + 1).toInt()] = (r[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            done += n
        }
        return out
    }
}

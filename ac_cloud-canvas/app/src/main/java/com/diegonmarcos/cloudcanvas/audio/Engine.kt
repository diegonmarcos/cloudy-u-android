package com.diegonmarcos.cloudcanvas.audio

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.tanh
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
 */
class MixEngine(val sampleRate: Int) {
    val session = MutableStateFlow(Session())

    private class LiveNote(val on: Boolean, val inst: Inst, val wave: Wave, val note: Int, val track: Int, val vel: Float)
    private class Active(val track: Int, val voice: Voice, val key: Int = -1, var offAt: Long = Long.MAX_VALUE, var offSent: Boolean = false)
    private class Held(val startFrame: Long, val startBeat: Double, val track: Int, val inst: Inst, val wave: Wave, val note: Int, val vel: Float)

    private val live = ConcurrentLinkedQueue<LiveNote>()
    private val voices = ArrayList<Active>()
    private val held = HashMap<Int, Held>()
    private val clickVoices = ArrayList<Voice>()
    private val bufs = Array(MixerState.MAX_TRACKS + 1) { FloatArray(0) }
    private var bufLen = 0

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

    /** Renders [n] stereo frames into [left] and [right] (overwritten). */
    fun render(left: FloatArray, right: FloatArray, n: Int) {
        val s = session.value
        val clock = LoopClock(s.bpm, s.bars, sampleRate)
        if (bufLen < n) { bufLen = n; for (i in bufs.indices) bufs[i] = FloatArray(n) }
        for (i in 0 until n) { left[i] = 0f; right[i] = 0f }
        for (b in bufs) java.util.Arrays.fill(b, 0, minOf(n, b.size), 0f)

        // live notes from the UI
        while (true) {
            val ln = live.poll() ?: break
            val k = key(ln.inst, ln.note)
            if (ln.on) {
                voices.add(Active(ln.track, Voices.create(ln.inst, ln.note, ln.wave, ln.vel, sampleRate), key = k))
                if (s.recording) held[k] = Held(frame, clock.recordBeat(frame), ln.track, ln.inst, ln.wave, ln.note, ln.vel)
            } else {
                voices.firstOrNull { it.key == k && !it.offSent }?.let { it.voice.noteOff(); it.offSent = true }
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
                    val v = Voices.create(e.inst, e.note, e.wave, e.vel, sampleRate)
                    voices.add(Active(t.id, DelayVoice(off, v), offAt = now + off + clock.beatToFrame(e.lenBeats)))
                }
            }
            if (s.metronome) for ((beatInBar, off) in clock.clicks(frame, n)) {
                voices.add(Active(0, DelayVoice(off, ClickVoice(beatInBar == 0, sampleRate)), offSent = true))
            }
        }

        // polyphony (#1787): at most MAX_VOICES sound at once; past that the oldest voice is stolen (a sampled piano note rings for
        // seconds, so a fast run or a held chord would otherwise pile up without bound)
        while (voices.size > MAX_VOICES) voices.removeAt(0)

        // mix every voice into its track's buffer (track 0 = the metronome, heard at unit gain)
        val iter = voices.iterator()
        while (iter.hasNext()) {
            val a = iter.next()
            if (!a.offSent && a.offAt <= now + n) { a.voice.noteOff(); a.offSent = true }
            val buf = bufs[a.track.coerceIn(0, MixerState.MAX_TRACKS)]
            if (!a.voice.mix(buf, n)) iter.remove()
        }
        for (t in s.mixer.tracks) {
            val (gl, gr) = s.mixer.gains(t.id)
            if (gl == 0f && gr == 0f) continue
            val b = bufs[t.id]
            for (i in 0 until n) { left[i] += b[i] * gl; right[i] += b[i] * gr }
        }
        val click = bufs[0]
        for (i in 0 until n) { left[i] += click[i]; right[i] += click[i]; left[i] = tanh(left[i]); right[i] = tanh(right[i]) }

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

    /** The voices sounding now (live, scheduled and the metronome's). */
    val voiceCount: Int get() = voices.size

    companion object {
        /** The engine's polyphony: the most voices mixed at once. */
        const val MAX_VOICES = 64
    }

    /** Starts [inner] after [delay] silent samples of the current block. */
    private class DelayVoice(private var delay: Int, private val inner: Voice) : Voice {
        override fun noteOff() = inner.noteOff()
        override fun mix(out: FloatArray, n: Int): Boolean {
            if (delay >= n) { delay -= n; return true }
            val d = delay
            delay = 0
            if (d == 0) return inner.mix(out, n)
            val tmp = FloatArray(n - d)
            val alive = inner.mix(tmp, n - d)
            for (i in tmp.indices) out[d + i] += tmp[i]
            return alive
        }
    }
}

/** Renders the mix of a session offline, bar-exact, for the WAV export. */
object OfflineMix {
    /** [repeats] passes of the loop plus a one second tail, 16-bit interleaved stereo; the metronome is left out. */
    fun render(source: Session, sampleRate: Int, repeats: Int = 1, block: Int = 512): ShortArray {
        val e = MixEngine(sampleRate)
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

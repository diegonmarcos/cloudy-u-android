package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.LoopClock
import com.diegonmarcos.cloudcanvas.audio.MixEngine
import com.diegonmarcos.cloudcanvas.audio.MixerState
import com.diegonmarcos.cloudcanvas.audio.NoteEvent
import com.diegonmarcos.cloudcanvas.audio.OfflineMix
import com.diegonmarcos.cloudcanvas.audio.Session
import com.diegonmarcos.cloudcanvas.audio.WavEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayTest {
    // ── mixer state: mute / solo ───────────────────────────────────────────
    @Test fun trackCountIsClampedToFourToEight() {
        assertEquals(4, MixerState.initial().tracks.size)
        assertEquals(4, MixerState.initial(1).tracks.size)
        assertEquals(8, MixerState.initial(20).tracks.size)
        assertEquals(4, MixerState.initial(8).withCount(2).tracks.size)
        assertEquals(6, MixerState.initial(4).withCount(6).tracks.map { it.id }.last())
    }

    @Test fun muteSilencesATrack() {
        val m = MixerState.initial().mute(2, true)
        assertFalse(m.audible(2))
        assertTrue(m.audible(1))
        assertEquals(0f to 0f, m.gains(2))
    }

    @Test fun soloSilencesEveryOtherTrack() {
        val m = MixerState.initial().solo(3, true)
        assertTrue(m.audible(3))
        assertFalse(m.audible(1)); assertFalse(m.audible(4))
        val two = m.solo(1, true)
        assertTrue(two.audible(1) && two.audible(3) && !two.audible(2))
        val off = two.solo(1, false).solo(3, false)
        assertTrue(off.tracks.all { off.audible(it.id) })
    }

    @Test fun aMuteBeatsASoloOnTheSameTrack() {
        val m = MixerState.initial().solo(2, true).mute(2, true)
        assertFalse(m.audible(2))
        assertFalse(m.audible(1))
    }

    @Test fun panLawIsConstantPowerAndUnityAtCentre() {
        val c = MixerState.initial().gains(1)
        assertEquals(0.8f, c.first, 0.001f); assertEquals(0.8f, c.second, 0.001f)
        val left = MixerState.initial().pan(1, -1f).gains(1)
        assertEquals(0f, left.second, 0.001f)
        val q = MixerState.initial().pan(1, 0.5f).gains(1)
        assertEquals(0.64f, q.first * q.first + q.second * q.second, 0.01f)
        assertTrue(q.second > q.first)
    }

    @Test fun volumeAndPanAreClamped() {
        val m = MixerState.initial().volume(1, 5f).pan(1, -9f)
        assertEquals(1f, m.tracks[0].volume, 0f); assertEquals(-1f, m.tracks[0].pan, 0f)
    }

    // ── loop recorder timing model ─────────────────────────────────────────
    private val clock = LoopClock(bpm = 120, bars = 2, sampleRate = 48000)

    @Test fun loopLengthFromTempoAndBars() {
        assertEquals(24000.0, clock.framesPerBeat, 0.0)
        assertEquals(8, clock.loopBeats)
        assertEquals(192000L, clock.loopFrames)
        assertEquals(192000L, LoopClock(60, 1, 48000).loopFrames)
    }

    @Test fun recordedPositionWrapsTheLoopAndSnapsToTheGrid() {
        assertEquals(1.0, clock.recordBeat(24000), 1e-9)
        assertEquals(1.0, clock.recordBeat(24000 + 192000), 1e-9)
        assertEquals(1.25, clock.recordBeat(24000 + 5000), 1e-9)
        assertEquals(1.0, clock.recordBeat(24000 + 2000), 1e-9)
        // a hit just before the loop end snaps onto the loop start
        assertEquals(0.0, clock.recordBeat(192000 - 1000), 1e-9)
        assertEquals(1.0 / 3, clock.recordBeat(8000, 0.0), 1e-9)
    }

    @Test fun tempoChangeRetimesEventsBecauseTheyAreStoredInBeats() {
        val e = NoteEvent(2.0, Inst.Piano, 60, 1.0)
        assertEquals(48000L, clock.beatToFrame(e.beat))
        assertEquals(96000L, LoopClock(60, 2, 48000).beatToFrame(e.beat))
    }

    @Test fun windowFindsEventsAndWrapsTheLoopEnd() {
        val a = NoteEvent(0.0, Inst.Drums, 0, 0.25)
        val b = NoteEvent(1.0, Inst.Drums, 1, 0.25)
        val got = clock.window(listOf(a, b), 23000, 2000)
        assertEquals(1, got.size)
        assertEquals(b, got[0].first); assertEquals(1000, got[0].second)
        // a window straddling the loop end sees the event at beat 0 of the next pass
        val wrap = clock.window(listOf(a), 191000, 2000)
        assertEquals(1, wrap.size); assertEquals(1000, wrap[0].second)
        // the same window one loop later is the same
        assertEquals(1, clock.window(listOf(a), 191000 + 192000, 2000).size)
        assertTrue(clock.window(listOf(a, b), 30000, 1000).isEmpty())
    }

    @Test fun everyEventSoundsExactlyOncePerPass() {
        val ev = listOf(0.0, 0.5, 3.75, 7.99).map { NoteEvent(it, Inst.Drums, 0, 0.25) }
        var hits = 0
        var f = 0L
        while (f < clock.loopFrames) { hits += clock.window(ev, f, 1000).size; f += 1000 }
        assertEquals(4, hits)
    }

    @Test fun metronomeClicksOnEveryBeatWithAnAccentOnTheBar() {
        val c = clock.clicks(0, 48000)
        assertEquals(listOf(0 to 0, 1 to 24000), c)
        assertEquals(listOf(3 to 0), clock.clicks(72000, 1000))
        assertTrue(clock.clicks(1, 1000).isEmpty())
    }

    @Test fun liveNotesAreRecordedIntoTheSelectedTrackAtTheirLoopPosition() {
        val e = MixEngine(48000)
        e.update { it.copy(bpm = 120, bars = 2, metronome = false, selected = 2) }
        val l = FloatArray(1000); val r = FloatArray(1000)
        e.record()
        repeat(24) { e.render(l, r, 1000) }        // 24000 frames = beat 1
        e.noteOn(Inst.Drums, com.diegonmarcos.cloudcanvas.audio.Wave.Sine, 1)
        e.render(l, r, 1000)
        e.render(l, r, 1000)
        val events = e.session.value.mixer.tracks[1].events
        assertEquals(1, events.size)
        assertEquals(1.0, events[0].beat, 1e-9)
        assertTrue(e.session.value.mixer.tracks[0].events.isEmpty())
    }

    @Test fun recordingEndsAfterOneLoopPass() {
        val e = MixEngine(48000)
        e.update { it.copy(bpm = 120, bars = 1, metronome = false) }
        e.record()
        val l = FloatArray(1000); val r = FloatArray(1000)
        repeat(95) { e.render(l, r, 1000) }
        assertTrue(e.session.value.recording)
        repeat(3) { e.render(l, r, 1000) }
        assertFalse(e.session.value.recording)
        assertTrue(e.session.value.playing)
    }

    // ── WAV encoder ────────────────────────────────────────────────────────
    private fun le32(b: ByteArray, at: Int) = (0..3).sumOf { (b[at + it].toInt() and 0xFF) shl (8 * it) }
    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    @Test fun wavHeaderAndSamples() {
        val pcm = shortArrayOf(0, 1000, -1000, 32767, -32768, 5)
        val w = WavEncoder.encode(pcm, 44100, 2)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4))
        assertEquals("fmt ", String(w, 12, 4)); assertEquals("data", String(w, 36, 4))
        assertEquals(44 + 12, w.size)
        assertEquals(w.size - 8, le32(w, 4))
        assertEquals(16, le32(w, 16)); assertEquals(1, le16(w, 20)); assertEquals(2, le16(w, 22))
        assertEquals(44100, le32(w, 24)); assertEquals(44100 * 4, le32(w, 28)); assertEquals(4, le16(w, 32)); assertEquals(16, le16(w, 34))
        assertEquals(12, le32(w, 40))
        assertEquals(1000, le16(w, 46).toShort().toInt())
        assertEquals(-32768, le16(w, 52).toShort().toInt())
    }

    @Test fun emptyWavIsJustTheHeader() {
        val w = WavEncoder.encode(ShortArray(0), 48000, 1)
        assertEquals(44, w.size); assertEquals(0, le32(w, 40))
    }

    // ── offline render (the export path) honours mute and solo ─────────────
    private fun loopSession(): Session {
        val ev = NoteEvent(0.0, Inst.Piano, 69, 2.0)
        val m = MixerState.initial().addEvent(1, ev)
        return Session(mixer = m, bpm = 120, bars = 1, metronome = false)
    }

    private fun peak(p: ShortArray) = p.maxOf { Math.abs(it.toInt()) }

    @Test fun offlineMixLengthAndSound() {
        val pcm = OfflineMix.render(loopSession(), 8000, repeats = 1)
        assertEquals((8000L * 2 + 8000) * 2, pcm.size.toLong())
        assertTrue(peak(pcm) > 500)
    }

    @Test fun offlineMixIsSilentWhenTheTrackIsMutedOrAnotherIsSoloed() {
        val s = loopSession()
        assertEquals(0, peak(OfflineMix.render(s.copy(mixer = s.mixer.mute(1, true)), 8000)))
        assertEquals(0, peak(OfflineMix.render(s.copy(mixer = s.mixer.solo(2, true)), 8000)))
        assertTrue(peak(OfflineMix.render(s.copy(mixer = s.mixer.solo(1, true)), 8000)) > 500)
    }
}

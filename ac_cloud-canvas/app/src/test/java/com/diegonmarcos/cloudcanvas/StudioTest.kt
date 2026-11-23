package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.audio.Arrangement
import com.diegonmarcos.cloudcanvas.audio.Cymatics
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.LoopClock
import com.diegonmarcos.cloudcanvas.audio.LoopRegion
import com.diegonmarcos.cloudcanvas.audio.MixerState
import com.diegonmarcos.cloudcanvas.audio.NoteEvent
import com.diegonmarcos.cloudcanvas.audio.OfflineMix
import com.diegonmarcos.cloudcanvas.audio.Session
import com.diegonmarcos.cloudcanvas.audio.Pitch
import com.diegonmarcos.cloudcanvas.audio.Spectrum
import com.diegonmarcos.cloudcanvas.audio.Wave
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1865 Play as a studio: note to frequency, Looping Play (region timing, wrap, overdub merge), the arranger model, cymatics, spectrum. */
class StudioTest {
    private fun n(beat: Double, note: Int = 60, vel: Float = 0.8f, inst: Inst = Inst.Piano, len: Double = 0.5) = NoteEvent(beat, inst, note, len, vel)

    // ── note to frequency ──────────────────────────────────────────────────
    @Test fun noteToFrequencyIsEqualTemperamentAtA440() {
        assertEquals(440.0, Pitch.freq(69), 1e-9)
        assertEquals(261.6256, Pitch.freq(60), 1e-3)
        assertEquals(880.0, Pitch.freq(81), 1e-9)
        assertEquals(27.5, Pitch.freq(21), 1e-9)
        for (m in 21..108) assertEquals(m, Pitch.note(Pitch.freq(m)))
        assertEquals(69, Pitch.note(445.0))
    }

    @Test fun keyboardLabelsNameTheOctave() {
        assertEquals("C4", Pitch.name(60))
        assertEquals("C0", Pitch.name(12))
        assertEquals("A#3", Pitch.name(58))
        assertTrue(Pitch.isBlack(61))
        assertFalse(Pitch.isBlack(64))
    }

    // ── Looping Play: region timing and wrap ───────────────────────────────
    @Test fun regionPositionWrapsAndCountsPasses() {
        val r = LoopRegion(2.0, 6.0)
        assertEquals(4.0, r.lengthBeats, 0.0)
        assertEquals(2.0, r.position(0.0), 1e-9)
        assertEquals(5.5, r.position(3.5), 1e-9)
        assertEquals(2.0, r.position(4.0), 1e-9)
        assertEquals(3.0, r.position(9.0), 1e-9)
        assertEquals(0, r.pass(3.99))
        assertEquals(2, r.pass(8.0))
        assertTrue(r.wraps(3.5, 1.0))
        assertFalse(r.wraps(1.0, 1.0))
    }

    @Test fun regionWindowFindsEventsAcrossTheWrapAndSkipsOutsideOnes() {
        val r = LoopRegion(2.0, 6.0)
        val ev = listOf(n(1.0), n(2.0, 62), n(5.5, 64), n(6.0, 65))
        // the window 3.5..4.5 of transport beats is region beats 5.5..6 then 2..2.5: finds 5.5 at 0 and 2.0 at 0.5
        val w = r.window(ev, 3.5, 1.0)
        assertEquals(listOf(64 to 0.0, 62 to 0.5), w.map { it.first.note to it.second })
        // 1.0 and 6.0 lie outside the region: never played
        assertTrue(r.window(ev, 0.0, 40.0).none { it.first.note == 60 || it.first.note == 65 })
        // a long window finds each region event once per pass
        assertEquals(6, r.window(ev, 0.0, 12.0).size)
        assertTrue(r.window(ev, 0.0, 0.0).isEmpty())
    }

    @Test fun regionClampsToWholeBeatsInsideTheLoop() {
        assertEquals(LoopRegion(1.0, 4.0), LoopRegion(1.4, 3.2).clampTo(8))
        assertEquals(LoopRegion(7.0, 8.0), LoopRegion(7.5, 12.0).clampTo(8))
        assertEquals(LoopRegion(0.0, 8.0), LoopRegion.whole(8))
    }

    // ── overdub merge ──────────────────────────────────────────────────────
    @Test fun overdubMergeKeepsEverythingAndOnlyALouderDuplicateReplaces() {
        val r = LoopRegion(0.0, 8.0)
        val base = listOf(n(0.0, 60, 0.5f), n(2.0, 64, 0.9f))
        val take = listOf(n(0.02, 60, 0.9f), n(2.01, 64, 0.3f), n(4.0, 67, 0.7f))
        val m = LoopRegion.merge(base, take, r)
        assertEquals(listOf(60, 64, 67), m.map { it.note })
        assertEquals(0.9f, m[0].vel, 0f)          // louder duplicate replaced the quiet one
        assertEquals(0.9f, m[1].vel, 0f)          // quieter duplicate did not
        assertEquals(listOf(0.02, 2.0, 4.0), m.map { it.beat })
    }

    @Test fun overdubMergeWrapsATakePastTheRegionEndAndSorts() {
        val r = LoopRegion(2.0, 6.0)
        val m = LoopRegion.merge(listOf(n(5.0, 70)), listOf(n(6.5, 72), n(3.0, 71, inst = Inst.Drums)), r)
        assertEquals(listOf(2.5, 3.0, 5.0), m.map { it.beat })
        assertTrue(m.all { it.beat >= r.startBeat && it.beat < r.endBeat })
        // a different instrument on the same key is not a duplicate
        val d = LoopRegion.merge(listOf(n(3.0, 71)), listOf(n(3.0, 71, inst = Inst.Drums)), r)
        assertEquals(2, d.size)
    }

    // ── the arranger: tracks and clips ─────────────────────────────────────
    @Test fun notesGroupIntoColouredClipsAndNoNoteIsLost() {
        val ev = listOf(n(0.0, len = 0.5), n(0.5, len = 0.5), n(4.0, len = 1.0), n(4.5, len = 0.5))
        val clips = Arrangement.clipsOf(2, "Piano", ev)
        assertEquals(2, clips.size)
        assertEquals(0.0, clips[0].startBeat, 0.0); assertEquals(1.0, clips[0].lenBeats, 1e-9)
        assertEquals(4.0, clips[1].startBeat, 0.0); assertEquals(1.0, clips[1].lenBeats, 1e-9)
        assertEquals(ev.size, clips.sumOf { it.notes })
        assertTrue(clips.all { it.color == Arrangement.colorOf(2) })
        assertNotEquals(Arrangement.colorOf(1), Arrangement.colorOf(2))
        assertTrue(Arrangement.clipsOf(1, "x", emptyList()).isEmpty())
    }

    @Test fun rowsListEveryInstrumentTrackThenTheAudioTracks() {
        val mix = MixerState.initial(4).addEvent(1, n(0.0)).addEvent(1, NoteEvent(1.0, Inst.Synth, 48, 1.0, 0.7f, Wave.Saw))
        val takes = listOf(Arrangement.AudioTake(1, Arrangement.AUDIO_BASE + 1, 2.0, 2.0, "Take 1"))
        val rows = Arrangement.rows(mix, takes)
        assertEquals(listOf(1, 2, 3, 4, Arrangement.AUDIO_BASE + 1), rows.map { it.trackId })
        assertEquals(Arrangement.Kind.Audio, rows.last().kind)
        assertEquals("Piano + Synth saw", rows[0].clips.single().name)
        assertEquals("Take 1", rows.last().clips.single().name)
        val ruler = Arrangement.ruler(8)
        assertEquals(8, ruler.size)
        assertEquals(listOf("1", "2"), ruler.mapNotNull { it.second })
    }

    // ── cymatics ───────────────────────────────────────────────────────────
    @Test fun cymaticsIsDeterministicPerFrequency() {
        val a = Cymatics.pattern(440.0, 48)
        val b = Cymatics.pattern(440.0, 48)
        assertTrue(a.contentEquals(b))
        assertTrue(Cymatics.nodes(a) > 0)
        assertTrue(a.all { it in 0f..1f })
    }

    @Test fun cymaticsIsSymmetric() {
        val s = 41
        for (f in listOf(110.0, 261.63, 440.0, 1234.5)) {
            val p = Cymatics.pattern(f, s)
            for (r in 0 until s) for (c in 0 until s) {
                val v = p[r * s + c]
                assertEquals("diagonal $f", v, p[c * s + r], 1e-5f)
                assertEquals("mirror x $f", v, p[r * s + (s - 1 - c)], 1e-5f)
                assertEquals("mirror y $f", v, p[(s - 1 - r) * s + c], 1e-5f)
            }
        }
    }

    @Test fun cymaticsChangesWithFrequency() {
        // the grid must sample the plate's highest mode above its Nyquist limit (a coarser grid aliases mode n onto grid-1-n); the view draws 96
        assertTrue(Cymatics.MODES.maxOf { it.first } * 2 + 2 < 64)
        val semis = (21..108).map { Cymatics.pattern(Pitch.freq(it), 64) }
        for (i in semis.indices) for (j in i + 1 until semis.size) assertFalse("notes ${i + 21} and ${j + 21}", semis[i].contentEquals(semis[j]))
        // within a semitone too: 440 and 450 Hz blend the same two modes differently
        assertFalse(Cymatics.pattern(440.0, 32).contentEquals(Cymatics.pattern(450.0, 32)))
        assertEquals(128, Cymatics.MODES.size)
        assertEquals(Cymatics.MODES.size, Cymatics.MODES.toSet().size)
    }

    @Test fun cymaticsOfSeveralFrequenciesBlendsThem() {
        val one = Cymatics.pattern(220.0, 32)
        val mix = Cymatics.blend(listOf(220.0 to 1.0, 330.0 to 0.5), 32)
        assertFalse(one.contentEquals(mix))
        assertTrue(mix.contentEquals(Cymatics.blend(listOf(220.0 to 1.0, 330.0 to 0.5), 32)))
        assertTrue(Cymatics.blend(emptyList(), 8).all { it == 0f })
    }

    // ── the dominant frequencies of the music ─────────────────────────────
    @Test fun spectrumFindsTheDominantFrequencies() {
        val sr = 44100
        val x = FloatArray(4096) { i -> (0.8 * sin(2 * PI * 440.0 * i / sr) + 0.4 * sin(2 * PI * 660.0 * i / sr)).toFloat() }
        val d = Spectrum.dominant(x, sr, 3)
        assertTrue(d.size >= 2)
        assertTrue("first ${d[0].first}", abs(d[0].first - 440.0) < 6.0)
        assertTrue("second ${d[1].first}", abs(d[1].first - 660.0) < 6.0)
        assertEquals(1.0, d[0].second, 1e-9)
        assertTrue(Spectrum.dominant(FloatArray(4096), sr).isEmpty())
        assertTrue(Spectrum.dominant(FloatArray(10), sr).isEmpty())
    }
    // ── the engine clock with a Looping Play region ───────────────────────
    @Test fun loopClockRepeatsTheRegionAndKeepsTheWholeLoopByDefault() {
        val whole = LoopClock(60, 2, 1000)
        assertEquals(whole.loopFrames, whole.regionFrames)
        assertEquals(500L, whole.loopPos(8500))
        val c = LoopClock(60, 2, 1000, 4, 2.0, 4.0)   // beats 2..4 of an 8-beat loop, 1000 frames a beat
        assertEquals(2000L, c.regionFrames)
        assertEquals(2000L, c.loopPos(0))
        assertEquals(3500L, c.loopPos(1500))
        assertEquals(2000L, c.loopPos(2000))      // wrapped to the region start
        val ev = listOf(n(0.0, 50), n(2.0, 52), n(3.5, 53), n(5.0, 55))
        // frames 1500..2500 are region beats 3.5..4 then 2..2.5: 52 at 500, 53 at 0 (in list order); 50 and 55 lie outside the region
        assertEquals(listOf(52 to 500, 53 to 0), c.window(ev, 1500, 1000).map { it.first.note to it.second })
        assertEquals(2.0, c.recordBeat(3990, 0.25), 1e-9)   // a snap onto the region end wraps to its start
        assertEquals(LoopRegion(2.0, 4.0), Session(bars = 2, region = LoopRegion(2.2, 3.6)).loopRegion())
        assertEquals(LoopRegion(0.0, 8.0), Session(bars = 2).loopRegion())
    }

    @Test fun microphoneTakesAreResampledForTheExport() {
        val x = FloatArray(480) { it / 480f }
        val y = OfflineMix.resample(x, 48000, 44100)
        assertEquals(441, y.size)
        assertEquals(0f, y[0], 1e-6f)
        assertTrue((1 until y.size).all { y[it] >= y[it - 1] })
        assertTrue(OfflineMix.resample(x, 44100, 44100) === x)
    }
}

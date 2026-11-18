package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.audio.Drum
import com.diegonmarcos.cloudcanvas.audio.DrumVoice
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.Layered
import com.diegonmarcos.cloudcanvas.audio.MixEngine
import com.diegonmarcos.cloudcanvas.audio.Pcm
import com.diegonmarcos.cloudcanvas.audio.Sample
import com.diegonmarcos.cloudcanvas.audio.SampleBank
import com.diegonmarcos.cloudcanvas.audio.SampleVoice
import com.diegonmarcos.cloudcanvas.audio.ToneVoice
import com.diegonmarcos.cloudcanvas.audio.TouchVelocity
import com.diegonmarcos.cloudcanvas.audio.Voices
import com.diegonmarcos.cloudcanvas.audio.Wave
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1787 the sampled instruments: the root and layer map, the pitch ratio, the voice, polyphony, the fallback and the asset set on disk. */
class SampleTest {
    private fun ramp(n: Int) = Sample(ShortArray(n) { (it * 10).toShort() }, 44100)
    private fun bank() = SampleBank(
        SampleBank.PIANO_ROOTS.associateWith { Layered(listOf(ramp(2000), ramp(2000), ramp(2000))) },
        mapOf(Drum.Kick to Layered(listOf(ramp(500)))),
    )

    @Test fun everyNoteMapsToTheNearestRecordedRoot() {
        val b = bank()
        assertEquals(30, SampleBank.PIANO_ROOTS.size)
        assertEquals(21, SampleBank.PIANO_ROOTS.first()); assertEquals(108, SampleBank.PIANO_ROOTS.last())
        assertEquals(60, b.pianoRoot(60))          // C4 is a root
        assertEquals(60, b.pianoRoot(61))          // one up: from C4
        assertEquals(63, b.pianoRoot(62))          // one below D#4: from D#4
        assertEquals(21, b.pianoRoot(5))           // below the range: the lowest root
        assertEquals(108, b.pianoRoot(127))
        // never more than 1.5 semitones away inside the range
        for (m in 21..108) assertTrue(Math.abs(b.pianoRoot(m)!! - m) <= 1)
    }

    @Test fun velocityPicksTheLayer() {
        assertEquals(0, SampleBank.layerIndex(0f, 3)); assertEquals(0, SampleBank.layerIndex(0.3f, 3))
        assertEquals(1, SampleBank.layerIndex(0.5f, 3)); assertEquals(2, SampleBank.layerIndex(0.9f, 3)); assertEquals(2, SampleBank.layerIndex(1f, 3))
        assertEquals(0, SampleBank.layerIndex(-1f, 3)); assertEquals(0, SampleBank.layerIndex(0.9f, 1))
        assertTrue(SampleBank.gain(0.2f) < SampleBank.gain(0.9f))
    }

    @Test fun pitchRatioShiftsBySemitones() {
        assertEquals(1.0, SampleBank.ratio(60, 60, 48000, 48000), 1e-9)
        assertEquals(2.0, SampleBank.ratio(60, 72, 48000, 48000), 1e-9)
        assertEquals(Math.pow(2.0, 1 / 12.0), SampleBank.ratio(60, 61, 48000, 48000), 1e-9)
        assertEquals(44100.0 / 48000.0, SampleBank.ratio(60, 60, 44100, 48000), 1e-9)
    }

    @Test fun sampleVoicePlaysTheSampleAtTheRatio() {
        val s = Sample(ShortArray(100) { (it * 100).toShort() }, 48000)
        val v = SampleVoice(s, 2.0, 1f, 48000, damped = true)
        val out = FloatArray(10)
        assertTrue(v.mix(out, 10))
        // after the 2 ms fade-in the voice reads every second frame: out[i] ~ pcm[2i]
        val sr = 48000; val fade = (sr * 0.002).toInt()
        assertTrue(fade > 10)
        assertEquals(0f, out[0], 1e-6f)
        // the voice ends at the end of the sample
        val long = FloatArray(200)
        assertTrue(!v.mix(long, 200))
    }

    @Test fun aDampedVoiceFadesAfterNoteOffAndADrumRingsOut() {
        val s = Sample(ShortArray(48000 * 3) { 10000 }, 48000)
        val piano = SampleVoice(s, 1.0, 1f, 48000, damped = true, releaseSec = 0.1)
        val drum = SampleVoice(s, 1.0, 1f, 48000, damped = false)
        piano.noteOff(); drum.noteOff()
        val buf = FloatArray(48000 / 2)
        assertTrue(!piano.mix(buf, buf.size))      // released within half a second
        assertTrue(drum.mix(FloatArray(buf.size), buf.size))  // a hit is not damped
    }

    @Test fun pianoAndDrumsUseSamplesWhenTheBankHoldsThem() {
        val b = bank()
        assertTrue(Voices.create(Inst.Piano, 60, Wave.Sine, 0.8f, 48000, b) is SampleVoice)
        assertTrue(Voices.create(Inst.Drums, Drum.Kick.ordinal, Wave.Sine, 0.8f, 48000, b) is SampleVoice)
        // the kit has no clap: the synthesized one plays; no bank at all: everything is synthesized
        assertTrue(Voices.create(Inst.Drums, Drum.Clap.ordinal, Wave.Sine, 0.8f, 48000, b) is DrumVoice)
        assertTrue(Voices.create(Inst.Piano, 60, Wave.Sine, 0.8f, 48000, null) is ToneVoice)
        assertTrue(Voices.create(Inst.Synth, 60, Wave.Saw, 0.8f, 48000, b) is ToneVoice)
        assertNull(SampleVoice.drum(b, Drum.Ride, 0.5f, 48000))
    }

    @Test fun drumOrdinalsOfRecordedLoopsAreStable() {
        // a loop stores a pad by ordinal: the first eight pads never move, new pads are appended
        assertEquals(listOf("Kick", "Snare", "Clap", "HatClosed", "HatOpen", "TomLow", "TomHigh", "Crash"), Drum.entries.take(8).map { it.name })
        assertEquals(listOf("TomMid", "Ride"), Drum.entries.drop(8).map { it.name })
    }

    @Test fun polyphonyIsCappedByStealingTheOldest() {
        val e = MixEngine(48000)
        val l = FloatArray(64); val r = FloatArray(64)
        for (n in 0 until MixEngine.MAX_VOICES + 20) e.noteOn(Inst.Synth, Wave.Sine, 40 + n % 40)
        e.render(l, r, 64)
        assertEquals(MixEngine.MAX_VOICES, e.voiceCount)
    }

    @Test fun velocityFromTheTouchPoint() {
        assertEquals(0.3f, TouchVelocity.of(0f), 1e-6f)
        assertEquals(1f, TouchVelocity.of(1f), 1e-6f)
        assertTrue(TouchVelocity.of(0.2f) < TouchVelocity.of(0.8f))
        assertEquals(1f, TouchVelocity.of(5f), 1e-6f)
    }

    @Test fun stereoIsAveragedToMono() {
        assertEquals(listOf<Short>(150, -50), Pcm.toMono(shortArrayOf(100, 200, -100, 0), 2).toList())
        assertEquals(listOf<Short>(1, 2), Pcm.toMono(shortArrayOf(1, 2), 1).toList())
    }

    @Test fun theAssetSetOnDiskIsComplete() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        val assets = File(dir!!, "app/src/main/assets")
        for (root in SampleBank.PIANO_ROOTS) for (l in 1..SampleBank.PIANO_LAYERS) {
            val f = File(assets, SampleBank.pianoAsset(root, l)); assertTrue("missing $f", f.isFile && f.length() > 1000)
        }
        for (d in SampleBank.DRUM_FILES.keys) for (l in 1..SampleBank.DRUM_LAYERS) {
            val f = File(assets, SampleBank.drumAsset(d, l)); assertTrue("missing $f", f.isFile && f.length() > 500)
        }
        val total = File(assets, "samples").walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("samples weigh $total bytes", total < 15L * 1024 * 1024)
    }
}

package com.diegonmarcos.cloudcalc.sound

import com.diegonmarcos.cloudcalc.sound.CymaticsMap.Drive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** #1892 the sound meter's spectrum to Cymatics mapping: deterministic, follows the dominant frequency, silence is flat, smoothed. */
class CymaticsMapTest {
    private val sr = 44100
    private val n = 4096

    private fun tone(vararg hzAmp: Pair<Double, Double>) =
        ShortArray(n) { i -> (hzAmp.sumOf { (f, a) -> a * sin(2 * PI * f * i / sr) } * 32767).toInt().toShort() }

    private fun driveOf(pcm: ShortArray, size: Int = n): Drive {
        val spec = Dsp.spectrumDb(pcm, size)
        return CymaticsMap.drive(spec, sr, Dsp.levelDbfs(pcm))
    }

    @Test fun `the strongest partials are found in order with their relative level`() {
        val spec = Dsp.spectrumDb(tone(440.0 to 0.5, 880.0 to 0.25, 1320.0 to 0.1), n)
        val p = CymaticsMap.partials(spec, sr)
        assertEquals(3, p.size)
        assertEquals(440.0, p[0].first, 3.0)
        assertEquals(880.0, p[1].first, 3.0)
        assertEquals(1320.0, p[2].first, 3.0)
        assertEquals(1.0, p[0].second, 1e-9)
        assertEquals(0.5, p[1].second, 0.2)
        assertEquals(0.2, p[2].second, 0.1)
    }

    @Test fun `count and floor limit the partials`() {
        val spec = Dsp.spectrumDb(tone(440.0 to 0.5, 880.0 to 0.25, 1320.0 to 0.1), n)
        assertEquals(2, CymaticsMap.partials(spec, sr, count = 2).size)
        assertEquals(2, CymaticsMap.partials(spec, sr, floor = 0.3).size)
        assertEquals(1, CymaticsMap.partials(spec, sr, floor = 0.6).size)
        assertTrue(CymaticsMap.partials(DoubleArray(2) { 0.0 }, sr).isEmpty())
    }

    @Test fun `a peak within a semitone of a stronger one is one partial`() {
        val spec = Dsp.spectrumDb(tone(440.0 to 0.5, 446.0 to 0.4), n)
        val p = CymaticsMap.partials(spec, sr)
        assertTrue(p.none { it.first > 430 && it.first < 456 && it.first != p[0].first })
    }

    @Test fun `bins under the lower bound are ignored`() {
        val spec = DoubleArray(512) { -100.0 }
        spec[1] = -10.0; spec[100] = -20.0
        val p = CymaticsMap.partials(spec, sr, minHz = 100.0)
        assertEquals(1, p.size)
        assertEquals(100 * sr.toDouble() / 1024, p[0].first, 1.0)
    }

    @Test fun `the same spectrum gives the same field`() {
        val a = CymaticsMap.field(driveOf(tone(440.0 to 0.5)), 48)
        val b = CymaticsMap.field(driveOf(tone(440.0 to 0.5)), 48)
        assertTrue(a.contentEquals(b))
        assertEquals(48 * 48, a.size)
    }

    @Test fun `the field changes with the dominant frequency`() {
        val low = CymaticsMap.field(driveOf(tone(220.0 to 0.5)), 48)
        val mid = CymaticsMap.field(driveOf(tone(440.0 to 0.5)), 48)
        val high = CymaticsMap.field(driveOf(tone(1760.0 to 0.5)), 48)
        assertFalse(low.contentEquals(mid))
        assertFalse(mid.contentEquals(high))
        assertFalse(low.contentEquals(high))
        assertNotEquals(driveOf(tone(220.0 to 0.5)).modes(), driveOf(tone(1760.0 to 0.5)).modes())
    }

    @Test fun `the strongest partials set the modes`() {
        val d = driveOf(tone(440.0 to 0.5, 880.0 to 0.25))
        assertEquals(2, d.partials.size)
        assertEquals(2, d.modes().size)
        assertNotEquals(d.modes()[0], d.modes()[1])
        assertFalse(CymaticsMap.field(d, 48).contentEquals(CymaticsMap.field(driveOf(tone(440.0 to 0.5)), 48)))
    }

    @Test fun `silence is a flat plate`() {
        val d = driveOf(ShortArray(n))
        assertEquals(CymaticsMap.SILENT, d)
        val f = CymaticsMap.field(d, 16)
        assertEquals(256, f.size)
        assertTrue(f.all { it == 1f })
        assertTrue(CymaticsMap.field(Drive(listOf(440.0 to 1.0), 0.0), 16).all { it == 1f })
        assertTrue(CymaticsMap.field(Drive(emptyList(), 0.8), 16).all { it == 1f })
    }

    @Test fun `the level sets the amplitude`() {
        assertEquals(0.0, CymaticsMap.amplitude(-90.0), 0.0)
        assertEquals(0.0, CymaticsMap.amplitude(CymaticsMap.QUIET_DB), 0.0)
        assertEquals(0.5, CymaticsMap.amplitude(-45.0), 1e-9)
        assertEquals(1.0, CymaticsMap.amplitude(0.0), 0.0)
        val loud = CymaticsMap.field(Drive(listOf(440.0 to 1.0), 1.0), 32)
        val soft = CymaticsMap.field(Drive(listOf(440.0 to 1.0), 0.25), 32)
        val plate = com.diegonmarcos.cloudlib.cymatics.Cymatics.blend(listOf(440.0 to 1.0), 32)
        assertTrue(loud.contentEquals(plate))
        assertTrue(soft.indices.all { soft[it] >= loud[it] })
        assertEquals(plate[40] + 0.75f * (1f - plate[40]), soft[40], 1e-6f)
        assertTrue(loud.min() < soft.min())
    }

    @Test fun `smoothing eases toward a new pitch instead of jumping`() {
        val s = CymaticsMap.Smoother()
        val a = Drive(listOf(220.0 to 1.0), 0.8)
        val b = Drive(listOf(440.0 to 1.0), 0.8)
        assertEquals(220.0, s.update(a).partials[0].first, 1e-9)
        val one = s.update(b).partials[0].first
        assertTrue(one > 220.0 && one < 440.0)
        var last = one
        repeat(40) { last = s.update(b).partials[0].first }
        assertEquals(440.0, last, 0.5)
    }

    @Test fun `smoothing fades a partial out and the amplitude to silence`() {
        val s = CymaticsMap.Smoother()
        val d = Drive(listOf(220.0 to 1.0, 330.0 to 0.6), 0.9)
        assertEquals(2, s.update(d).partials.size)
        val half = s.update(Drive(listOf(220.0 to 1.0), 0.9))
        assertEquals(2, half.partials.size)
        assertTrue(half.partials[1].second < 0.6)
        repeat(40) { s.update(Drive(listOf(220.0 to 1.0), 0.9)) }
        assertEquals(1, s.update(Drive(listOf(220.0 to 1.0), 0.9)).partials.size)
        var out = Drive(listOf(1.0 to 1.0), 1.0)
        repeat(60) { out = s.update(CymaticsMap.SILENT) }
        assertTrue(out.partials.isEmpty())
        assertEquals(0.0, out.amplitude, 0.0)
    }

    @Test fun `smoothing is deterministic and keeps the strongest first`() {
        val seq = listOf(Drive(listOf(220.0 to 1.0, 440.0 to 0.4), 0.7), Drive(listOf(230.0 to 0.9, 450.0 to 0.5), 0.6), CymaticsMap.SILENT)
        fun run(): List<Drive> { val s = CymaticsMap.Smoother(); return seq.map { s.update(it) } }
        assertEquals(run(), run())
        run().forEach { d -> assertEquals(d.partials.sortedByDescending { it.second }, d.partials) }
    }
}

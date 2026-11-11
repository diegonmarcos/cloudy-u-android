package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AudioTest {
    private val rate = 16000

    /** [ms] of a constant 16-bit LE sample. */
    private fun frame(sample: Int, ms: Int = 100): ByteArray {
        val n = rate * ms / 1000
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            out[2 * i] = (sample and 0xff).toByte()
            out[2 * i + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return out
    }

    private val voice = frame(1000)
    private val quiet = frame(0)
    private fun seg() = Segmenter(rate, 500.0, 300, 200, 2000)
    private fun Segmenter.put(f: ByteArray) = feed(f, f.size)

    @Test fun `rms reads signed little-endian samples over the given length`() {
        assertEquals(0.0, Segmenter.rms(quiet, quiet.size), 0.0)
        assertEquals(1000.0, Segmenter.rms(voice, voice.size), 1e-9)
        assertEquals(1000.0, Segmenter.rms(frame(-1000), 3200), 1e-9)
        assertEquals(258.0, Segmenter.rms(byteArrayOf(0x02, 0x01), 2), 1e-9)
        assertEquals(Math.sqrt((9.0 + 16.0) / 2), Segmenter.rms(byteArrayOf(3, 0, 4, 0), 4), 1e-9)
        assertEquals(0.0, Segmenter.rms(byteArrayOf(5), 1), 0.0)
        assertEquals(1000.0, Segmenter.rms(byteArrayOf(0xe8.toByte(), 0x03, 0, 0), 2), 1e-9)
    }

    @Test fun `level is rms over full scale, clamped`() {
        assertEquals(0f, Segmenter.level(quiet, quiet.size), 0f)
        assertEquals(1000f / 32768f, Segmenter.level(voice, voice.size), 1e-6f)
        assertEquals(1f, Segmenter.level(frame(-32768), 3200), 0f)
    }

    @Test fun `leading silence is not recorded`() {
        val s = seg()
        assertNull(s.put(quiet))
        assertNull(s.put(frame(499)))
        assertEquals(0L, s.bufferedMs)
        assertNull(s.put(frame(500)))
        assertEquals(100L, s.bufferedMs)
    }

    @Test fun `a pause after enough voice closes a segment with its trailing silence`() {
        val s = seg()
        repeat(3) { assertNull(s.put(voice)) }
        assertEquals(300L, s.bufferedMs)
        assertNull(s.put(quiet))
        assertNull(s.put(quiet))
        val out = s.put(quiet)
        assertNotNull(out)
        assertEquals(6 * 3200, out!!.size)
        assertEquals(0L, s.bufferedMs)
    }

    @Test fun `exactly the minimum voice is enough`() {
        val s = seg()
        repeat(2) { s.put(voice) }
        assertNull(s.put(quiet))
        assertNull(s.put(quiet))
        assertEquals(5 * 3200, s.put(quiet)!!.size)
    }

    @Test fun `a blip followed by a pause is dropped`() {
        val s = seg()
        s.put(voice)
        repeat(3) { assertNull(s.put(quiet)) }
        assertEquals(0L, s.bufferedMs)
        assertNull(s.put(quiet))
        assertNull(s.flush())
    }

    @Test fun `voice resets the pause`() {
        val s = seg()
        repeat(3) { s.put(voice) }
        repeat(2) { assertNull(s.put(quiet)) }
        assertNull(s.put(voice))
        repeat(2) { assertNull(s.put(quiet)) }
        assertEquals(9 * 3200, s.put(quiet)!!.size)
    }

    @Test fun `a monologue is cut at the cap`() {
        val s = seg()
        repeat(19) { assertNull(s.put(voice)) }
        assertEquals(1900L, s.bufferedMs)
        assertEquals(20 * 3200, s.put(voice)!!.size)
        assertEquals(0L, s.bufferedMs)
    }

    @Test fun `flush keeps enough voice and drops a blip`() {
        val s = seg()
        assertNull(s.flush())
        repeat(2) { s.put(voice) }
        assertEquals(2 * 3200, s.flush()!!.size)
        assertEquals(0L, s.bufferedMs)
        s.put(voice)
        assertNull(s.flush())
        assertEquals(0L, s.bufferedMs)
    }

    @Test fun `short and partial frames`() {
        val s = seg()
        assertNull(s.feed(ByteArray(1), 1))
        assertNull(s.feed(voice, 0))
        assertEquals(0L, s.bufferedMs)
        assertNull(s.feed(voice, 1600))
        assertEquals(50L, s.bufferedMs)
    }

    @Test fun `the wav header describes the pcm`() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val w = Wav.encode(pcm, 16000)
        assertEquals(48, w.size)
        fun ascii(at: Int) = String(w, at, 4, Charsets.US_ASCII)
        fun int(at: Int) = (0 until 4).sumOf { (w[at + it].toInt() and 0xff) shl (8 * it) }
        fun short(at: Int) = (w[at].toInt() and 0xff) or ((w[at + 1].toInt() and 0xff) shl 8)
        assertEquals("RIFF", ascii(0))
        assertEquals(40, int(4))
        assertEquals("WAVE", ascii(8))
        assertEquals("fmt ", ascii(12))
        assertEquals(16, int(16))
        assertEquals(1, short(20))
        assertEquals(1, short(22))
        assertEquals(16000, int(24))
        assertEquals(32000, int(28))
        assertEquals(2, short(32))
        assertEquals(16, short(34))
        assertEquals("data", ascii(36))
        assertEquals(4, int(40))
        assertArrayEquals(pcm, w.copyOfRange(44, 48))
    }

    @Test fun `stereo 8-bit fields follow the arguments`() {
        val w = Wav.encode(ByteArray(10), 8000, channels = 2, bits = 8)
        fun int(at: Int) = (0 until 4).sumOf { (w[at + it].toInt() and 0xff) shl (8 * it) }
        fun short(at: Int) = (w[at].toInt() and 0xff) or ((w[at + 1].toInt() and 0xff) shl 8)
        assertEquals(2, short(22))
        assertEquals(8000, int(24))
        assertEquals(16000, int(28))
        assertEquals(2, short(32))
        assertEquals(8, short(34))
        assertEquals(46, int(4))
    }
}

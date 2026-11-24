package com.diegonmarcos.cloudlib.cymatics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1892 parity: the Cymatics field for fixed inputs is byte-identical to what Cloud Canvas Play's own implementation (audio/Studio.kt, #1865)
 * produced BEFORE the math moved here. The reference values (an FNV-1a 64 over every float's raw bits, and three raw cells) were captured
 * from that code first, with the same operations in the same order, and are never regenerated from this module.
 */
class CymaticsParityTest {
    private fun bits(p: FloatArray, i: Int) = java.lang.Float.floatToRawIntBits(p[i]).toUInt().toString(16).padStart(8, '0')

    private fun fnv(p: FloatArray): ULong {
        var h = 0xcbf29ce484222325UL
        for (f in p) {
            val b = java.lang.Float.floatToRawIntBits(f)
            for (i in 0 until 4) { h = h xor ((b ushr (8 * i)) and 0xff).toULong(); h *= 0x100000001b3UL }
        }
        return h
    }

    private fun check(p: FloatArray, hash: ULong, a: String, b: String, c: String) {
        assertEquals(hash, fnv(p))
        assertEquals(a, bits(p, 0))
        assertEquals(b, bits(p, p.size / 2 + 3))
        assertEquals(c, bits(p, p.size - 5))
    }

    @Test fun `A4 plate on 16 cells is byte-identical to the pre-move field`() =
        check(Cymatics.pattern(440.0, 16), 0x3428ff27c87fa13dUL, "3f59999a", "3d133616", "3e919a52")

    @Test fun `A2 plate on 24 cells is byte-identical to the pre-move field`() =
        check(Cymatics.pattern(110.0, 24), 0x5436c1864398ad35UL, "3f59999a", "3e94ea4d", "3e46f337")

    @Test fun `middle C plate at phase 0_25 on 32 cells is byte-identical to the pre-move field`() =
        check(Cymatics.pattern(261.6256, 32, 0.25), 0x0121191f52615575UL, "3f800000", "3f30fc1d", "3edcddc0")

    @Test fun `1 kHz plate on 16 cells is byte-identical to the pre-move field`() =
        check(Cymatics.pattern(1000.0, 16), 0xf33a3ad7894fb4f5UL, "3f59999a", "3ca15a8b", "3d892d5c")

    @Test fun `a three-frequency blend at phase 0_6 on 32 cells is byte-identical to the pre-move field`() =
        check(Cymatics.blend(listOf(220.0 to 1.0, 330.0 to 0.5, 1000.0 to 0.3), 32, 0.6), 0x81816f0b4eaf020dUL, "3f430770", "3e58d385", "3e9968e5")

    @Test fun `the mode table is the 128 modes ordered by n2 plus m2`() {
        assertEquals(128, Cymatics.MODES.size)
        assertEquals(2 to 1, Cymatics.MODES.first())
        assertEquals(19 to 2, Cymatics.MODES.last())
        assertEquals(Cymatics.MODES.size, Cymatics.MODES.toSet().size)
    }

    @Test fun `mode numbers follow the frequency`() {
        assertEquals(Cymatics.MODES[0], Cymatics.modeOf(27.5))
        assertEquals(Cymatics.MODES[48], Cymatics.modeOf(440.0))
        assertEquals(Cymatics.MODES[127], Cymatics.modeOf(1e9))
        assertEquals(Cymatics.MODES[0], Cymatics.modeOf(0.0))
        assertEquals(48.0, Cymatics.modeIndex(440.0), 1e-9)
    }

    @Test fun `deterministic, symmetric and different per frequency`() {
        val a = Cymatics.pattern(440.0, 32)
        assertTrue(a.contentEquals(Cymatics.pattern(440.0, 32)))
        assertFalse(a.contentEquals(Cymatics.pattern(466.0, 32)))
        for (r in 0 until 32) for (c in 0 until 32) assertEquals(a[r * 32 + c], a[r * 32 + (31 - c)], 1e-5f)
        assertTrue(Cymatics.blend(emptyList(), 8).all { it == 0f })
        assertTrue(Cymatics.nodes(a) > 0)
    }
}

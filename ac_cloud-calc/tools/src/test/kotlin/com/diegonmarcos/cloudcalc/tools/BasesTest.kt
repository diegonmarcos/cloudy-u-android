package com.diegonmarcos.cloudcalc.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/** #1409 the base converter: every base 2..36, negatives, fractions and two's complement. */
class BasesTest {
    private fun conv(t: String, from: Int, to: Int) = Bases.format(Bases.parse(t, from)!!, to)

    @Test fun integers() {
        assertEquals("ff", conv("255", 10, 16)); assertEquals("11111111", conv("255", 10, 2)); assertEquals("377", conv("255", 10, 8))
        assertEquals("255", conv("FF", 16, 10)); assertEquals("z", conv("35", 10, 36)); assertEquals("10", conv("36", 10, 36))
        assertEquals("0", conv("0", 10, 2)); assertEquals("1010", conv("1_010", 2, 2)); assertEquals("12", conv("1 2", 10, 10))
        assertEquals("18446744073709551616", conv("10000000000000000", 16, 10))
    }

    @Test fun negativesAndFractions() {
        assertEquals("-ff", conv("-255", 10, 16)); assertEquals("-255", conv("-ff", 16, 10))
        assertEquals("0.1", conv("0.5", 10, 2)); assertEquals("0.8", conv("0.5", 10, 16)); assertEquals("10.625", conv("1010.101", 2, 10))
        assertEquals("-1.4", conv("-1.25", 10, 16))
        assertEquals("0.1", conv("0.1", 3, 3))
        assertEquals("0.333", Bases.format(Bases.parse("0.1", 3)!!, 10, 3).removeSuffix("…"))
        assertTrue(Bases.format(Bases.parse("0.1", 3)!!, 10, 3).endsWith("…"))
        assertEquals("0.0001100110011…", Bases.format(Bases.parse("0.1", 10)!!, 2, 13))
        assertEquals("0.1", conv("0.1…", 10, 10))
        assertEquals("5", conv("5.0", 10, 10))
    }

    @Test fun rejects() {
        assertNull(Bases.parse("2", 2)); assertNull(Bases.parse("g", 16)); assertNull(Bases.parse("", 10)); assertNull(Bases.parse("-", 10))
        assertNull(Bases.parse("1.2.3", 10)); assertNull(Bases.parse(".", 10)); assertNull(Bases.parse("1", 1)); assertNull(Bases.parse("1", 37))
        assertEquals("z", conv("z", 36, 36)); assertEquals("1", conv("1", 2, 2))
        assertEquals(Bases.Value(BigInteger.valueOf(5), BigInteger.ONE), Bases.parse(".5", 10)!!.let { Bases.Value(it.num, BigInteger.ONE) })
        assertEquals(BigInteger.TEN, Bases.parse(".5", 10)!!.den)
    }

    @Test fun twosComplement() {
        val m1 = Bases.parse("-1", 10)!!
        assertEquals("ff", Bases.twos(m1, 16, 8)); assertEquals("11111111", Bases.twos(m1, 2, 8)); assertEquals("ffff", Bases.twos(m1, 16, 16))
        assertEquals("80", Bases.twos(Bases.parse("-128", 10)!!, 16, 8)); assertNull(Bases.twos(Bases.parse("-129", 10)!!, 16, 8))
        assertEquals("7f", Bases.twos(Bases.parse("127", 10)!!, 16, 8)); assertNull(Bases.twos(Bases.parse("128", 10)!!, 16, 8))
        assertNull(Bases.twos(Bases.parse("1.5", 10)!!, 16, 8))
        assertEquals("-1", Bases.format(Bases.fromTwos("ff", 16, 8)!!, 10)); assertEquals("127", Bases.format(Bases.fromTwos("7f", 16, 8)!!, 10))
        assertEquals("-128", Bases.format(Bases.fromTwos("10000000", 2, 8)!!, 10)); assertEquals("255", Bases.format(Bases.fromTwos("ff", 16, 16)!!, 10))
        assertNull(Bases.fromTwos("1ff", 16, 8)); assertNull(Bases.fromTwos("-1", 16, 8)); assertNull(Bases.fromTwos("1.1", 2, 8)); assertNull(Bases.fromTwos("x", 16, 8))
        assertEquals("-9223372036854775808", Bases.format(Bases.fromTwos("8000000000000000", 16, 64)!!, 10))
        assertEquals(listOf(8, 16, 32, 64), Bases.BIT_WIDTHS)
        assertTrue(Bases.parse("4", 10)!!.isInteger); assertFalse(Bases.parse("4.5", 10)!!.isInteger)
    }
}

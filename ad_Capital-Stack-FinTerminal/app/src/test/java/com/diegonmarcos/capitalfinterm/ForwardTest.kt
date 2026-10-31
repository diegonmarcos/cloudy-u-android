package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The forward-rate derivation: f(s,l) = ((1+y(s+l))^(s+l) / (1+y(s))^s)^(1/l) - 1 on an interpolated curve. */
class ForwardTest {
    private val curve = listOf(1.0 to 2.0, 2.0 to 3.0, 5.0 to 4.0, 10.0 to 4.5)

    @Test fun oneYearForwardOneYearOutIsTheTextbookValue() {
        // (1.03^2 / 1.02) - 1 = 4.00980...%
        assertEquals(4.0098, Forward.rate(curve, 1.0, 1.0), 1e-3)
    }

    @Test fun aForwardFromTodayIsTheSpotYield() {
        assertEquals(2.0, Forward.rate(curve, 0.0, 1.0), 1e-9)
        assertEquals(4.0, Forward.rate(curve, 0.0, 5.0), 1e-9)
    }

    @Test fun aFlatCurveHasFlatForwards() {
        val flat = listOf(1.0 to 3.5, 10.0 to 3.5)
        for (s in listOf(0.0, 1.0, 4.0, 7.0)) assertEquals(3.5, Forward.rate(flat, s, 2.0), 1e-9)
    }

    @Test fun anUpwardCurveHasForwardsAboveSpot() {
        assertTrue(Forward.rate(curve, 2.0, 3.0) > 4.0)
        assertTrue(Forward.rate(curve, 5.0, 5.0) > 4.5)
    }

    @Test fun interpolationIsLinearAndFlatOutside() {
        assertEquals(2.5, Forward.yieldAt(curve, 1.5), 1e-12)
        assertEquals(2.0, Forward.yieldAt(curve, 0.25), 1e-12)
        assertEquals(4.5, Forward.yieldAt(curve, 30.0), 1e-12)
        assertTrue(Forward.yieldAt(emptyList(), 1.0).isNaN())
    }

    @Test fun degenerateInputsAreNaNAndTheCurveHasOnePointPerStartYear() {
        assertTrue(Forward.rate(emptyList(), 1.0, 1.0).isNaN())
        assertTrue(Forward.rate(curve, 1.0, 0.0).isNaN())
        val c = Forward.curve(curve, 1.0, 29)
        assertEquals(30, c.size)
        assertEquals(2.0, c.first().second, 1e-9)
    }

    @Test fun theSpec9TableIsTenorSpotAndTheForwardOverTheSameTenor() {
        val t = Forward.table(listOf(1.0 to 2.0, 2.0 to 3.0), 1.0)
        assertEquals(listOf(1.0, 2.0), t.map { it.first })
        assertEquals(listOf(2.0, 3.0), t.map { it.second })
        assertEquals(4.0098, t[0].third, 1e-3) // 1y forward 1y out: above the 2.0 spot by about 201 bp
    }
}

package com.diegonmarcos.superapp.notificationcenter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1451 / #1543 The weekly now-usage block (AiUsageModel.week, shared with SuperIntel's Cost page), from the raw seven_day sample. */
class AiUsageWeekTest {

    private val now = 1_792_843_200_000L
    private fun at(used: Double, remainingDays: Double) = AiUsageModel.Win(used, now / 1000 + Math.round(remainingDays * 86_400))

    @Test fun `the owner's example - used 58, 2_9 days in, 4_1 days left - gives exactly the four lines`() {
        assertEquals(
            listOf(
                "Used: 58% (in 2.9 days) | 20%/day avg",
                "Left: 42% (4.1 days)    | 10%/day",
                "Est: 20%/day (used avg) : over in 2.1 days",
                "     14%/day (1/7)      : over in 2.9 days",
            ),
            AiUsageModel.weekLines(at(58.0, 4.1), now),
        )
    }

    @Test fun `the example's numbers and its runs-out-before-reset marks`() {
        val w = AiUsageModel.week(at(58.0, 4.1), now)
        assertEquals(2.9, w.elapsedDays, 1e-3); assertEquals(4.1, w.remainingDays, 1e-3)
        assertEquals(20.0, w.avgPerDay!!, 0.01); assertEquals(10.24, w.needPerDay!!, 0.01)
        assertEquals(2.1, w.overAvgDays!!, 0.01); assertEquals(2.94, w.overFlatDays, 0.01)
        assertTrue("2.1 days at the avg is before the reset in 4.1", w.runsOutAvg)
        assertTrue("2.9 days at 1/7 is before the reset in 4.1", w.runsOutFlat)
        assertEquals(setOf(2, 3), w.alert)
    }

    @Test fun `a slow week does not run out and is not marked`() {
        val w = AiUsageModel.week(at(20.0, 3.0), now)   // 4 days in at 5%/day: 80% left lasts 16 days at the avg
        assertFalse(w.runsOutAvg); assertFalse(w.runsOutFlat); assertTrue(w.alert.isEmpty())
        assertEquals("Est: 5%/day (used avg) : over in 16.0 days", w.lines()[2])
        assertEquals("     14%/day (1/7)     : over in 5.6 days", w.lines()[3])
    }

    @Test fun `the earlier example still holds, columns now aligned`() {
        assertEquals(
            listOf(
                "Used: 29% (in 0.8 days) | 35%/day avg",
                "Left: 71% (6.2 days)    | 12%/day",
                "Est: 35%/day (used avg) : over in 2.0 days",
                "     14%/day (1/7)      : over in 5.0 days",
            ),
            AiUsageModel.weekLines(at(29.0, 6.17), now),
        )
    }

    @Test fun `a fresh week and a spent week do not divide by zero`() {
        val fresh = AiUsageModel.weekLines(AiUsageModel.Win(0.0, now / 1000 + 7 * 86_400), now)
        assertEquals("Used: 0% (in 0.0 days) | -/day avg", fresh[0])
        assertEquals("Est: -/day (used avg) : -", fresh[2])
        val spent = AiUsageModel.week(AiUsageModel.Win(100.0, now / 1000 + 86_400), now)
        assertEquals("Left: 0% (1.0 days)      | 0%/day", spent.lines()[1])
        assertEquals("Est: 17%/day (used avg) : over in 0.0 days", spent.lines()[2])
        assertTrue("already spent before the reset", spent.runsOutAvg)
    }
}

package com.diegonmarcos.superapp.notificationcenter

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1451 The weekly burn-rate block of the AI card, from the raw seven_day sample, with the owner's example numbers. */
class AiUsageWeekTest {

    private val now = 1_792_843_200_000L

    /** used 29%, 6.17 days to the reset (= 0.83 days elapsed). */
    private val sample = AiUsageModel.Win(29.0, now / 1000 + (6.17 * 86_400).toLong())

    @Test fun `the example numbers give the four lines of the owner's format`() {
        assertEquals(
            listOf(
                "Used: 29% (in 0.8 days) | 35%/day avg",
                "Left: 71% (6.2 days) | 12%/day",
                "Est: 35%/day (used avg) : over in 2.0 days",
                "     14%/day (1/7)      : over in 5.0 days",
            ),
            AiUsageModel.weekLines(sample, now),
        )
    }

    @Test fun `a fresh week and a spent week do not divide by zero`() {
        val fresh = AiUsageModel.weekLines(AiUsageModel.Win(0.0, now / 1000 + 7 * 86_400), now)
        assertEquals("Used: 0% (in 0.0 days) | -/day avg", fresh[0])
        val spent = AiUsageModel.weekLines(AiUsageModel.Win(100.0, now / 1000 + 86_400), now)
        assertEquals("Left: 0% (1.0 days) | 0%/day", spent[1])
        assertEquals("Est: 17%/day (used avg) : over in 0.0 days", spent[2])
    }
}

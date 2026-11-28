package com.diegonmarcos.clocktools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1986 the reference look's tokens, the stopwatch dial's reading, the ring card's buttons; #1987 Calc's one clock page. */
class ClockLookTest {
    private fun rgb(c: Long) = Triple(((c shr 16) and 0xFF).toInt(), ((c shr 8) and 0xFF).toInt(), (c and 0xFF).toInt())
    private fun luma(c: Long) = rgb(c).let { (r, g, b) -> (r * 299 + g * 587 + b * 114) / 1000 }

    @Test fun `the background is black with an indigo and a purple glow, the accent is lilac`() {
        assertEquals(0xFF000000L, ClockLook.BACKGROUND)
        rgb(ClockLook.GLOW_INDIGO).let { (r, g, b) -> assertTrue("indigo: blue leads", b > r && b > g && b in 0x28..0x60) }
        rgb(ClockLook.GLOW_PURPLE).let { (r, g, b) -> assertTrue("purple: blue then red over green", b > r && r > g && b in 0x30..0x70) }
        assertTrue("the indigo glow sits above the purple one", ClockLook.GLOW_INDIGO_AT.second < ClockLook.GLOW_PURPLE_AT.second)
        rgb(ClockLook.ACCENT).let { (r, g, b) -> assertTrue("lilac: light, blue over red over green", luma(ClockLook.ACCENT) > 150 && b > r && r > g) }
        assertEquals(0xFFB5A5C2L, ClockLook.ACCENT)
        assertEquals(0xFF8587FEL, ClockLook.HAND)
    }

    @Test fun `sheets, pills and the island are dark greys, text on them light`() {
        for (c in listOf(ClockLook.SHEET, ClockLook.LAP, ClockLook.BAR, ClockLook.ISLAND, ClockLook.ISLAND_SELECTED, ClockLook.PILL)) {
            assertTrue("dark grey %08x".format(c), luma(c) in 0x15..0x50)
        }
        assertEquals(0xFF1B1B1BL, ClockLook.SHEET)
        assertTrue(luma(ClockLook.ISLAND_SELECTED) > luma(ClockLook.ISLAND))
        for (c in listOf(ClockLook.TITLE, ClockLook.PILL_TEXT, ClockLook.BAR_TEXT, ClockLook.DIGITS, ClockLook.DIGITS_WHITE, ClockLook.ON_ACCENT)) {
            assertTrue("light text %08x".format(c), luma(c) > 180)
        }
        assertTrue("a disabled Start is a dim accent", luma(ClockLook.ACCENT_DISABLED) < luma(ClockLook.ACCENT))
        assertTrue("wheel neighbours are dim", ClockLook.WHEEL_DIM in 0.1f..0.35f)
    }

    @Test fun `the dial reads mm ss cc and its hand turns once a minute`() {
        assertEquals("00:00.00", ClockLook.dialText(0))
        assertEquals("00:48.28", ClockLook.dialText(48_280))
        assertEquals("12:05.09", ClockLook.dialText(12 * 60_000L + 5_090))
        assertEquals("1:00:00.00", ClockLook.dialText(3_600_000))
        assertEquals("00:00.00", ClockLook.dialText(-5))
        assertEquals(0f, ClockLook.handDegrees(0), 0f)
        assertEquals(90f, ClockLook.handDegrees(15_000), 0.01f)
        assertEquals(0f, ClockLook.handDegrees(60_000), 0f)
        assertTrue(ClockLook.majorTick(0) && ClockLook.majorTick(55))
        assertFalse(ClockLook.majorTick(1))
    }

    @Test fun `the island keeps every page, in the reference's order first`() {
        assertEquals(listOf("alarms", "world", "stopwatch", "timers", "interval", "bedtime", "pomodoro"), ClockLook.islandOrder(ClockSubpages.IDS))
        assertEquals(ClockSubpages.IDS.toSet(), ClockLook.islandOrder(ClockSubpages.IDS).toSet())
        assertEquals(listOf("world", "extra"), ClockLook.islandOrder(listOf("extra", "world")))
    }

    @Test fun `a ringing alarm's card has Stop, Snooze with its time and Dismiss, full screen and ongoing`() {
        val c = RingCard.of("alarm:7", snoozable = true, snoozeAt = "07:15")
        assertTrue(c.alarm && c.fullScreen && c.ongoing)
        assertEquals(listOf(RingCard.STOP, RingCard.SNOOZE + ":07:15", RingCard.DISMISS), c.actions.map { it.label })
        assertEquals(listOf("dismiss" to "alarm:7", "snooze" to "alarm:7", "dismiss_all" to ""), c.actions.map { it.op to it.key })
        // snoozed out: Stop and Dismiss stay
        assertEquals(listOf(RingCard.STOP, RingCard.DISMISS), RingCard.of("alarm:7", false, "07:15").actions.map { it.label })
        assertEquals("07:15", RingCard.snoozeAt(7 * 60 + 5, 10))
        assertEquals("00:04", RingCard.snoozeAt(23 * 60 + 59, 5))
    }

    @Test fun `a finished timer's card has Stop and +1 min, full screen`() {
        val c = RingCard.of("timer:3", snoozable = false, snoozeAt = "")
        assertFalse(c.alarm)
        assertTrue(c.fullScreen && c.ongoing)
        assertEquals(listOf(RingCard.STOP, RingCard.PLUS_MINUTE), c.actions.map { it.label })
        assertEquals(listOf("dismiss" to "timer:3", "timer_add" to "timer:3"), c.actions.map { it.op to it.key })
        assertEquals(listOf(RingCard.STOP, RingCard.START), RingCard.of(ClockLogic.POMODORO, false, "").actions.map { it.label })
    }

    @Test fun `Cloud Calc keeps only the World clock, every other page moves to Cloud Tasks`() {
        assertEquals(listOf("world"), ClockSubpages.CALC)
        assertEquals(listOf("alarms", "timers", "stopwatch", "interval", "bedtime", "pomodoro"), ClockSubpages.MOVED_TO_TASKS)
        assertEquals("com.diegonmarcos.cloudagenda", ClockSubpages.TASKS_PACKAGE)
        assertEquals("world", ClockSubpages.idOf("worldclock"))
        assertEquals("alarms", ClockSubpages.idOf("alarms"))
        assertEquals(null, ClockSubpages.idOf("nowhere"))
    }
}

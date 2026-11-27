package com.diegonmarcos.clocktools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1970 the wheel pickers' value mapping and the timer's preset chips. PIT mutates Wheel / TimerPresets / ClockDesign against it. */
class WheelPresetsTest {
    @Test fun `a wheel row shows its value and wraps both ways`() {
        assertEquals(0, Wheel.valueAt(0, 0, 59))
        assertEquals(59, Wheel.valueAt(59, 0, 59))
        assertEquals(0, Wheel.valueAt(60, 0, 59))
        assertEquals(59, Wheel.valueAt(-1, 0, 59))
        assertEquals(99, Wheel.valueAt(-1, 0, 99))
        assertEquals(1, Wheel.valueAt(0, 1, 12))
        assertEquals(2, Wheel.valueAt(1, 1, 12))
        assertEquals(12, Wheel.valueAt(-1, 1, 12))
        assertEquals(24, Wheel.size(0, 23))
        assertEquals(12, Wheel.size(1, 12))
    }

    @Test fun `a wheel opens on its value in the middle lap`() {
        val i = Wheel.indexOf(7, 0, 23)
        assertEquals(500 * 24 + 7, i)
        assertEquals(7, Wheel.valueAt(i, 0, 23))
        assertEquals(10 * 24 + 7, Wheel.indexOf(7, 0, 23, laps = 20))
        assertEquals(500 * 24 + 23, Wheel.indexOf(40, 0, 23))
        assertEquals(500 * 12, Wheel.indexOf(-3, 1, 12))
    }

    @Test fun `faded neighbours are the values either side`() {
        assertEquals(listOf(6, 7, 8), Wheel.neighbours(7, 0, 23, 1))
        assertEquals(listOf(99, 0, 1), Wheel.neighbours(0, 0, 99, 1))
        assertEquals(listOf(58, 59, 0, 1, 2), Wheel.neighbours(0, 0, 59, 2))
        assertEquals(listOf(5), Wheel.neighbours(5, 0, 59, 0))
    }

    @Test fun `the middle row follows the scroll, rounding at half a row`() {
        assertEquals(11, Wheel.centre(10, 0, 100, 3))
        assertEquals(11, Wheel.centre(10, 49, 100, 3))
        assertEquals(12, Wheel.centre(10, 50, 100, 3))
        assertEquals(12, Wheel.centre(10, 99, 100, 3))
        assertEquals(12, Wheel.centre(10, 0, 100, 5))
        assertEquals(10, Wheel.centre(10, 70, 0, 1))
        assertEquals(9, Wheel.firstFor(10, 3))
        assertEquals(8, Wheel.firstFor(10, 5))
        assertEquals(0, Wheel.firstFor(0, 3))
    }

    @Test fun `rows fade with distance`() {
        assertEquals(1f, Wheel.alpha(0), 0f)
        assertEquals(0.35f, Wheel.alpha(1), 0f)
        assertEquals(0.35f, Wheel.alpha(-1), 0f)
        assertEquals(0.15f, Wheel.alpha(2), 0f)
        assertEquals(0.15f, Wheel.alpha(-3), 0f)
    }

    @Test fun `timer wheels map to a duration and back`() {
        assertEquals(0L, Wheel.hmsToMs(0, 0, 0))
        assertEquals(3_723_000L, Wheel.hmsToMs(1, 2, 3))
        assertEquals((99 * 3600L + 59 * 60 + 59) * 1000, Wheel.hmsToMs(150, 80, 70))
        assertEquals(0L, Wheel.hmsToMs(-1, -1, -1))
        assertEquals(Triple(1, 2, 3), Wheel.msToHms(3_723_000L))
        assertEquals(Triple(0, 0, 0), Wheel.msToHms(-5))
        assertEquals(Triple(0, 0, 0), Wheel.msToHms(999))
        assertEquals(Triple(99, 59, 59), Wheel.msToHms(500 * 3_600_000L))
        assertEquals(Triple(0, 10, 0), Wheel.msToHms(600_000))
    }

    @Test fun `alarm wheels map to a minute of the day and back`() {
        assertEquals(425, Wheel.minuteOfDay(7, 5))
        assertEquals(23 * 60 + 59, Wheel.minuteOfDay(30, 70))
        assertEquals(0, Wheel.minuteOfDay(-1, -1))
        assertEquals(7 to 5, Wheel.hourMinute(425))
        assertEquals(0 to 0, Wheel.hourMinute(1440))
        assertEquals(23 to 59, Wheel.hourMinute(-1))
    }

    @Test fun `preset chips read HH MM SS and parse back`() {
        assertEquals(listOf("00:10:00", "00:15:00", "00:30:00"), TimerPresets.DEFAULT.map(TimerPresets::text))
        assertEquals("01:01:01", TimerPresets.text(3661))
        assertEquals("00:00:00", TimerPresets.text(-4))
        assertEquals(600L, TimerPresets.parse("00:10:00"))
        assertEquals(600L, TimerPresets.parse(" 10:00 "))
        assertEquals(3661L, TimerPresets.parse("1:01:01"))
        assertEquals(99 * 3600L + 59 * 60 + 59, TimerPresets.parse("99:59:59"))
        for (bad in listOf("", "10", "00:00:00", "0:60", "00:10:60", "1:2:3:4", "aa:10", "100:00:00", "10:", ":10", "-1:10")) {
            assertNull(bad, TimerPresets.parse(bad))
        }
    }

    @Test fun `presets are the user's edits, else the declaration, else the defaults`() {
        assertEquals(listOf(60L), TimerPresets.effective(listOf(60L), listOf(120L)))
        assertEquals(listOf(120L), TimerPresets.effective(null, listOf(120L)))
        assertEquals(TimerPresets.DEFAULT, TimerPresets.effective(null, emptyList()))
        assertEquals(listOf(600L, 120L, 1800L), TimerPresets.edit(TimerPresets.DEFAULT, 1, 120))
        assertEquals(TimerPresets.DEFAULT, TimerPresets.edit(TimerPresets.DEFAULT, 3, 120))
        assertEquals(TimerPresets.DEFAULT, TimerPresets.edit(TimerPresets.DEFAULT, -1, 120))
        assertEquals(TimerPresets.DEFAULT, TimerPresets.edit(TimerPresets.DEFAULT, 0, 0))
        assertEquals(listOf(1L, 900L, 1800L), TimerPresets.edit(TimerPresets.DEFAULT, 0, 1))
    }

    @Test fun `the alarm sheet's declared choices`() {
        assertEquals("Basic call", ClockDesign.vibrationLabel("basic"))
        assertEquals("Heartbeat", ClockDesign.vibrationLabel("heartbeat"))
        assertEquals("Basic call", ClockDesign.vibrationLabel("nope"))
        assertArrayEquals(longArrayOf(0, 800, 600), ClockDesign.vibrationPattern("basic"))
        assertArrayEquals(longArrayOf(0, 800, 600), ClockDesign.vibrationPattern("gone"))
        assertArrayEquals(longArrayOf(0, 100, 400, 100, 400), ClockDesign.vibrationPattern("ticktock"))
        assertEquals("5 minutes, 3 times", ClockDesign.snoozeText(5, 3))
        assertEquals("1 minute, once", ClockDesign.snoozeText(1, 1))
        assertEquals("10 minutes, unlimited", ClockDesign.snoozeText(10, 0))
        assertEquals("10 minutes, unlimited", ClockDesign.snoozeText(10, -2))
        assertEquals("fleet", ClockDesign.BACKGROUNDS.first())
        assertEquals(0, ClockDesign.SNOOZE_TIMES.last())
    }

    @Test fun `the declaration carries the wheel presets and the Pomodoro lengths`() {
        val c = ClockConfig.parse(mapOf(
            "timers" to """{"presets":[60],"wheel_presets":[300,0,1200]}""",
            "pomodoro" to """{"work":50,"short_break":10,"long_break":30,"long_every":3,"auto_advance":false}""",
        ))
        assertEquals(listOf(300L, 1200L), c.wheelPresets)
        assertEquals(listOf(50, 10, 30, 3), listOf(c.pomodoro.workMin, c.pomodoro.shortMin, c.pomodoro.longMin, c.pomodoro.longEvery))
        assertEquals(false, c.pomodoro.autoAdvance)
        val none = ClockConfig.parse(emptyMap())
        assertEquals(TimerPresets.DEFAULT, none.wheelPresets)
        assertEquals(Pomodoro(), none.pomodoro)
        assertEquals(TimerPresets.DEFAULT, ClockConfig.DEFAULT.wheelPresets)
        assertEquals(listOf("world", "alarms", "timers", "stopwatch", "interval", "bedtime", "pomodoro"), ClockSubpages.IDS)
    }
}

package com.diegonmarcos.clocktools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** #1970 "Add to calendar" against a fake provider: insert, update in place, re-insert when gone, delete; plus the alarm sheet's new rules. */
class ClockCalendarTest {
    /** The fake CalendarContract.Events: rows by id, and every call made. */
    class FakeProvider(private val writable: Boolean = true) : CalendarSink {
        val rows = linkedMapOf<Long, CalendarEvent>()
        val calls = mutableListOf<String>()
        private var next = 100L
        override fun insert(e: CalendarEvent): Long? {
            calls += "insert"
            if (!writable) return null
            val id = next++
            rows[id] = e
            return id
        }
        override fun update(id: Long, e: CalendarEvent): Boolean {
            calls += "update:$id"
            if (id !in rows) return false
            rows[id] = e
            return true
        }
        override fun delete(id: Long): Boolean {
            calls += "delete:$id"
            return rows.remove(id) != null
        }
    }

    private val madrid = ZoneId.of("Europe/Madrid")
    private val friNoon = LocalDateTime.of(2026, 10, 2, 12, 0).atZone(madrid).toInstant().toEpochMilli()
    private fun at(d: Int, h: Int, m: Int) = LocalDateTime.of(2026, 10, d, h, m).atZone(madrid).toInstant().toEpochMilli()

    @Test fun `repeat days become an RRULE`() {
        assertEquals("", ClockCalendar.rrule(0))
        assertEquals("FREQ=DAILY", ClockCalendar.rrule(ClockLogic.ALL_DAYS))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", ClockCalendar.rrule(ClockLogic.WEEKDAYS))
        assertEquals("FREQ=WEEKLY;BYDAY=SA,SU", ClockCalendar.rrule(ClockLogic.WEEKEND))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", ClockCalendar.rrule(0b101))
        assertEquals("FREQ=DAILY", ClockCalendar.rrule(0xFF))
    }

    @Test fun `the event is the alarm's next occurrence, named and in its zone`() {
        val e = ClockCalendar.event(Alarm(1, 7 * 60 + 5, days = ClockLogic.WEEKDAYS, label = "Gym", group = "Work"), friNoon, madrid)
        assertEquals("Gym 07:05", e.title)
        assertEquals(at(5, 7, 5), e.start)
        assertEquals(5 * ClockLogic.MINUTE_MS, e.durationMs)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", e.rrule)
        assertEquals("Europe/Madrid", e.timezone)
        assertEquals("Cloud Clock alarm · Work", e.description)
        val plain = ClockCalendar.event(Alarm(1, 18 * 60, snoozedUntil = friNoon + 60_000), friNoon, madrid)
        assertEquals("Alarm 18:00", plain.title)
        assertEquals("a snooze is not the event's time", at(2, 18, 0), plain.start)
        assertEquals("Cloud Clock alarm", plain.description)
    }

    @Test fun `switching the option on writes the event, saving again updates it, off deletes it`() {
        val fake = FakeProvider()
        val a = Alarm(1, 7 * 60, days = ClockLogic.ALL_DAYS, calendar = true)
        val on = ClockCalendar.sync(a, fake, friNoon, madrid)
        assertEquals(100L, on.calendarEventId)
        assertEquals("FREQ=DAILY", fake.rows.getValue(100).rrule)
        val moved = ClockCalendar.sync(on.copy(minuteOfDay = 8 * 60), fake, friNoon, madrid)
        assertEquals(100L, moved.calendarEventId)
        assertEquals(at(3, 8, 0), fake.rows.getValue(100).start)
        assertEquals(listOf("insert", "update:100"), fake.calls)
        val off = ClockCalendar.sync(moved.copy(calendar = false), fake, friNoon, madrid)
        assertEquals(0L, off.calendarEventId)
        assertTrue(fake.rows.isEmpty())
        val disabled = ClockCalendar.sync(on.copy(enabled = false), FakeProvider().also { it.rows[100] = ClockCalendar.event(a, friNoon, madrid) }, friNoon, madrid)
        assertEquals(0L, disabled.calendarEventId)
    }

    @Test fun `an event deleted in the calendar app is written again`() {
        val fake = FakeProvider()
        val a = ClockCalendar.sync(Alarm(1, 7 * 60, calendar = true, calendarEventId = 55), fake, friNoon, madrid)
        assertEquals(listOf("update:55", "insert"), fake.calls)
        assertEquals(100L, a.calendarEventId)
    }

    @Test fun `no calendar or no grant leaves no id, and nothing is touched when the option is off`() {
        assertEquals(0L, ClockCalendar.sync(Alarm(1, 60, calendar = true), FakeProvider(writable = false), friNoon, madrid).calendarEventId)
        val fake = FakeProvider()
        assertEquals(Alarm(1, 60), ClockCalendar.sync(Alarm(1, 60), fake, friNoon, madrid))
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun `deleting the alarm deletes its event`() {
        val fake = FakeProvider()
        val a = ClockCalendar.sync(Alarm(1, 60, calendar = true), fake, friNoon, madrid)
        assertTrue(ClockCalendar.remove(a, fake))
        assertTrue(fake.rows.isEmpty())
        assertFalse(ClockCalendar.remove(Alarm(2, 60), fake))
        assertEquals(listOf("insert", "delete:100"), fake.calls)
        assertTrue(ClockCalendar.wanted(Alarm(1, 60, calendar = true)))
        assertFalse(ClockCalendar.wanted(Alarm(1, 60, calendar = true, enabled = false)))
        assertFalse(ClockCalendar.wanted(Alarm(1, 60)))
    }

    @Test fun `a one-off alarm on a picked date rings that day, then is an ordinary one-off`() {
        val oct9 = java.time.LocalDate.of(2026, 10, 9).toEpochDay()
        assertEquals(at(9, 7, 0), ClockLogic.nextOccurrence(Alarm(1, 7 * 60, date = oct9), friNoon, madrid))
        assertEquals("a date in the past falls back", at(3, 7, 0), ClockLogic.nextOccurrence(Alarm(1, 7 * 60, date = oct9 - 30), friNoon, madrid))
        assertEquals("repeat days win over a date", at(5, 7, 0), ClockLogic.nextOccurrence(Alarm(1, 7 * 60, days = ClockLogic.WEEKDAYS, date = oct9), friNoon, madrid))
    }

    @Test fun `snooze stops at its count and is reset by a dismiss`() {
        val key = ClockLogic.alarmKey(1)
        var d = ClockData(alarms = listOf(Alarm(1, 7 * 60, snoozeMinutes = 5, snoozeTimes = 2)), ringing = listOf(Ring(key, "", friNoon)))
        d = ClockLogic.snooze(d, key, friNoon)
        assertEquals(1, d.alarms.single().snoozeCount)
        assertEquals(friNoon + 5 * ClockLogic.MINUTE_MS, d.alarms.single().snoozedUntil)
        d = ClockLogic.snooze(d.copy(ringing = listOf(Ring(key, "", friNoon))), key, friNoon)
        assertEquals(2, d.alarms.single().snoozeCount)
        assertFalse(ClockLogic.canSnooze(d.alarms.single()))
        val third = ClockLogic.snooze(d.copy(ringing = listOf(Ring(key, "", friNoon))), key, friNoon + 1)
        assertEquals("the third is a dismiss", 0, third.alarms.single().snoozeCount)
        assertTrue(third.ringing.isEmpty())
        assertTrue(ClockLogic.canSnooze(Alarm(1, 0, snoozeCount = 99)))
        assertFalse(ClockLogic.canSnooze(Alarm(1, 0, snoozeOn = false)))
        val off = ClockLogic.snooze(ClockData(alarms = listOf(Alarm(1, 0, snoozeOn = false)), ringing = listOf(Ring(key, "", 0))), key, friNoon)
        assertEquals(0L, off.alarms.single().snoozedUntil)
    }

    @Test fun `the sheet's fields survive the store, and old stores read as before`() {
        val a = Alarm(3, 425, 0x7F, true, "Run", false, "content://x", 5, false, 0, false, 3, 1, "heartbeat", "Gym", "ocean", 20_000, true, 77)
        val back = ClockCodec.decode(ClockCodec.encode(ClockData(alarms = listOf(a), wheelPresets = listOf(60L, 120L)))).also { assertEquals(listOf(60L, 120L), it.wheelPresets) }
        assertEquals(a, back.alarms.single())
        val old = ClockCodec.decode("""{"alarms":[{"id":1,"minute":60}]}""")
        assertEquals(Alarm(1, 60), old.alarms.single())
        assertEquals(null, old.wheelPresets)
    }
}

package com.diegonmarcos.clocktools

import java.time.DayOfWeek
import java.time.ZoneId

/** #1970 one alarm as a calendar event: what the device calendar (CalendarContract.Events) is given. */
data class CalendarEvent(
    val title: String,
    val start: Long,
    val durationMs: Long,
    /** RFC 5545 RRULE ("FREQ=DAILY", "FREQ=WEEKLY;BYDAY=MO,WE"); "" for a one-off alarm. */
    val rrule: String,
    val timezone: String,
    val description: String,
)

/** Where events go: the Android half writes CalendarContract.Events; the suite hands a fake. */
interface CalendarSink {
    /** The new event's id, or null when nothing could be written (no calendar, no grant). */
    fun insert(e: CalendarEvent): Long?
    /** False when the event is gone (deleted in the calendar app): the caller writes a new one. */
    fun update(id: Long, e: CalendarEvent): Boolean
    fun delete(id: Long): Boolean
}

/**
 * #1970 "Add to calendar": an alarm with [Alarm.calendar] on (and switched on) is one event in the device calendar, its
 * repeat days its RRULE; saving it again updates that event, switching it or the option off, or deleting the alarm,
 * removes it. The alarm keeps the event's id ([Alarm.calendarEventId]), so the two stay one.
 */
object ClockCalendar {
    /** How long the event lasts in the calendar: a marker, not a meeting. */
    const val LENGTH_MS = 5 * ClockLogic.MINUTE_MS

    private val BYDAY = mapOf(
        DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
        DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
    )

    fun rrule(days: Int): String = when (days and ClockLogic.ALL_DAYS) {
        0 -> ""
        ClockLogic.ALL_DAYS -> "FREQ=DAILY"
        else -> "FREQ=WEEKLY;BYDAY=" + DayOfWeek.entries.filter { days and ClockLogic.dayBit(it) != 0 }.joinToString(",") { BYDAY.getValue(it) }
    }

    /** Whether [a] should have an event now. */
    fun wanted(a: Alarm): Boolean = a.calendar && a.enabled

    fun event(a: Alarm, now: Long, zone: ZoneId): CalendarEvent = CalendarEvent(
        title = a.label.ifBlank { "Alarm" } + " " + ClockLogic.timeText(a.minuteOfDay),
        start = ClockLogic.nextOccurrence(a.copy(snoozedUntil = 0), now, zone),
        durationMs = LENGTH_MS,
        rrule = rrule(a.days),
        timezone = zone.id,
        description = "Cloud Clock alarm" + (if (a.group.isNotBlank()) " · " + a.group else ""),
    )

    /** Brings the calendar in line with [a] and returns [a] with the event id it now has (0: none). */
    fun sync(a: Alarm, sink: CalendarSink, now: Long, zone: ZoneId): Alarm {
        if (!wanted(a)) {
            if (a.calendarEventId > 0) sink.delete(a.calendarEventId)
            return a.copy(calendarEventId = 0)
        }
        val e = event(a, now, zone)
        if (a.calendarEventId > 0 && sink.update(a.calendarEventId, e)) return a
        return a.copy(calendarEventId = sink.insert(e) ?: 0)
    }

    /** The alarm is deleted: so is its event. */
    fun remove(a: Alarm, sink: CalendarSink): Boolean = a.calendarEventId > 0 && sink.delete(a.calendarEventId)
}

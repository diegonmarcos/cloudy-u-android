package com.diegonmarcos.clocktools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat

/**
 * #1970 "Add to calendar" on the device: ClockCalendar's events written through the CalendarContract provider (Cloud
 * Tasks only imports events, #1766; there is no fleet calendar engine that writes, so the system provider is the one
 * calendar every app on the phone reads). The event goes to the primary writable calendar, else the first writable one.
 * Without READ_CALENDAR + WRITE_CALENDAR (asked by the alarm sheet only when the option is switched on) there is no sink.
 */
class ClockCalendarProvider(private val ctx: Context) : CalendarSink {
    private val cr get() = ctx.contentResolver

    private fun calendarId(): Long? = runCatching {
        cr.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { c ->
            var first: Long? = null
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (first == null) first = id
                if (c.getInt(1) == 1) return@use id
            }
            first
        }
    }.getOrNull()

    private fun values(e: CalendarEvent): ContentValues = ContentValues().apply {
        put(CalendarContract.Events.TITLE, e.title)
        put(CalendarContract.Events.DESCRIPTION, e.description)
        put(CalendarContract.Events.DTSTART, e.start)
        put(CalendarContract.Events.EVENT_TIMEZONE, e.timezone)
        // A repeating event carries DURATION and no DTEND (the provider's rule); a one-off carries DTEND.
        if (e.rrule.isNotEmpty()) {
            put(CalendarContract.Events.RRULE, e.rrule)
            put(CalendarContract.Events.DURATION, "PT${e.durationMs / 60_000}M")
            putNull(CalendarContract.Events.DTEND)
        } else {
            putNull(CalendarContract.Events.RRULE)
            putNull(CalendarContract.Events.DURATION)
            put(CalendarContract.Events.DTEND, e.start + e.durationMs)
        }
    }

    override fun insert(e: CalendarEvent): Long? = runCatching {
        val cal = calendarId() ?: return null
        cr.insert(CalendarContract.Events.CONTENT_URI, values(e).apply { put(CalendarContract.Events.CALENDAR_ID, cal) })
            ?.let { ContentUris.parseId(it) }
    }.getOrNull()

    override fun update(id: Long, e: CalendarEvent): Boolean = runCatching {
        cr.update(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), values(e), null, null) > 0
    }.getOrDefault(false)

    override fun delete(id: Long): Boolean = runCatching {
        cr.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }.getOrDefault(false)

    companion object {
        /** The libs:perms catalogue ids the option needs (each consumer's build.json::perms.needs holds them). */
        val PERMS: List<String> = listOf("calendar_read", "calendar_write")

        fun granted(ctx: Context): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

        fun sinkOrNull(ctx: Context): CalendarSink? = if (granted(ctx)) ClockCalendarProvider(ctx.applicationContext ?: ctx) else null
    }
}

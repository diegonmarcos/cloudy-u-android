package com.diegonmarcos.cloudagenda.data

import android.content.Intent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * #1766 one event handed to Cloud Tasks by another app ("Add to calendar" or an .ics file). [start] / [end] are local
 * date-times in [zone]; an [allDay] event covers the dates [start]..[end] inclusive (ICS DTEND is already folded back
 * by one day). [uid] is the ICS UID (blank for an Intent: it never dedupes).
 */
data class ImportedEvent(
    val uid: String = "",
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val zone: String = "",
    val allDay: Boolean = false,
    val location: String = "",
    val description: String = "",
    val organizer: String = "",
    val attendees: List<String> = emptyList(),
    val rrule: String = "",
    val alarms: List<String> = emptyList(),
) {
    /** The calendar views read dates only (Task.dateTarget / dateDeadline); the time of day and the rest go in the details. */
    fun toTask(id: String, listId: String, old: Task? = null): Task = Task(
        id = id, listId = listId, name = title.ifBlank { "(no title)" }, details = detailsText(),
        dateTarget = start.toLocalDate().toString(), dateDeadline = lastDay().toString(), location = location,
        parentId = old?.parentId, status = old?.status ?: TaskStatus.ACTION, position = old?.position ?: 0,
        tagIds = old?.tagIds ?: emptySet(), attachments = old?.attachments ?: emptyList(),
    )

    fun lastDay(): LocalDate = end.toLocalDate().let { if (it.isBefore(start.toLocalDate())) start.toLocalDate() else it }

    fun detailsText(): String = buildList {
        add(if (allDay) "All day" else "${TIME.format(start)} - ${TIME.format(end)}" + zone.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty())
        if (organizer.isNotBlank()) add("Organizer: $organizer")
        if (attendees.isNotEmpty()) add("Guests: " + attendees.joinToString(", "))
        if (rrule.isNotBlank()) add("Repeats: $rrule")
        if (alarms.isNotEmpty()) add("Reminders: " + alarms.joinToString(", "))
        if (description.isNotBlank()) add("").also { add(description) }
    }.joinToString("\n")

    private companion object { val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
}

/** What to do with an imported event whose UID is already in the store. */
enum class DuplicateChoice { UPDATE, SKIP }

/** The "Add to calendar" Intent (CalendarContract.Events): its extras, read without the provider (no calendar permission). */
object EventIntents {
    const val EXTRA_BEGIN = "beginTime"
    const val EXTRA_END = "endTime"
    const val EXTRA_ALL_DAY = "allDay"
    const val EXTRA_LOCATION = "eventLocation"
    const val EXTRA_DESCRIPTION = "description"
    const val EXTRA_TITLE = "title"
    const val EXTRA_RRULE = "rrule"
    const val EXTRA_TZ = "eventTimezone"
    const val EXTRA_ORGANIZER = "organizer"

    fun isInsert(i: Intent): Boolean = (i.action == Intent.ACTION_INSERT || i.action == Intent.ACTION_EDIT) &&
        (i.type?.endsWith("/event") == true || i.data?.path?.startsWith("/events") == true && i.data?.authority == "com.android.calendar" || i.data?.let { it.scheme == "content" && it.authority == "com.android.calendar" } == true)

    /** [defaultZone] reads a timed event with no eventTimezone extra (the phone's zone). A missing end is one hour (a day when all-day). */
    fun fromIntent(i: Intent, defaultZone: ZoneId = ZoneId.systemDefault()): ImportedEvent? {
        if (!isInsert(i)) return null
        val ex = i.extras
        val allDay = when (val v = ex?.get(EXTRA_ALL_DAY)) { is Boolean -> v; is String -> v.equals("true", true); is Number -> v.toInt() != 0; else -> false }
        fun millis(k: String, d: Long): Long = when (val v = ex?.get(k)) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull() ?: d; else -> d }
        val zone = if (allDay) ZoneOffset.UTC else ex?.getString(EXTRA_TZ)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: defaultZone
        val title = ex?.getString(EXTRA_TITLE).orEmpty().ifBlank { ex?.getString(Intent.EXTRA_SUBJECT).orEmpty() }
        val now = System.currentTimeMillis()
        val b = millis(EXTRA_BEGIN, now)
        val e = if (i.hasExtra(EXTRA_END)) millis(EXTRA_END, b) else b + if (allDay) 86_400_000L else 3_600_000L
        val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(b), zone)
        var end = LocalDateTime.ofInstant(Instant.ofEpochMilli(maxOf(e, b)), zone)
        // An all-day end is the midnight AFTER the last day (CalendarContract): the last day is the day before.
        if (allDay && end.isAfter(start)) end = end.minusDays(1)
        val guests = (ex?.get(Intent.EXTRA_EMAIL)).let { v ->
            when (v) { is Array<*> -> v.map { it.toString() }; is String -> v.split(',', ';'); is Collection<*> -> v.map { it.toString() }; else -> emptyList() }
        }.map { it.trim() }.filter { it.isNotEmpty() }
        return ImportedEvent(
            title = title, start = start, end = end, zone = if (allDay) "" else zone.id, allDay = allDay,
            location = ex?.getString(EXTRA_LOCATION).orEmpty(), description = ex?.getString(EXTRA_DESCRIPTION).orEmpty(),
            organizer = ex?.getString(EXTRA_ORGANIZER).orEmpty(), attendees = guests, rrule = ex?.getString(EXTRA_RRULE).orEmpty(),
        )
    }
}

/** A small RFC 5545 reader: every VEVENT of a file, with folded lines, escapes, TZID / UTC / floating times and VALARMs. Pure JVM. */
object Ics {
    fun parse(text: String, defaultZone: ZoneId = ZoneId.systemDefault()): List<ImportedEvent> {
        val lines = unfold(text)
        val out = ArrayList<ImportedEvent>()
        var cur: MutableList<Triple<String, Map<String, String>, String>>? = null
        var alarm: MutableList<Triple<String, Map<String, String>, String>>? = null
        val alarms = ArrayList<String>()
        for (raw in lines) {
            val p = prop(raw) ?: continue
            val (name, params, value) = p
            when {
                name == "BEGIN" && value.equals("VEVENT", true) -> { cur = ArrayList(); alarms.clear() }
                name == "END" && value.equals("VEVENT", true) -> { cur?.let { event(it, alarms.toList(), defaultZone)?.let(out::add) }; cur = null }
                cur == null -> {}
                name == "BEGIN" && value.equals("VALARM", true) -> alarm = ArrayList()
                name == "END" && value.equals("VALARM", true) -> { alarm?.let { alarmText(it)?.let(alarms::add) }; alarm = null }
                alarm != null -> alarm.add(p)
                else -> cur.add(p)
            }
        }
        return out
    }

    private fun unfold(t: String): List<String> {
        val out = ArrayList<String>()
        for (l in t.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + l.substring(1) else out.add(l)
        }
        return out.filter { it.isNotBlank() }
    }

    private fun prop(line: String): Triple<String, Map<String, String>, String>? {
        var q = false
        var idx = -1
        for ((i, c) in line.withIndex()) { if (c == '"') q = !q else if (c == ':' && !q) { idx = i; break } }
        if (idx <= 0) return null
        val parts = line.substring(0, idx).split(';')
        val params = parts.drop(1).mapNotNull { s -> s.indexOf('=').takeIf { it > 0 }?.let { s.substring(0, it).uppercase() to s.substring(it + 1).trim('"') } }.toMap()
        return Triple(parts[0].uppercase(), params, line.substring(idx + 1))
    }

    fun unescape(s: String): String {
        val b = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { b.append(when (val n = s[i + 1]) { 'n', 'N' -> '\n'; else -> n }); i += 2 } else { b.append(c); i++ }
        }
        return b.toString()
    }

    private class Moment(val dt: LocalDateTime, val zone: String, val allDay: Boolean)

    private fun moment(value: String, params: Map<String, String>, def: ZoneId): Moment? {
        val v = value.trim()
        return runCatching {
            if (params["VALUE"].equals("DATE", true) || (v.length == 8 && !v.contains('T'))) {
                Moment(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(), "", true)
            } else {
                val utc = v.endsWith("Z")
                val local = LocalDateTime.parse(v.removeSuffix("Z"), DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
                val tz = params["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                when {
                    utc -> Moment(local.atZone(ZoneOffset.UTC).withZoneSameInstant(tz ?: def).toLocalDateTime(), (tz ?: def).id, false)
                    else -> Moment(local, (tz ?: def).id, false)
                }
            }
        }.getOrNull()
    }

    private fun event(props: List<Triple<String, Map<String, String>, String>>, alarms: List<String>, def: ZoneId): ImportedEvent? {
        fun first(n: String) = props.firstOrNull { it.first == n }
        val ds = first("DTSTART") ?: return null
        val s = moment(ds.third, ds.second, def) ?: return null
        val de = first("DTEND")?.let { moment(it.third, it.second, def) }
        val dur = first("DURATION")?.third?.let { runCatching { java.time.Duration.parse(it.trim().replace("+", "")) }.getOrNull() }
        var end = when {
            de != null -> de.dt
            dur != null -> s.dt.plus(dur)
            s.allDay -> s.dt.plusDays(1)
            else -> s.dt
        }
        if (s.allDay && end.isAfter(s.dt)) end = end.minusDays(1)   // DTEND of a date event is exclusive
        if (end.isBefore(s.dt)) end = s.dt
        fun text(n: String) = first(n)?.third?.let(::unescape).orEmpty()
        fun mail(v: String) = v.removePrefix("mailto:").removePrefix("MAILTO:")
        val org = first("ORGANIZER")?.let { it.second["CN"]?.let { cn -> "$cn <${mail(it.third)}>" } ?: mail(it.third) }.orEmpty()
        val att = props.filter { it.first == "ATTENDEE" }.map { it.second["CN"]?.let { cn -> "$cn <${mail(it.third)}>" } ?: mail(it.third) }
        return ImportedEvent(
            uid = first("UID")?.third?.trim().orEmpty(), title = text("SUMMARY"), start = s.dt, end = end, zone = s.zone, allDay = s.allDay,
            location = text("LOCATION"), description = text("DESCRIPTION"), organizer = org, attendees = att,
            rrule = first("RRULE")?.third?.trim().orEmpty(), alarms = alarms,
        )
    }

    private fun alarmText(p: List<Triple<String, Map<String, String>, String>>): String? =
        p.firstOrNull { it.first == "TRIGGER" }?.let { t ->
            val v = t.third.trim()
            if (v.startsWith("-")) "${v.removePrefix("-")} before" else if (v.startsWith("P")) "$v after" else v
        }
}

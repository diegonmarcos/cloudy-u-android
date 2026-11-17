package com.diegonmarcos.cloudagenda

import android.content.Intent
import android.net.Uri
import com.diegonmarcos.cloudagenda.data.DuplicateChoice
import com.diegonmarcos.cloudagenda.data.EventIntents
import com.diegonmarcos.cloudagenda.data.Ics
import com.diegonmarcos.cloudagenda.data.TasksDb
import com.diegonmarcos.cloudagenda.data.TasksRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** #1766 Cloud Tasks as a calendar app: the Add to calendar extras, ICS files, UID dedupe and the manifest filters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarImportTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun insert() = Intent(Intent.ACTION_INSERT).setData(Uri.parse("content://com.android.calendar/events"))

    // ── Add to calendar extras ──────────────────────────────────────────────────────────────────────────

    @Test fun `extras give title place notes guests and a timed range in the event zone`() {
        val begin = 1_800_000_000_000L
        val i = insert().putExtra("title", "Dinner").putExtra("eventLocation", "Cafe X").putExtra("description", "Bring wine")
            .putExtra("beginTime", begin).putExtra("endTime", begin + 7_200_000L).putExtra("eventTimezone", "Europe/Berlin")
            .putExtra(Intent.EXTRA_EMAIL, "a@x.org, b@y.org")
        val e = EventIntents.fromIntent(i, ZoneId.of("UTC"))!!
        assertEquals("Dinner", e.title)
        assertEquals("Cafe X", e.location)
        assertEquals("Bring wine", e.description)
        assertEquals(listOf("a@x.org", "b@y.org"), e.attendees)
        assertEquals("Europe/Berlin", e.zone)
        assertEquals(LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(begin), berlin), e.start)
        assertEquals(e.start.plusHours(2), e.end)
        assertEquals("", e.uid)
    }

    @Test fun `allDay extras give whole days and the end midnight is folded back one day`() {
        val d = LocalDate.of(2026, 12, 24).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = EventIntents.fromIntent(insert().putExtra("title", "Trip").putExtra("allDay", true).putExtra("beginTime", d).putExtra("endTime", d + 3 * 86_400_000L))!!
        assertTrue(e.allDay)
        assertEquals(LocalDate.of(2026, 12, 24), e.start.toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 26), e.lastDay())
    }

    @Test fun `a missing end is one hour, and a non calendar intent is not an event`() {
        val e = EventIntents.fromIntent(insert().putExtra("beginTime", 1_800_000_000_000L), berlin)!!
        assertEquals(e.start.plusHours(1), e.end)
        assertNull(EventIntents.fromIntent(Intent(Intent.ACTION_VIEW).setData(Uri.parse("https://x.org"))))
        assertNotNull(EventIntents.fromIntent(Intent(Intent.ACTION_EDIT).setType("vnd.android.cursor.item/event")))
    }

    // ── ICS ─────────────────────────────────────────────────────────────────────────────────────────────

    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:one@example.org
        SUMMARY:Planning\, Q1
        DTSTART;TZID=Europe/Berlin:20261201T090000
        DTEND;TZID=Europe/Berlin:20261201T103000
        LOCATION:Room 4
        DESCRIPTION:Line one\nLine two that is long
         and folded
        ORGANIZER;CN=Ann:mailto:ann@example.org
        ATTENDEE;CN=Bob:mailto:bob@example.org
        ATTENDEE:mailto:cy@example.org
        RRULE:FREQ=WEEKLY;COUNT=4
        BEGIN:VALARM
        TRIGGER:-PT15M
        ACTION:DISPLAY
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:two@example.org
        SUMMARY:Holiday
        DTSTART;VALUE=DATE:20261224
        DTEND;VALUE=DATE:20261227
        END:VEVENT
        BEGIN:VEVENT
        UID:three@example.org
        SUMMARY:Call
        DTSTART:20261201T120000Z
        DURATION:PT30M
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test fun `one file with three events yields three in order`() {
        assertEquals(listOf("one@example.org", "two@example.org", "three@example.org"), Ics.parse(ics, berlin).map { it.uid })
    }

    @Test fun `a timed VEVENT keeps its time zone, place, people, rule, reminder and unfolded notes`() {
        val e = Ics.parse(ics, ZoneId.of("UTC")).first()
        assertEquals("Planning, Q1", e.title)
        assertEquals("Europe/Berlin", e.zone)
        assertEquals(LocalDateTime.of(2026, 12, 1, 9, 0), e.start)
        assertEquals(LocalDateTime.of(2026, 12, 1, 10, 30), e.end)
        assertEquals("Room 4", e.location)
        assertEquals("Line one\nLine two that is longand folded", e.description)
        assertEquals("Ann <ann@example.org>", e.organizer)
        assertEquals(listOf("Bob <bob@example.org>", "cy@example.org"), e.attendees)
        assertEquals("FREQ=WEEKLY;COUNT=4", e.rrule)
        assertEquals(listOf("PT15M before"), e.alarms)
    }

    @Test fun `an all day VEVENT ends on the last day, not the exclusive DTEND`() {
        val e = Ics.parse(ics, berlin)[1]
        assertTrue(e.allDay)
        assertEquals(LocalDate.of(2026, 12, 24), e.start.toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 26), e.lastDay())
    }

    @Test fun `a UTC time is shown in the reader zone and a DURATION sets the end`() {
        val e = Ics.parse(ics, berlin)[2]
        assertEquals(LocalDateTime.of(2026, 12, 1, 13, 0), e.start)
        assertEquals(LocalDateTime.of(2026, 12, 1, 13, 30), e.end)
    }

    @Test fun `garbage and events with no start give nothing`() {
        assertEquals(0, Ics.parse("hello").size)
        assertEquals(0, Ics.parse("BEGIN:VEVENT\nSUMMARY:x\nEND:VEVENT").size)
    }

    // ── the store: dated task, UID dedupe ───────────────────────────────────────────────────────────────

    private fun repo() = TasksRepository(TasksDb(RuntimeEnvironment.getApplication(), null))

    @Test fun `an import lands in Inbox as a dated task the calendars read`() {
        val r = repo()
        val (a, u, k) = r.importEvents(Ics.parse(ics, berlin), r.inboxListId(), DuplicateChoice.UPDATE)
        assertEquals(Triple(3, 0, 0), Triple(a, u, k))
        val s = r.snapshot()
        assertEquals("Inbox", s.project(s.list(s.tasks.first().listId)!!.projectId)!!.name)
        val hol = s.tasks.first { it.name == "Holiday" }
        assertEquals("2026-12-24", hol.dateTarget)
        assertEquals("2026-12-26", hol.dateDeadline)
        assertTrue(s.tasks.first { it.name == "Planning, Q1" }.details.contains("Repeats: FREQ=WEEKLY;COUNT=4"))
    }

    @Test fun `the same UID updates in place or is skipped, never doubled`() {
        val r = repo()
        val list = r.inboxListId()
        val events = Ics.parse(ics, berlin)
        r.importEvents(events, list, DuplicateChoice.UPDATE)
        val changed = events.map { it.copy(title = it.title + " v2") }
        assertEquals(Triple(0, 3, 0), r.importEvents(changed, list, DuplicateChoice.UPDATE))
        assertEquals(3, r.snapshot().tasks.size)
        assertTrue(r.snapshot().tasks.all { it.name.endsWith(" v2") || it.name.endsWith("v2") })
        assertEquals(Triple(0, 0, 3), r.importEvents(changed.map { it.copy(title = "x") }, list, DuplicateChoice.SKIP))
        assertTrue(r.snapshot().tasks.none { it.name == "x" })
    }

    @Test fun `an event with no UID is always new`() {
        val r = repo()
        val e = EventIntents.fromIntent(insert().putExtra("title", "A").putExtra("beginTime", 1_800_000_000_000L), berlin)!!
        r.importEvents(listOf(e), r.inboxListId(), DuplicateChoice.UPDATE)
        assertEquals(Triple(1, 0, 0), r.importEvents(listOf(e), r.inboxListId(), DuplicateChoice.UPDATE))
        assertEquals(2, r.snapshot().tasks.size)
    }

    @Test fun `Inbox is made once and reused`() {
        val r = repo()
        assertEquals(r.inboxListId(), r.inboxListId())
        assertEquals(1, r.snapshot().projects.count { it.name == "Inbox" })
    }

    // ── manifest guard ──────────────────────────────────────────────────────────────────────────────────

    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val filters = Regex("<intent-filter>.*?</intent-filter>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.value }.toList()

    private fun has(action: String, vararg needles: String) = filters.any { f ->
        f.contains("android.intent.action.$action\"") && needles.all { f.contains(it) }
    }

    @Test fun `the manifest registers Cloud Tasks as a calendar app`() {
        for (a in listOf("INSERT", "EDIT")) {
            assertTrue(a, has(a, "vnd.android.cursor.dir/event", "vnd.android.cursor.item/event", "category.DEFAULT"))
        }
        assertTrue(has("VIEW", "android:host=\"com.android.calendar\"", "android:pathPrefix=\"/events\""))
        assertTrue(has("VIEW", "time/epoch", "android:host=\"com.android.calendar\""))
        for (a in listOf("VIEW", "SEND")) assertTrue(a, has(a, "text/calendar", "application/ics", "text/x-vcalendar"))
        assertTrue(has("VIEW", "android:scheme=\"content\"", "android:scheme=\"file\"", "text/calendar"))
        assertTrue(has("VIEW", "pathPattern", ".ics"))
        assertTrue(has("MAIN", "android.intent.category.APP_CALENDAR"))
        assertTrue(has("MAIN", "android.intent.category.LAUNCHER"))
    }

    @Test fun `the manifest declares the type-less Cloud Mail form and a singleTask activity`() {
        for (a in listOf("INSERT", "EDIT")) assertTrue(a, has(a, "android:scheme=\"content\"", "android:host=\"com.android.calendar\"", "android:pathPrefix=\"/events\""))
        assertTrue(manifest.contains("android:launchMode=\"singleTask\""))
    }

    @Test fun `a type-less insert as Cloud Mail sends it, and int or string extras, still read`() {
        val b = 1_800_000_000_000L
        val e = EventIntents.fromIntent(insert().putExtra("title", "Design review").putExtra("beginTime", b).putExtra("endTime", b + 3_600_000L)
            .putExtra("eventLocation", "Room 4").putExtra("description", "Plan").putExtra("allDay", false), ZoneId.of("UTC"))!!
        assertEquals("Design review", e.title); assertEquals("Room 4", e.location); assertEquals(false, e.allDay)
        assertEquals(60L, java.time.Duration.between(e.start, e.end).toMinutes())
        val s = EventIntents.fromIntent(insert().putExtra("beginTime", b.toString()).putExtra("allDay", "true"), ZoneId.of("UTC"))!!
        assertTrue(s.allDay)
        assertNotNull(EventIntents.fromIntent(insert().putExtra("beginTime", 1_000_000)))
    }

    @Test fun `no calendar provider permission is declared`() {
        assertTrue(!manifest.contains("READ_CALENDAR") && !manifest.contains("WRITE_CALENDAR"))
    }
}

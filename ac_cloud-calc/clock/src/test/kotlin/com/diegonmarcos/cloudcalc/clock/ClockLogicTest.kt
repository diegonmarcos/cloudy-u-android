package com.diegonmarcos.cloudcalc.clock

import com.diegonmarcos.cloudcalc.clock.ClockLogic.Effect
import com.diegonmarcos.cloudcalc.clock.ClockLogic.MINUTE_MS
import com.diegonmarcos.cloudcalc.clock.ClockLogic.PhaseKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The Clock rules against hand-worked instants. PIT mutates ClockLogic/ClockCodec and runs this
 * suite against every mutant (clock/build.gradle), so an assertion that cannot tell a mutant from
 * the original shows up as a surviving mutant, not as a green tick.
 */
class ClockLogicTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val utc = ZoneId.of("UTC")
    private fun at(zone: ZoneId, y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()
    private val ring = 10 * MINUTE_MS

    // 2026-10-02 is a Friday.
    private val friNoon = at(madrid, 2026, 10, 2, 12, 0)

    // ── alarms ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a one-shot alarm rings today if its time is still ahead, else tomorrow`() {
        assertEquals(at(madrid, 2026, 10, 2, 18, 30), ClockLogic.nextOccurrence(Alarm(1, 18 * 60 + 30), friNoon, madrid))
        assertEquals(at(madrid, 2026, 10, 3, 7, 0), ClockLogic.nextOccurrence(Alarm(1, 7 * 60), friNoon, madrid))
    }

    @Test fun `an alarm at exactly now is the next day, never now`() {
        assertEquals(at(madrid, 2026, 10, 3, 12, 0), ClockLogic.nextOccurrence(Alarm(1, 12 * 60), friNoon, madrid))
    }

    @Test fun `repeat days skip to the next listed weekday`() {
        val mon = ClockLogic.dayBit(DayOfWeek.MONDAY)
        val weekdays = Alarm(1, 7 * 60, days = ClockLogic.WEEKDAYS)
        // Friday noon, 07:00 weekdays -> Monday 5th.
        assertEquals(at(madrid, 2026, 10, 5, 7, 0), ClockLogic.nextOccurrence(weekdays, friNoon, madrid))
        assertEquals(at(madrid, 2026, 10, 5, 7, 0), ClockLogic.nextOccurrence(Alarm(1, 7 * 60, days = mon), friNoon, madrid))
        // Friday only, already past today -> next Friday, 7 days on (the eighth day of the loop).
        val fri = Alarm(1, 7 * 60, days = ClockLogic.dayBit(DayOfWeek.FRIDAY))
        assertEquals(at(madrid, 2026, 10, 9, 7, 0), ClockLogic.nextOccurrence(fri, friNoon, madrid))
        // Friday, still ahead today.
        assertEquals(at(madrid, 2026, 10, 2, 20, 0), ClockLogic.nextOccurrence(fri.copy(minuteOfDay = 20 * 60), friNoon, madrid))
        // Weekend -> Saturday.
        assertEquals(at(madrid, 2026, 10, 3, 9, 0), ClockLogic.nextOccurrence(Alarm(1, 9 * 60, days = ClockLogic.WEEKEND), friNoon, madrid))
    }

    @Test fun `day bits are Monday first`() {
        assertEquals(1, ClockLogic.dayBit(DayOfWeek.MONDAY))
        assertEquals(64, ClockLogic.dayBit(DayOfWeek.SUNDAY))
        assertEquals(ClockLogic.ALL_DAYS, ClockLogic.WEEKDAYS or ClockLogic.WEEKEND)
    }

    @Test fun `alarm times are local wall time across a DST change`() {
        // Europe/Madrid springs forward 2026-03-29 02:00 -> 03:00 and falls back 2026-10-25 03:00 -> 02:00.
        val sat = at(madrid, 2026, 3, 28, 12, 0)
        val seven = Alarm(1, 7 * 60)
        assertEquals(at(madrid, 2026, 3, 29, 7, 0), ClockLogic.nextOccurrence(seven, sat, madrid))
        // 23 hours of real time between Saturday's and Sunday's 07:00.
        val sat7 = at(madrid, 2026, 3, 28, 7, 0)
        assertEquals(23 * 60 * MINUTE_MS, ClockLogic.nextOccurrence(seven, sat7, madrid) - sat7)
        // 02:30 does not exist that night: it rings at 03:30 local, the gap's length later.
        val gap = ClockLogic.nextOccurrence(Alarm(1, 2 * 60 + 30), sat, madrid)
        assertEquals(at(madrid, 2026, 3, 29, 3, 30), gap)
        // Fall back: 25 hours between the two 07:00s.
        val oct24 = at(madrid, 2026, 10, 24, 7, 0)
        assertEquals(25 * 60 * MINUTE_MS, ClockLogic.nextOccurrence(seven, oct24, madrid) - oct24)
    }

    @Test fun `alarmAt is null when off and takes a sooner snooze`() {
        val a = Alarm(1, 7 * 60)
        assertNull(ClockLogic.alarmAt(a.copy(enabled = false), friNoon, madrid))
        val snooze = friNoon + 5 * MINUTE_MS
        assertEquals(snooze, ClockLogic.alarmAt(a.copy(snoozedUntil = snooze), friNoon, madrid))
        // A snooze already in the past is ignored.
        assertEquals(at(madrid, 2026, 10, 3, 7, 0), ClockLogic.alarmAt(a.copy(snoozedUntil = friNoon - 1), friNoon, madrid))
        // A snooze later than the next occurrence loses to it.
        val late = at(madrid, 2026, 10, 4, 0, 0)
        assertEquals(at(madrid, 2026, 10, 3, 7, 0), ClockLogic.alarmAt(a.copy(snoozedUntil = late), friNoon, madrid))
    }

    @Test fun `nextAlarm is the soonest ringing alarm and ignores quiet ones`() {
        val d = ClockData(alarms = listOf(
            Alarm(1, 9 * 60), Alarm(2, 13 * 60), Alarm(3, 12 * 60 + 30, quiet = true), Alarm(4, 12 * 60 + 10, enabled = false),
        ))
        val (a, t) = ClockLogic.nextAlarm(d, friNoon, madrid)!!
        assertEquals(2L, a.id)
        assertEquals(at(madrid, 2026, 10, 2, 13, 0), t)
        assertNull(ClockLogic.nextAlarm(ClockData(), friNoon, madrid))
    }

    // ── timers ──────────────────────────────────────────────────────────────────────────────

    @Test fun `a timer counts down, pauses, resumes and gains a minute`() {
        val t0 = Timer(7, "tea", 5 * MINUTE_MS)
        assertEquals(5 * MINUTE_MS, ClockLogic.remaining(t0, friNoon))
        val run = ClockLogic.start(t0, friNoon)
        assertEquals(TimerState.RUNNING, run.state)
        assertEquals(friNoon + 5 * MINUTE_MS, run.endsAt)
        assertEquals(3 * MINUTE_MS, ClockLogic.remaining(run, friNoon + 2 * MINUTE_MS))
        assertEquals(0L, ClockLogic.remaining(run, friNoon + 9 * MINUTE_MS))
        // Starting a running timer again changes nothing.
        assertEquals(run, ClockLogic.start(run, friNoon + MINUTE_MS))
        val paused = ClockLogic.pause(run, friNoon + 2 * MINUTE_MS)
        assertEquals(TimerState.PAUSED, paused.state)
        assertEquals(3 * MINUTE_MS, paused.remainingMs)
        assertEquals(0L, paused.endsAt)
        // Time passes while paused: nothing moves.
        assertEquals(3 * MINUTE_MS, ClockLogic.remaining(paused, friNoon + 60 * MINUTE_MS))
        assertEquals(paused, ClockLogic.pause(paused, friNoon + 61 * MINUTE_MS))
        val resumed = ClockLogic.start(paused, friNoon + 10 * MINUTE_MS)
        assertEquals(friNoon + 13 * MINUTE_MS, resumed.endsAt)
        assertEquals(friNoon + 14 * MINUTE_MS, ClockLogic.addMinute(resumed, friNoon).endsAt)
        assertEquals(4 * MINUTE_MS, ClockLogic.addMinute(paused, friNoon).remainingMs)
        assertEquals(6 * MINUTE_MS, ClockLogic.addMinute(t0, friNoon).remainingMs)
        val reset = ClockLogic.reset(resumed)
        assertEquals(TimerState.IDLE, reset.state)
        assertEquals(5 * MINUTE_MS, reset.remainingMs)
        assertEquals(0L, reset.endsAt)
    }

    @Test fun `a ringing timer reads zero and +1 minute restarts it`() {
        val ringing = Timer(7, "", 5 * MINUTE_MS, TimerState.RINGING, 0, 0)
        assertEquals(0L, ClockLogic.remaining(ringing, friNoon))
        val again = ClockLogic.addMinute(ringing, friNoon)
        assertEquals(TimerState.RUNNING, again.state)
        assertEquals(friNoon + MINUTE_MS, again.endsAt)
        assertEquals(ringing, ClockLogic.start(ringing, friNoon))
    }

    // ── stopwatch ───────────────────────────────────────────────────────────────────────────

    @Test fun `the stopwatch accumulates across pauses and records laps`() {
        var s = Stopwatch()
        assertFalse(s.running)
        assertEquals(s, ClockLogic.lap(s, friNoon))
        s = ClockLogic.toggle(s, friNoon)
        assertTrue(s.running)
        assertEquals(1_500L, ClockLogic.elapsed(s, friNoon + 1_500))
        s = ClockLogic.lap(s, friNoon + 1_500)
        s = ClockLogic.lap(s, friNoon + 4_000)
        s = ClockLogic.toggle(s, friNoon + 5_000)
        assertFalse(s.running)
        assertEquals(5_000L, s.accumulatedMs)
        assertEquals(5_000L, ClockLogic.elapsed(s, friNoon + 99_000))
        s = ClockLogic.toggle(s, friNoon + 10_000)
        assertEquals(7_000L, ClockLogic.elapsed(s, friNoon + 12_000))
        assertEquals(listOf(1_500L, 4_000L), s.laps)
        assertEquals(listOf(1_500L, 2_500L), ClockLogic.lapTimes(s))
        assertEquals(emptyList<Long>(), ClockLogic.lapTimes(Stopwatch()))
    }

    // ── interval ────────────────────────────────────────────────────────────────────────────

    private val pomodoro = Interval("Pomodoro", 25 * MINUTE_MS, 5 * MINUTE_MS, 4, 15 * MINUTE_MS, 2, friNoon)

    @Test fun `an interval session is work, rest, long rest every n, and no rest after the last round`() {
        val p = ClockLogic.phases(pomodoro)
        assertEquals(
            listOf(PhaseKind.WORK, PhaseKind.REST, PhaseKind.WORK, PhaseKind.LONG_REST, PhaseKind.WORK, PhaseKind.REST, PhaseKind.WORK),
            p.map { it.kind },
        )
        assertEquals(listOf(1, 1, 2, 2, 3, 3, 4), p.map { it.round })
        assertEquals(friNoon, p.first().startsAt)
        assertEquals(friNoon + (4 * 25 + 5 + 15 + 5) * MINUTE_MS, p.last().endsAt)
        p.zipWithNext().forEach { (a, b) -> assertEquals(a.endsAt, b.startsAt) }
        // No rest declared: work back to back.
        assertEquals(3, ClockLogic.phases(pomodoro.copy(restMs = 0, longEvery = 0, rounds = 3)).size)
        // A long rest needs both a period and a length.
        assertTrue(ClockLogic.phases(pomodoro.copy(longRestMs = 0)).none { it.kind == PhaseKind.LONG_REST })
        assertTrue(ClockLogic.phases(pomodoro.copy(longEvery = 0)).none { it.kind == PhaseKind.LONG_REST })
        assertEquals(1, ClockLogic.phases(pomodoro.copy(rounds = 1)).size)
    }

    @Test fun `phaseAt walks the session and is null after it`() {
        assertEquals(PhaseKind.WORK, ClockLogic.phaseAt(pomodoro, friNoon)!!.kind)
        val rest = ClockLogic.phaseAt(pomodoro, friNoon + 25 * MINUTE_MS)!!
        assertEquals(PhaseKind.REST, rest.kind)
        assertEquals(friNoon + 30 * MINUTE_MS, rest.endsAt)
        assertEquals(PhaseKind.LONG_REST, ClockLogic.phaseAt(pomodoro, friNoon + 56 * MINUTE_MS)!!.kind)
        assertNull(ClockLogic.phaseAt(pomodoro, friNoon + 150 * MINUTE_MS))
    }

    // ── plan ────────────────────────────────────────────────────────────────────────────────

    @Test fun `the plan holds one wakeup per thing that must happen`() {
        val d = ClockData(
            alarms = listOf(Alarm(1, 13 * 60), Alarm(2, 23 * 60, quiet = true), Alarm(3, 8 * 60, enabled = false)),
            timers = listOf(
                Timer(5, "", MINUTE_MS, TimerState.RUNNING, friNoon + MINUTE_MS, 0),
                Timer(6, "", MINUTE_MS, TimerState.PAUSED, 0, 30_000),
                Timer(7, "", MINUTE_MS),
            ),
            interval = pomodoro,
            sleepUntil = friNoon + 30 * MINUTE_MS,
            ringing = listOf(Ring("timer:9", "", friNoon - MINUTE_MS), Ring("alarm:8", "", friNoon - 2 * MINUTE_MS)),
        )
        val plan = ClockLogic.plan(d, friNoon, madrid, ring).associateBy { it.key }
        assertEquals(setOf("alarm:1", "alarm:2", "timer:5", "interval", "sleep", "silence"), plan.keys)
        assertEquals(ClockLogic.Wakeup("alarm:1", at(madrid, 2026, 10, 2, 13, 0), true), plan["alarm:1"])
        assertEquals(ClockLogic.Wakeup("alarm:2", at(madrid, 2026, 10, 2, 23, 0), false), plan["alarm:2"])
        assertEquals(ClockLogic.Wakeup("timer:5", friNoon + MINUTE_MS, false), plan["timer:5"])
        assertEquals(friNoon + 25 * MINUTE_MS, plan.getValue("interval").at)
        assertEquals(friNoon + 30 * MINUTE_MS, plan.getValue("sleep").at)
        // The oldest ring decides when the auto-silence comes.
        assertEquals(friNoon - 2 * MINUTE_MS + ring, plan.getValue("silence").at)
        assertTrue(ClockLogic.plan(ClockData(), friNoon, madrid, ring).isEmpty())
    }

    @Test fun `an interval already over is finished by an immediate wakeup`() {
        val over = ClockData(interval = pomodoro.copy(startedAt = friNoon - 500 * MINUTE_MS))
        assertEquals(listOf(ClockLogic.Wakeup("interval", friNoon, false)), ClockLogic.plan(over, friNoon, madrid, ring))
    }

    // ── fire ────────────────────────────────────────────────────────────────────────────────

    @Test fun `a due one-shot alarm rings once and switches off, a repeating one stays on`() {
        val sevenOnce = Alarm(1, 7 * 60, label = "wake")
        val seven = at(madrid, 2026, 10, 3, 7, 0)
        val f = ClockLogic.fire(ClockData(alarms = listOf(sevenOnce)), "alarm:1", seven, madrid, ring)
        assertEquals(Effect.RING, f.effect)
        assertEquals("wake", f.label)
        assertEquals(listOf(Ring("alarm:1", "wake", seven)), f.data.ringing)
        assertFalse(f.data.alarms.single().enabled)
        val daily = sevenOnce.copy(days = ClockLogic.ALL_DAYS, snoozedUntil = seven - 1)
        val g = ClockLogic.fire(ClockData(alarms = listOf(daily)), "alarm:1", seven, madrid, ring)
        assertTrue(g.data.alarms.single().enabled)
        assertEquals(0L, g.data.alarms.single().snoozedUntil)
        // After it rang, today's 07:00 is behind: the next plan is tomorrow's.
        assertEquals(seven + 24 * 60 * MINUTE_MS, ClockLogic.plan(g.data, seven, madrid, ring).first { it.key == "alarm:1" }.at)
    }

    @Test fun `a late delivery inside the ring window still rings`() {
        val seven = at(madrid, 2026, 10, 3, 7, 0)
        val d = ClockData(alarms = listOf(Alarm(1, 7 * 60)))
        assertEquals(Effect.RING, ClockLogic.fire(d, "alarm:1", seven + 9 * MINUTE_MS, madrid, ring).effect)
        assertEquals(Effect.NONE, ClockLogic.fire(d, "alarm:1", seven + 11 * MINUTE_MS, madrid, ring).effect)
    }

    @Test fun `a stale or early alarm wakeup changes nothing`() {
        val d = ClockData(alarms = listOf(Alarm(1, 7 * 60), Alarm(2, 7 * 60, enabled = false)))
        // Early: six in the morning.
        val six = at(madrid, 2026, 10, 3, 6, 0)
        assertEquals(ClockLogic.Fired(d, Effect.NONE), ClockLogic.fire(d, "alarm:1", six, madrid, ring))
        // Switched off, deleted.
        val seven = at(madrid, 2026, 10, 3, 7, 0)
        assertEquals(Effect.NONE, ClockLogic.fire(d, "alarm:2", seven, madrid, ring).effect)
        assertEquals(Effect.NONE, ClockLogic.fire(d, "alarm:99", seven, madrid, ring).effect)
        assertEquals(Effect.NONE, ClockLogic.fire(d, "nonsense", seven, madrid, ring).effect)
    }

    @Test fun `a quiet alarm notifies instead of ringing`() {
        val bed = Alarm(1, 23 * 60, days = ClockLogic.ALL_DAYS, quiet = true, label = "Bedtime")
        val f = ClockLogic.fire(ClockData(alarms = listOf(bed)), "alarm:1", at(madrid, 2026, 10, 2, 23, 0), madrid, ring)
        assertEquals(Effect.NOTIFY, f.effect)
        assertEquals("Bedtime", f.label)
        assertTrue(f.data.ringing.isEmpty())
    }

    @Test fun `a due timer rings and an early or paused one does not`() {
        val run = Timer(5, "eggs", MINUTE_MS, TimerState.RUNNING, friNoon + MINUTE_MS, 0)
        val d = ClockData(timers = listOf(run, Timer(6, "", MINUTE_MS, TimerState.PAUSED, 0, 1000)))
        assertEquals(Effect.NONE, ClockLogic.fire(d, "timer:5", friNoon, madrid, ring).effect)
        // A wakeup within EARLY_MS of the end counts as the end.
        val f = ClockLogic.fire(d, "timer:5", friNoon + MINUTE_MS - ClockLogic.EARLY_MS, madrid, ring)
        assertEquals(Effect.RING, f.effect)
        assertEquals("eggs", f.label)
        val t = f.data.timers.first()
        assertEquals(TimerState.RINGING, t.state)
        assertEquals(0L, t.remainingMs)
        assertEquals(listOf("timer:5"), f.data.ringing.map { it.key })
        assertEquals(Effect.NONE, ClockLogic.fire(d, "timer:6", friNoon + MINUTE_MS, madrid, ring).effect)
        assertEquals(Effect.NONE, ClockLogic.fire(d, "timer:5", friNoon + MINUTE_MS - ClockLogic.EARLY_MS - 1, madrid, ring).effect)
    }

    @Test fun `interval wakeups beep between phases and ring at the end`() {
        val d = ClockData(interval = pomodoro)
        val beep = ClockLogic.fire(d, "interval", friNoon + 25 * MINUTE_MS, madrid, ring)
        assertEquals(Effect.BEEP, beep.effect)
        assertEquals(d, beep.data)
        val end = pomodoro.startedAt + 125 * MINUTE_MS
        val done = ClockLogic.fire(d, "interval", end, madrid, ring)
        assertEquals(Effect.RING, done.effect)
        assertNull(done.data.interval)
        assertEquals(listOf("interval"), done.data.ringing.map { it.key })
        // One second before the end counts as the end.
        assertEquals(Effect.RING, ClockLogic.fire(d, "interval", end - ClockLogic.EARLY_MS, madrid, ring).effect)
        assertEquals(Effect.NONE, ClockLogic.fire(ClockData(), "interval", end, madrid, ring).effect)
    }

    @Test fun `a new ring joins the rings already sounding and replaces only its own`() {
        val seven = at(madrid, 2026, 10, 3, 7, 0)
        val other = Ring("timer:9", "", seven - MINUTE_MS)
        val stale = Ring("alarm:1", "", seven - 3 * MINUTE_MS)
        val d = ClockData(
            alarms = listOf(Alarm(1, 7 * 60)),
            timers = listOf(Timer(5, "", MINUTE_MS, TimerState.RUNNING, seven, 0)),
            interval = pomodoro.copy(startedAt = seven - 125 * MINUTE_MS),
            ringing = listOf(other, stale),
        )
        assertEquals(listOf(other, Ring("alarm:1", "", seven)), ClockLogic.fire(d, "alarm:1", seven, madrid, ring).data.ringing)
        assertEquals(listOf(other, stale, Ring("timer:5", "", seven)), ClockLogic.fire(d, "timer:5", seven, madrid, ring).data.ringing)
        assertEquals(listOf(other, stale, Ring("interval", "Pomodoro", seven)), ClockLogic.fire(d, "interval", seven, madrid, ring).data.ringing)
    }

    @Test fun `the sleep timer pauses media once, when due`() {
        val d = ClockData(sleepUntil = friNoon + MINUTE_MS)
        assertEquals(Effect.NONE, ClockLogic.fire(d, "sleep", friNoon, madrid, ring).effect)
        val f = ClockLogic.fire(d, "sleep", friNoon + MINUTE_MS, madrid, ring)
        assertEquals(Effect.PAUSE_MEDIA, f.effect)
        assertEquals(0L, f.data.sleepUntil)
        assertEquals(Effect.NONE, ClockLogic.fire(f.data, "sleep", friNoon + MINUTE_MS, madrid, ring).effect)
    }

    @Test fun `rings that outlive the ring window are silenced and their timers reset`() {
        val d = ClockData(
            timers = listOf(Timer(5, "", 3 * MINUTE_MS, TimerState.RINGING, 0, 0)),
            ringing = listOf(Ring("timer:5", "", friNoon - ring), Ring("alarm:1", "", friNoon - MINUTE_MS)),
        )
        val f = ClockLogic.fire(d, "silence", friNoon, madrid, ring)
        assertEquals(Effect.SILENCE, f.effect)
        assertEquals(listOf("alarm:1"), f.data.ringing.map { it.key })
        assertEquals(TimerState.IDLE, f.data.timers.single().state)
        assertEquals(3 * MINUTE_MS, f.data.timers.single().remainingMs)
        assertEquals(Effect.NONE, ClockLogic.fire(f.data, "silence", friNoon, madrid, ring).effect)
    }

    @Test fun `dismiss and snooze`() {
        val d = ClockData(
            alarms = listOf(Alarm(1, 7 * 60, enabled = false, snoozeMinutes = 9)),
            timers = listOf(Timer(5, "", 3 * MINUTE_MS, TimerState.RINGING, 0, 0), Timer(6, "", MINUTE_MS, TimerState.RINGING, 0, 0)),
            ringing = listOf(Ring("alarm:1", "", friNoon), Ring("timer:5", "", friNoon), Ring("timer:6", "", friNoon)),
        )
        val s = ClockLogic.snooze(d, "alarm:1", friNoon)
        assertEquals(friNoon + 9 * MINUTE_MS, s.alarms.single().snoozedUntil)
        assertTrue("a snoozed one-shot is switched back on", s.alarms.single().enabled)
        assertEquals(listOf("timer:5", "timer:6"), s.ringing.map { it.key })
        assertEquals(friNoon + 9 * MINUTE_MS, ClockLogic.alarmAt(s.alarms.single(), friNoon, madrid))
        val x = ClockLogic.dismiss(s, "timer:5")
        assertEquals(listOf("timer:6"), x.ringing.map { it.key })
        assertEquals(TimerState.IDLE, x.timers[0].state)
        assertEquals(3 * MINUTE_MS, x.timers[0].remainingMs)
        assertEquals(TimerState.RINGING, x.timers[1].state)
        // Snoozing a timer's ring is a dismiss.
        assertEquals(ClockLogic.dismiss(s, "timer:6"), ClockLogic.snooze(s, "timer:6", friNoon))
    }

    // ── text ────────────────────────────────────────────────────────────────────────────────

    @Test fun `countdowns round up and stopwatches show hundredths`() {
        assertEquals("5:00", ClockLogic.countdown(5 * MINUTE_MS))
        assertEquals("0:01", ClockLogic.countdown(1))
        assertEquals("0:00", ClockLogic.countdown(0))
        assertEquals("0:00", ClockLogic.countdown(-5))
        assertEquals("1:00:00", ClockLogic.countdown(60 * MINUTE_MS))
        assertEquals("1:01:01", ClockLogic.countdown(3_661_000))
        assertEquals("0:01.23", ClockLogic.stopwatchText(1_234))
        assertEquals("1:00:00.00", ClockLogic.stopwatchText(3_600_000))
        assertEquals("0:00.00", ClockLogic.stopwatchText(-1))
        assertEquals("07:05", ClockLogic.timeText(7 * 60 + 5))
    }

    @Test fun `repeat days read as words`() {
        val short = { d: DayOfWeek -> d.name.take(3) }
        assertEquals("Once", ClockLogic.daysText(0, DayOfWeek.MONDAY, short))
        assertEquals("Every day", ClockLogic.daysText(ClockLogic.ALL_DAYS, DayOfWeek.MONDAY, short))
        assertEquals("Weekdays", ClockLogic.daysText(ClockLogic.WEEKDAYS, DayOfWeek.MONDAY, short))
        assertEquals("Weekends", ClockLogic.daysText(ClockLogic.WEEKEND, DayOfWeek.MONDAY, short))
        val monSun = ClockLogic.dayBit(DayOfWeek.MONDAY) or ClockLogic.dayBit(DayOfWeek.SUNDAY)
        assertEquals("MON SUN", ClockLogic.daysText(monSun, DayOfWeek.MONDAY, short))
        assertEquals("SUN MON", ClockLogic.daysText(monSun, DayOfWeek.SUNDAY, short))
        assertEquals(DayOfWeek.SUNDAY, ClockLogic.week(DayOfWeek.SUNDAY).first())
        assertEquals(7, ClockLogic.week(DayOfWeek.MONDAY).toSet().size)
    }

    @Test fun `world clock text`() {
        assertEquals("Buenos Aires", ClockLogic.city("America/Argentina/Buenos_Aires"))
        assertEquals("UTC", ClockLogic.city("UTC"))
        val tokyo = ZoneId.of("Asia/Tokyo")
        val ny = ZoneId.of("America/New_York")
        // 2026-10-02: Madrid CEST (+2), Tokyo +9, New York EDT (-4), Kolkata +5:30.
        assertEquals("+7 h", ClockLogic.offsetText(tokyo, madrid, friNoon))
        assertEquals("−6 h", ClockLogic.offsetText(ny, madrid, friNoon))
        assertEquals("+3:30 h", ClockLogic.offsetText(ZoneId.of("Asia/Kolkata"), madrid, friNoon))
        assertEquals("−0:30 h", ClockLogic.offsetText(ZoneId.of("UTC"), ZoneId.of("+00:30"), friNoon))
        assertEquals("Same time", ClockLogic.offsetText(madrid, ZoneId.of("Europe/Paris"), friNoon))
        assertEquals("UTC+9", ClockLogic.utcText(tokyo, friNoon))
        assertEquals("UTC−4", ClockLogic.utcText(ny, friNoon)) // EDT in October
        assertEquals("UTC+5:30", ClockLogic.utcText(ZoneId.of("Asia/Kolkata"), friNoon))
        assertEquals("UTC−2:30", ClockLogic.utcText(ZoneId.of("America/St_Johns"), friNoon)) // NDT
        assertEquals("UTC", ClockLogic.utcText(utc, friNoon))
        assertEquals(true, ClockLogic.isDay(madrid, friNoon))
        assertEquals(false, ClockLogic.isDay(tokyo, friNoon)) // 19:00 in Tokyo
        assertEquals(true, ClockLogic.isDay(madrid, at(madrid, 2026, 10, 2, 6, 0)))
        assertEquals(false, ClockLogic.isDay(madrid, at(madrid, 2026, 10, 2, 5, 59)))
        assertEquals(true, ClockLogic.isDay(madrid, at(madrid, 2026, 10, 2, 17, 59)))
        assertEquals(false, ClockLogic.isDay(madrid, at(madrid, 2026, 10, 2, 18, 0)))
        // 23:00 in Madrid is already tomorrow in Tokyo and still today in New York.
        val late = at(madrid, 2026, 10, 2, 23, 0)
        assertEquals(1, ClockLogic.dayShift(tokyo, madrid, late))
        assertEquals(0, ClockLogic.dayShift(ny, madrid, late))
        assertEquals(-1, ClockLogic.dayShift(ny, madrid, at(madrid, 2026, 10, 2, 3, 0)))
        assertEquals(0, ClockLogic.dayShift(utc, madrid, friNoon))
    }

    @Test fun `the sleep window runs from the bedtime reminder to the first alarm after it`() {
        val d = ClockData(alarms = listOf(
            Alarm(1, 23 * 60, days = ClockLogic.ALL_DAYS, quiet = true),
            Alarm(2, 7 * 60, days = ClockLogic.ALL_DAYS),
            Alarm(3, 22 * 60),
        ))
        val (bed, wake) = ClockLogic.sleepWindow(d, friNoon, madrid)!!
        assertEquals(at(madrid, 2026, 10, 2, 23, 0), bed)
        assertEquals(at(madrid, 2026, 10, 3, 7, 0), wake)
        assertNull(ClockLogic.sleepWindow(d.copy(alarms = d.alarms.filter { !it.quiet }), friNoon, madrid))
        assertNull(ClockLogic.sleepWindow(d.copy(alarms = d.alarms.filter { it.quiet }), friNoon, madrid))
    }

    @Test fun `times parse as 24-hour hh mm only`() {
        assertEquals(7 * 60 + 5, ClockLogic.parseTime("07:05"))
        assertEquals(23 * 60 + 59, ClockLogic.parseTime(" 23:59 "))
        assertEquals(0, ClockLogic.parseTime("0:00"))
        assertNull(ClockLogic.parseTime("24:00"))
        assertNull(ClockLogic.parseTime("12:60"))
        assertNull(ClockLogic.parseTime("7"))
        assertNull(ClockLogic.parseTime("07:05pm"))
    }

    // ── codec: what survives process death ──────────────────────────────────────────────────

    @Test fun `everything round-trips through the stored JSON`() {
        val d = ClockData(
            alarms = listOf(Alarm(1, 7 * 60, ClockLogic.WEEKDAYS, false, "work", false, Alarm.SILENT, 15, true, 42)),
            timers = listOf(Timer(5, "tea", 3 * MINUTE_MS, TimerState.RUNNING, friNoon, 0), Timer(6, "", 9, TimerState.PAUSED, 0, 4)),
            stopwatch = Stopwatch(friNoon, 1234, listOf(1, 2, 3)),
            interval = pomodoro,
            sleepUntil = friNoon + 1,
            zones = listOf("Asia/Tokyo", "UTC"),
            ringing = listOf(Ring("timer:5", "tea", friNoon)),
            seq = 99,
        )
        assertEquals(d, ClockCodec.decode(ClockCodec.encode(d)))
        assertEquals(ClockData(), ClockCodec.decode(ClockCodec.encode(ClockData())))
        assertNull(ClockCodec.decode(ClockCodec.encode(ClockData())).zones)
    }

    @Test fun `an unreadable store is an empty clock, not a crash`() {
        assertEquals(ClockData(), ClockCodec.decode(null))
        assertEquals(ClockData(), ClockCodec.decode("not json"))
        assertEquals(ClockData(), ClockCodec.decode("""{"timers":[{"id":1,"duration":5,"state":"MELTED"}]}"""))
        // Missing optional fields take their defaults.
        val a = ClockCodec.decode("""{"alarms":[{"id":3,"minute":420}]}""").alarms.single()
        assertEquals(Alarm(3, 420), a)
    }
}

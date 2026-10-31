package com.diegonmarcos.cloudcalc.clock

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs

/**
 * Every Clock rule that is not Android: when an alarm next rings, what a timer has left, which
 * interval phase is running, what fires and what it changes. Pure functions over [ClockData] and a
 * `now`, so the JVM suite — and PIT's mutants of it — hold each one without a device.
 *
 * The scheduling shape is Fossify Clock's (GPL-3.0, evaluated in NOTICE.md): an alarm is an
 * AlarmManager alarm CLOCK (Doze-exempt, shown in the status bar), everything else an exact
 * allow-while-idle wakeup, and every boot / time change re-plans from the stored state.
 */
object ClockLogic {
    const val SECOND_MS = 1_000L
    const val MINUTE_MS = 60_000L
    const val ALL_DAYS = 0x7F
    const val WEEKDAYS = 0x1F
    const val WEEKEND = 0x60

    /** A wakeup fired up to this early is still due: AlarmManager may round, a test clock may not. */
    const val EARLY_MS = SECOND_MS

    fun dayBit(d: DayOfWeek): Int = 1 shl (d.value - 1)

    // ── alarms ──────────────────────────────────────────────────────────────────────────────

    /** The first instant strictly after [now] at which [a]'s time falls on one of its days. */
    fun nextOccurrence(a: Alarm, now: Long, zone: ZoneId): Long {
        val mask = a.days and ALL_DAYS
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = LocalTime.of(a.minuteOfDay / 60, a.minuteOfDay % 60)
        // Eight days: today's time may already have passed and the only listed day be today.
        for (d in 0L..7L) {
            val date = today.plusDays(d)
            if (mask != 0 && (mask and dayBit(date.dayOfWeek)) == 0) continue
            // A time inside a DST gap is moved forward by the gap (ZonedDateTime.of), so 02:30 on
            // the spring-forward night rings at 03:30 rather than not at all.
            val at = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (at > now) return at
        }
        error("no day in a week matches ${a.days}")
    }

    /** When [a] rings next — its snooze if that is sooner — or null when it is switched off. */
    fun alarmAt(a: Alarm, now: Long, zone: ZoneId): Long? {
        if (!a.enabled) return null
        val next = nextOccurrence(a, now, zone)
        return if (a.snoozedUntil > now) minOf(a.snoozedUntil, next) else next
    }

    /** The soonest ringing (not quiet) alarm: (alarm, at), or null. */
    fun nextAlarm(d: ClockData, now: Long, zone: ZoneId): Pair<Alarm, Long>? =
        d.alarms.filter { !it.quiet }.mapNotNull { a -> alarmAt(a, now, zone)?.let { a to it } }.minByOrNull { it.second }

    // ── timers ──────────────────────────────────────────────────────────────────────────────

    fun remaining(t: Timer, now: Long): Long = when (t.state) {
        TimerState.RUNNING -> (t.endsAt - now).coerceAtLeast(0)
        TimerState.RINGING -> 0
        else -> t.remainingMs
    }

    fun start(t: Timer, now: Long): Timer = when (t.state) {
        TimerState.IDLE, TimerState.PAUSED -> t.copy(state = TimerState.RUNNING, endsAt = now + t.remainingMs)
        else -> t
    }

    fun pause(t: Timer, now: Long): Timer =
        if (t.state == TimerState.RUNNING) t.copy(state = TimerState.PAUSED, remainingMs = remaining(t, now), endsAt = 0) else t

    /** +1 minute: a ringing timer starts again from one minute. */
    fun addMinute(t: Timer, now: Long): Timer = when (t.state) {
        TimerState.RUNNING -> t.copy(endsAt = t.endsAt + MINUTE_MS)
        TimerState.RINGING -> t.copy(state = TimerState.RUNNING, endsAt = now + MINUTE_MS)
        else -> t.copy(remainingMs = t.remainingMs + MINUTE_MS)
    }

    fun reset(t: Timer): Timer = t.copy(state = TimerState.IDLE, endsAt = 0, remainingMs = t.durationMs)

    // ── stopwatch ───────────────────────────────────────────────────────────────────────────

    fun elapsed(s: Stopwatch, now: Long): Long = s.accumulatedMs + if (s.running) now - s.startedAt else 0

    fun toggle(s: Stopwatch, now: Long): Stopwatch =
        if (s.running) s.copy(startedAt = 0, accumulatedMs = elapsed(s, now)) else s.copy(startedAt = now)

    fun lap(s: Stopwatch, now: Long): Stopwatch = if (s.running) s.copy(laps = s.laps + elapsed(s, now)) else s

    /** Each lap's own length, from the running totals. */
    fun lapTimes(s: Stopwatch): List<Long> = s.laps.mapIndexed { i, total -> total - (if (i == 0) 0 else s.laps[i - 1]) }

    // ── interval / Pomodoro ─────────────────────────────────────────────────────────────────

    enum class PhaseKind { WORK, REST, LONG_REST }
    data class Phase(val kind: PhaseKind, val round: Int, val startsAt: Long, val endsAt: Long)

    /** The session laid out: work, then a rest between rounds (a long one every longEvery), none after the last. */
    fun phases(i: Interval): List<Phase> {
        val out = mutableListOf<Phase>()
        var t = i.startedAt
        fun add(kind: PhaseKind, round: Int, ms: Long) {
            if (ms <= 0) return
            out += Phase(kind, round, t, t + ms)
            t += ms
        }
        for (r in 1..i.rounds) {
            add(PhaseKind.WORK, r, i.workMs)
            if (r == i.rounds) break
            val long = i.longEvery > 0 && r % i.longEvery == 0 && i.longRestMs > 0
            if (long) add(PhaseKind.LONG_REST, r, i.longRestMs) else add(PhaseKind.REST, r, i.restMs)
        }
        return out
    }

    /** The phase running at [now], or null once the session is over. */
    fun phaseAt(i: Interval, now: Long): Phase? = phases(i).firstOrNull { now < it.endsAt }

    // ── what is scheduled ───────────────────────────────────────────────────────────────────

    /** [clock]: an AlarmManager alarm clock (an alarm the user set); else an exact while-idle wakeup. */
    data class Wakeup(val key: String, val at: Long, val clock: Boolean)

    const val INTERVAL = "interval"
    const val SLEEP = "sleep"
    const val SILENCE = "silence"
    fun alarmKey(id: Long) = "alarm:$id"
    fun timerKey(id: Long) = "timer:$id"

    /**
     * Every wakeup [d] needs, from scratch. The scheduler sets exactly this list and cancels whatever
     * else it set before, so a deleted alarm or a finished timer can never ring from a stale entry.
     */
    fun plan(d: ClockData, now: Long, zone: ZoneId, ringMs: Long): List<Wakeup> = buildList {
        d.alarms.forEach { a -> alarmAt(a, now, zone)?.let { add(Wakeup(alarmKey(a.id), it, clock = !a.quiet)) } }
        d.timers.filter { it.state == TimerState.RUNNING }.forEach { add(Wakeup(timerKey(it.id), it.endsAt, clock = false)) }
        // A session already over is finished by an immediate wakeup.
        d.interval?.let { i -> add(Wakeup(INTERVAL, phaseAt(i, now)?.endsAt ?: now, clock = false)) }
        if (d.sleepUntil > 0) add(Wakeup(SLEEP, d.sleepUntil, clock = false))
        d.ringing.minOfOrNull { it.since }?.let { add(Wakeup(SILENCE, it + ringMs, clock = false)) }
    }

    // ── what a wakeup does ──────────────────────────────────────────────────────────────────

    enum class Effect {
        NONE,
        /** Start sounding: [Fired.data]'s ringing gained an entry. */
        RING,
        /** A quiet alarm (bedtime): post a reminder, no sound. */
        NOTIFY,
        /** An interval phase changed: a short tone. */
        BEEP,
        /** The sleep timer ran out: take audio focus so other players stop. */
        PAUSE_MEDIA,
        /** Rings that outlived ringMs were stopped. */
        SILENCE,
    }

    data class Fired(val data: ClockData, val effect: Effect, val label: String = "")

    /**
     * Wakeup [key] arrived at [now]. A wakeup that is no longer due — its item deleted, switched off,
     * paused, or moved later since it was set — changes nothing: AlarmManager may deliver one set
     * before the last re-plan, and acting on it would ring something the user already stopped.
     */
    fun fire(d: ClockData, key: String, now: Long, zone: ZoneId, ringMs: Long): Fired {
        val due = now + EARLY_MS
        when {
            key.startsWith("alarm:") -> {
                val a = d.alarms.firstOrNull { alarmKey(it.id) == key && it.enabled } ?: return Fired(d, Effect.NONE)
                // Due means: set for a moment no later than now. The occurrence after (now - ringMs)
                // is the one this wakeup was set for, so a delivery up to ringMs late still rings;
                // an occurrence later than now means the wakeup is early or stale.
                val at = alarmAt(a, now - ringMs, zone) ?: return Fired(d, Effect.NONE)
                if (at > due) return Fired(d, Effect.NONE)
                val after = a.copy(snoozedUntil = 0, enabled = a.days and ALL_DAYS != 0)
                val alarms = d.alarms.map { if (it.id == a.id) after else it }
                return if (a.quiet) Fired(d.copy(alarms = alarms), Effect.NOTIFY, a.label)
                else Fired(d.copy(alarms = alarms, ringing = d.ringing.filter { it.key != key } + Ring(key, a.label, now)), Effect.RING, a.label)
            }
            key.startsWith("timer:") -> {
                val t = d.timers.firstOrNull { timerKey(it.id) == key && it.state == TimerState.RUNNING } ?: return Fired(d, Effect.NONE)
                if (t.endsAt > due) return Fired(d, Effect.NONE)
                val timers = d.timers.map { if (it.id == t.id) it.copy(state = TimerState.RINGING, remainingMs = 0, endsAt = 0) else it }
                return Fired(d.copy(timers = timers, ringing = d.ringing.filter { it.key != key } + Ring(key, t.label, now)), Effect.RING, t.label)
            }
            key == INTERVAL -> {
                val i = d.interval ?: return Fired(d, Effect.NONE)
                if (phaseAt(i, now + EARLY_MS) != null) return Fired(d, Effect.BEEP, i.label)
                return Fired(d.copy(interval = null, ringing = d.ringing.filter { it.key != key } + Ring(key, i.label, now)), Effect.RING, i.label)
            }
            key == SLEEP -> {
                if (d.sleepUntil == 0L || d.sleepUntil > due) return Fired(d, Effect.NONE)
                return Fired(d.copy(sleepUntil = 0), Effect.PAUSE_MEDIA)
            }
            key == SILENCE -> {
                val (old, keep) = d.ringing.partition { it.since + ringMs <= due }
                if (old.isEmpty()) return Fired(d, Effect.NONE)
                return Fired(stopRinging(d.copy(ringing = keep), old.map { it.key }), Effect.SILENCE)
            }
        }
        return Fired(d, Effect.NONE)
    }

    /** Dismiss: the ring ends; a timer that rang goes back to its full duration. */
    fun dismiss(d: ClockData, key: String): ClockData = stopRinging(d.copy(ringing = d.ringing.filter { it.key != key }), listOf(key))

    /** Snooze an alarm's ring for its own snooze minutes; anything else that rings is dismissed. */
    fun snooze(d: ClockData, key: String, now: Long): ClockData {
        val a = d.alarms.firstOrNull { alarmKey(it.id) == key } ?: return dismiss(d, key)
        val alarms = d.alarms.map { if (it.id == a.id) it.copy(enabled = true, snoozedUntil = now + a.snoozeMinutes * MINUTE_MS) else it }
        return d.copy(alarms = alarms, ringing = d.ringing.filter { it.key != key })
    }

    private fun stopRinging(d: ClockData, keys: List<String>): ClockData =
        d.copy(timers = d.timers.map { if (timerKey(it.id) in keys && it.state == TimerState.RINGING) reset(it) else it })

    // ── what the screens print ──────────────────────────────────────────────────────────────

    /** A countdown: rounded UP to the second, so it never reads 0:00 while still running. h:mm:ss or m:ss. */
    fun countdown(ms: Long): String = clockText((ms.coerceAtLeast(0) + SECOND_MS - 1) / SECOND_MS)

    /** A stopwatch reading: m:ss.cc (h:mm:ss.cc past the hour). */
    fun stopwatchText(ms: Long): String {
        val v = ms.coerceAtLeast(0)
        return clockText(v / SECOND_MS) + "." + "%02d".format((v % SECOND_MS) / 10)
    }

    private fun clockText(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** "Once", "Every day", "Weekdays", "Weekends", else the days in week order from [first]. */
    fun daysText(days: Int, first: DayOfWeek, short: (DayOfWeek) -> String): String = when (days and ALL_DAYS) {
        0 -> "Once"
        ALL_DAYS -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKEND -> "Weekends"
        else -> week(first).filter { days and dayBit(it) != 0 }.joinToString(" ") { short(it) }
    }

    /** The seven days, starting at [first]. */
    fun week(first: DayOfWeek): List<DayOfWeek> = (0L..6L).map { first.plus(it) }

    /** "Europe/Madrid" → "Madrid", "America/Argentina/Buenos_Aires" → "Buenos Aires". */
    fun city(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

    /** How far [zone] is from [here] at [now]: "Same time", "+6 h", "−4:30 h". */
    fun offsetText(zone: ZoneId, here: ZoneId, now: Long): String {
        val i = Instant.ofEpochMilli(now)
        val diff = zone.rules.getOffset(i).totalSeconds - here.rules.getOffset(i).totalSeconds
        if (diff == 0) return "Same time"
        val sign = if (diff > 0) "+" else "−"
        val h = abs(diff) / 3600
        val m = (abs(diff) % 3600) / 60
        return sign + (if (m == 0) "$h" else "%d:%02d".format(h, m)) + " h"
    }

    /** #1377 [zone]'s offset from UTC at [now]: "UTC", "UTC+9", "UTC−3", "UTC+5:30". */
    fun utcText(zone: ZoneId, now: Long): String {
        val s = zone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds
        if (s == 0) return "UTC"
        val h = abs(s) / 3600
        val m = (abs(s) % 3600) / 60
        return "UTC" + (if (s > 0) "+" else "−") + (if (m == 0) "$h" else "%d:%02d".format(h, m))
    }

    /** #1377 day (06:00 to 17:59 local) or night in [zone] at [now]: a clock-face rule, the city's coordinates are not known. */
    fun isDay(zone: ZoneId, now: Long): Boolean = Instant.ofEpochMilli(now).atZone(zone).hour in 6..17

    /** Whether [zone]'s date at [now] is the day before (−1), the same (0) or the day after (+1) [here]'s. */
    fun dayShift(zone: ZoneId, here: ZoneId, now: Long): Int {
        val i = Instant.ofEpochMilli(now)
        return i.atZone(zone).toLocalDate().compareTo(i.atZone(here).toLocalDate()).coerceIn(-1, 1)
    }

    /** From the next bedtime reminder to the first ringing alarm after it: the sleep window, or null. */
    fun sleepWindow(d: ClockData, now: Long, zone: ZoneId): Pair<Long, Long>? {
        val bed = d.alarms.filter { it.quiet }.mapNotNull { alarmAt(it, now, zone) }.minOrNull() ?: return null
        val wake = d.alarms.filter { !it.quiet }.mapNotNull { alarmAt(it, bed, zone) }.minOrNull() ?: return null
        return bed to wake
    }

    /** "08:30" → 510, or null for anything that is not a 24-hour time. */
    fun parseTime(text: String): Int? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(text) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    fun timeText(minuteOfDay: Int): String = "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

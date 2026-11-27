package com.diegonmarcos.clocktools

import org.json.JSONObject

enum class PomodoroPhase { WORK, SHORT_BREAK, LONG_BREAK }

/**
 * #1970 the Pomodoro subpage: work, short-break and long-break phases (default 25 / 5 / 15 min, a long break after every
 * 4th work phase), whether a finished phase starts the next by itself, and the counts. Running while [endsAt] > 0 (a
 * wall-clock end, like a [Timer]); paused while [remainingMs] >= 0 and not running.
 */
data class Pomodoro(
    val workMin: Int = 25,
    val shortMin: Int = 5,
    val longMin: Int = 15,
    val longEvery: Int = 4,
    val autoAdvance: Boolean = true,
    val phase: PomodoroPhase = PomodoroPhase.WORK,
    /** Work phases done in the current set (a long break ends the set). */
    val cycle: Int = 0,
    /** Work phases done since the last reset. */
    val sessions: Int = 0,
    val endsAt: Long = 0,
    /** Paused: what is left; -1 is the whole phase. */
    val remainingMs: Long = -1,
    /** Work phases done on [todayDay] (an epoch day). */
    val today: Int = 0,
    val todayDay: Long = -1,
) {
    val running: Boolean get() = endsAt > 0
}

/** The Pomodoro cycle as a state machine: every transition is a pure function, the engine stores the result. */
object PomodoroLogic {
    fun durationMs(p: Pomodoro, phase: PomodoroPhase = p.phase): Long = when (phase) {
        PomodoroPhase.WORK -> p.workMin
        PomodoroPhase.SHORT_BREAK -> p.shortMin
        PomodoroPhase.LONG_BREAK -> p.longMin
    } * ClockLogic.MINUTE_MS

    fun remaining(p: Pomodoro, now: Long): Long = when {
        p.running -> (p.endsAt - now).coerceAtLeast(0)
        p.remainingMs >= 0 -> p.remainingMs
        else -> durationMs(p)
    }

    /** 0..1 of the phase gone, for the progress ring. */
    fun progress(p: Pomodoro, now: Long): Float {
        val total = durationMs(p)
        return if (total <= 0) 0f else (1f - remaining(p, now).toFloat() / total).coerceIn(0f, 1f)
    }

    fun start(p: Pomodoro, now: Long): Pomodoro = if (p.running) p else p.copy(endsAt = now + remaining(p, now), remainingMs = -1)

    fun pause(p: Pomodoro, now: Long): Pomodoro = if (!p.running) p else p.copy(endsAt = 0, remainingMs = remaining(p, now))

    /** Back to the first work phase of a new set; today's count stays. */
    fun reset(p: Pomodoro): Pomodoro = p.copy(phase = PomodoroPhase.WORK, cycle = 0, sessions = 0, endsAt = 0, remainingMs = -1)

    /** The phase after [p]'s and the set count it starts with. */
    fun next(p: Pomodoro): Pair<PomodoroPhase, Int> = when (p.phase) {
        PomodoroPhase.WORK -> {
            val done = p.cycle + 1
            if (p.longEvery > 0 && done >= p.longEvery) PomodoroPhase.LONG_BREAK to done else PomodoroPhase.SHORT_BREAK to done
        }
        PomodoroPhase.SHORT_BREAK -> PomodoroPhase.WORK to p.cycle
        PomodoroPhase.LONG_BREAK -> PomodoroPhase.WORK to 0
    }

    fun todayCount(p: Pomodoro, day: Long): Int = if (p.todayDay == day) p.today else 0

    /**
     * The phase ended at [now] ([counted]: it ran out, so a work phase counts; a skip does not). With auto-advance the next
     * phase runs at once, else it waits at its full length for Start.
     */
    fun advance(p: Pomodoro, now: Long, day: Long, counted: Boolean): Pomodoro {
        val (phase, cycle) = next(p)
        val work = counted && p.phase == PomodoroPhase.WORK
        val today = todayCount(p, day) + if (work) 1 else 0
        val moved = p.copy(
            phase = phase, cycle = cycle, sessions = p.sessions + if (work) 1 else 0,
            today = today, todayDay = day, endsAt = 0, remainingMs = -1,
        )
        return if (p.autoAdvance) moved.copy(endsAt = now + durationMs(moved)) else moved
    }

    /** The wakeup: a running phase that is due ends (counted); anything else is not this wakeup's, null. */
    fun fire(p: Pomodoro, now: Long, day: Long): Pomodoro? =
        if (p.running && p.endsAt <= now + ClockLogic.EARLY_MS) advance(p, now, day, counted = true) else null

    /** New durations (each at least a minute) and auto-advance; a phase not started yet takes its new length. */
    fun configure(p: Pomodoro, workMin: Int, shortMin: Int, longMin: Int, longEvery: Int, autoAdvance: Boolean): Pomodoro =
        p.copy(
            workMin = workMin.coerceIn(1, 180), shortMin = shortMin.coerceIn(1, 60), longMin = longMin.coerceIn(1, 120),
            longEvery = longEvery.coerceIn(1, 12), autoAdvance = autoAdvance,
        )

    /** "Cycle 2 of 4": the work phase of the set running now (or the one a break leads to). */
    fun cycleOf(p: Pomodoro): Int = if (p.phase == PomodoroPhase.WORK) p.cycle + 1 else p.cycle
}

internal object PomodoroCodec {
    fun encode(p: Pomodoro): JSONObject = JSONObject()
        .put("work", p.workMin).put("short", p.shortMin).put("long", p.longMin).put("long_every", p.longEvery).put("auto", p.autoAdvance)
        .put("phase", p.phase.name).put("cycle", p.cycle).put("sessions", p.sessions).put("ends_at", p.endsAt)
        .put("remaining", p.remainingMs).put("today", p.today).put("today_day", p.todayDay)

    fun decode(o: JSONObject?): Pomodoro {
        if (o == null) return Pomodoro()
        return Pomodoro(
            o.optInt("work", 25), o.optInt("short", 5), o.optInt("long", 15), o.optInt("long_every", 4), o.optBoolean("auto", true),
            runCatching { PomodoroPhase.valueOf(o.optString("phase")) }.getOrDefault(PomodoroPhase.WORK),
            o.optInt("cycle"), o.optInt("sessions"), o.optLong("ends_at"), o.optLong("remaining", -1), o.optInt("today"), o.optLong("today_day", -1),
        )
    }
}

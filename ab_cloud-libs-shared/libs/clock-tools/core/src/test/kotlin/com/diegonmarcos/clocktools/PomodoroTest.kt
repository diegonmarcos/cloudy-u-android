package com.diegonmarcos.clocktools

import com.diegonmarcos.clocktools.ClockLogic.MINUTE_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** #1970 the Pomodoro cycle state machine, and its wakeup on the timer engine. */
class PomodoroTest {
    private val t0 = 1_800_000_000_000L
    private val day = 20_000L
    private val manual = Pomodoro(autoAdvance = false)

    @Test fun `defaults are 25 5 15 with a long break every 4`() {
        val p = Pomodoro()
        assertEquals(listOf(25, 5, 15, 4), listOf(p.workMin, p.shortMin, p.longMin, p.longEvery))
        assertTrue(p.autoAdvance)
        assertEquals(PomodoroPhase.WORK, p.phase)
        assertEquals(25 * MINUTE_MS, PomodoroLogic.durationMs(p))
        assertEquals(5 * MINUTE_MS, PomodoroLogic.durationMs(p, PomodoroPhase.SHORT_BREAK))
        assertEquals(15 * MINUTE_MS, PomodoroLogic.durationMs(p, PomodoroPhase.LONG_BREAK))
        assertFalse(p.running)
    }

    @Test fun `start pause and resume keep what is left`() {
        val r = PomodoroLogic.start(manual, t0)
        assertTrue(r.running)
        assertEquals(t0 + 25 * MINUTE_MS, r.endsAt)
        assertEquals(r, PomodoroLogic.start(r, t0 + 5))
        assertEquals(20 * MINUTE_MS, PomodoroLogic.remaining(r, t0 + 5 * MINUTE_MS))
        assertEquals(0L, PomodoroLogic.remaining(r, t0 + 99 * MINUTE_MS))
        val p = PomodoroLogic.pause(r, t0 + 5 * MINUTE_MS)
        assertFalse(p.running)
        assertEquals(20 * MINUTE_MS, p.remainingMs)
        assertEquals(20 * MINUTE_MS, PomodoroLogic.remaining(p, t0 + 50 * MINUTE_MS))
        assertEquals(p, PomodoroLogic.pause(p, t0 + 6 * MINUTE_MS))
        val again = PomodoroLogic.start(p, t0 + 10 * MINUTE_MS)
        assertEquals(t0 + 30 * MINUTE_MS, again.endsAt)
        assertEquals(-1L, again.remainingMs)
        assertEquals(0.2f, PomodoroLogic.progress(r, t0 + 5 * MINUTE_MS), 0.0001f)
        assertEquals(0f, PomodoroLogic.progress(manual, t0), 0f)
        assertEquals(1f, PomodoroLogic.progress(r, t0 + 99 * MINUTE_MS), 0f)
    }

    @Test fun `work then short breaks then a long break after the 4th, and the counts`() {
        var p = manual
        val seen = mutableListOf<String>()
        repeat(9) {
            seen += p.phase.name + ":" + PomodoroLogic.cycleOf(p)
            p = PomodoroLogic.advance(p, t0, day, counted = true)
        }
        assertEquals(listOf("WORK:1", "SHORT_BREAK:1", "WORK:2", "SHORT_BREAK:2", "WORK:3", "SHORT_BREAK:3", "WORK:4", "LONG_BREAK:4", "WORK:1"), seen)
        assertEquals(5, p.sessions)
        assertEquals(5, PomodoroLogic.todayCount(p, day))
        assertEquals(0, PomodoroLogic.todayCount(p, day + 1))
        assertEquals(PomodoroPhase.SHORT_BREAK, p.phase)
        assertFalse("without auto-advance the next phase waits", p.running)
    }

    @Test fun `a long break every 2 and every 1`() {
        val two = PomodoroLogic.advance(PomodoroLogic.advance(PomodoroLogic.advance(manual.copy(longEvery = 2), t0, day, true), t0, day, true), t0, day, true)
        assertEquals(PomodoroPhase.LONG_BREAK, two.phase)
        assertEquals(PomodoroPhase.LONG_BREAK, PomodoroLogic.advance(manual.copy(longEvery = 1), t0, day, true).phase)
        assertEquals(PomodoroPhase.SHORT_BREAK to 1, PomodoroLogic.next(manual.copy(longEvery = 0)))
    }

    @Test fun `auto-advance runs the next phase at once`() {
        val p = PomodoroLogic.advance(Pomodoro(), t0, day, counted = true)
        assertTrue(p.running)
        assertEquals(t0 + 5 * MINUTE_MS, p.endsAt)
        assertEquals(PomodoroPhase.SHORT_BREAK, p.phase)
    }

    @Test fun `a skip moves on without counting the work`() {
        val p = PomodoroLogic.advance(manual, t0, day, counted = false)
        assertEquals(PomodoroPhase.SHORT_BREAK, p.phase)
        assertEquals(0, p.sessions)
        assertEquals(0, PomodoroLogic.todayCount(p, day))
        assertEquals(1, p.cycle)
        val brk = PomodoroLogic.advance(p, t0, day, counted = true)
        assertEquals("a finished break counts no work", 0, brk.sessions)
    }

    @Test fun `today's count starts again on a new day`() {
        val p = PomodoroLogic.advance(manual.copy(today = 7, todayDay = day - 1), t0, day, counted = true)
        assertEquals(1, p.today)
        assertEquals(day, p.todayDay)
        val q = PomodoroLogic.advance(manual.copy(today = 7, todayDay = day), t0, day, counted = true)
        assertEquals(8, q.today)
    }

    @Test fun `reset goes back to the first work phase and keeps today's count`() {
        val p = PomodoroLogic.reset(manual.copy(phase = PomodoroPhase.LONG_BREAK, cycle = 4, sessions = 9, endsAt = t0, remainingMs = 5, today = 3, todayDay = day))
        assertEquals(manual.copy(today = 3, todayDay = day), p)
    }

    @Test fun `the wakeup ends a due phase and nothing else`() {
        val r = PomodoroLogic.start(manual, t0)
        assertNull(PomodoroLogic.fire(r, t0 + 10 * MINUTE_MS, day))
        assertNull(PomodoroLogic.fire(manual, t0 + 99 * MINUTE_MS, day))
        val f = PomodoroLogic.fire(r, r.endsAt - 500, day)!!
        assertEquals(PomodoroPhase.SHORT_BREAK, f.phase)
        assertEquals(1, f.today)
        assertNull(PomodoroLogic.fire(r, r.endsAt - 2000, day))
    }

    @Test fun `configure clamps every length and keeps auto-advance`() {
        val p = PomodoroLogic.configure(Pomodoro(), 0, 0, 0, 0, false)
        assertEquals(listOf(1, 1, 1, 1), listOf(p.workMin, p.shortMin, p.longMin, p.longEvery))
        assertFalse(p.autoAdvance)
        val q = PomodoroLogic.configure(Pomodoro(), 500, 500, 500, 500, true)
        assertEquals(listOf(180, 60, 120, 12), listOf(q.workMin, q.shortMin, q.longMin, q.longEvery))
        assertEquals(listOf(50, 10, 20, 3), PomodoroLogic.configure(Pomodoro(), 50, 10, 20, 3, true).let { listOf(it.workMin, it.shortMin, it.longMin, it.longEvery) })
    }

    @Test fun `the engine plans and fires the Pomodoro wakeup`() {
        val zone = ZoneId.of("UTC")
        val running = ClockData(pomodoro = PomodoroLogic.start(manual, t0))
        assertTrue(ClockLogic.plan(running, t0, zone, MINUTE_MS).contains(ClockLogic.Wakeup(ClockLogic.POMODORO, t0 + 25 * MINUTE_MS, false)))
        assertTrue(ClockLogic.plan(ClockData(), t0, zone, MINUTE_MS).none { it.key == ClockLogic.POMODORO })
        assertEquals(ClockLogic.Effect.NONE, ClockLogic.fire(running, ClockLogic.POMODORO, t0, zone, MINUTE_MS).effect)
        val rang = ClockLogic.fire(running, ClockLogic.POMODORO, t0 + 25 * MINUTE_MS, zone, MINUTE_MS)
        assertEquals(ClockLogic.Effect.RING, rang.effect)
        assertEquals(listOf(ClockLogic.POMODORO), rang.data.ringing.map { it.key })
        assertEquals(1, rang.data.pomodoro.sessions)
        assertEquals("Pomodoro: work", rang.label)
        val auto = ClockData(pomodoro = PomodoroLogic.start(Pomodoro(), t0))
        val beep = ClockLogic.fire(auto, ClockLogic.POMODORO, t0 + 25 * MINUTE_MS, zone, MINUTE_MS)
        assertEquals(ClockLogic.Effect.BEEP, beep.effect)
        assertTrue(beep.data.ringing.isEmpty())
        assertTrue(beep.data.pomodoro.running)
        assertEquals(java.time.Instant.ofEpochMilli(t0 + 25 * MINUTE_MS).atZone(zone).toLocalDate().toEpochDay(), beep.data.pomodoro.todayDay)
    }

    @Test fun `the Pomodoro survives the store`() {
        val p = Pomodoro(50, 10, 20, 3, false, PomodoroPhase.LONG_BREAK, 3, 7, t0, 1234, 5, day)
        assertEquals(p, ClockCodec.decode(ClockCodec.encode(ClockData(pomodoro = p))).pomodoro)
        assertEquals(Pomodoro(), ClockCodec.decode(ClockCodec.encode(ClockData())).pomodoro)
        assertEquals(Pomodoro(), ClockCodec.decode("""{"alarms":[]}""").pomodoro)
        assertEquals(PomodoroPhase.WORK, ClockCodec.decode("""{"pomodoro":{"phase":"NOPE"}}""").pomodoro.phase)
        assertEquals(-1L, ClockCodec.decode("""{"pomodoro":{}}""").pomodoro.remainingMs)
    }
}

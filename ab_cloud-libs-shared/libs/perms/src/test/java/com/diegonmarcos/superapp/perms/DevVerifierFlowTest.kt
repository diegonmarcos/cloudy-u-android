package com.diegonmarcos.superapp.perms

import com.diegonmarcos.superapp.perms.DevVerifier.Reading
import com.diegonmarcos.superapp.perms.DevVerifier.State
import com.diegonmarcos.superapp.perms.DevVerifierFlow.Facts
import com.diegonmarcos.superapp.perms.DevVerifierFlow.Kind
import com.diegonmarcos.superapp.perms.DevVerifierFlow.Saved
import com.diegonmarcos.superapp.perms.DevVerifierFlow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1822 the Advanced Flow: transitions from measurements only, resume across restart and reboot, window end, reset detection, row text and alarms. */
class DevVerifierFlowTest {
    private val H = 3_600_000L
    private val T0 = 1_800_000_000_000L
    private fun facts(s: State, until: Long = 0, dev: Boolean? = true, boot: String? = "boot-A", up: Long? = 0L) =
        Facts(Reading(s, until, "d"), dev, boot, up)

    @Test fun `request then restart then waiting then finish then done`() {
        var r = DevVerifierFlow.advance(facts(State.NOT_ALLOWED), Saved(), T0)
        assertEquals(Step.REQUEST, r.step)
        r = DevVerifierFlow.advance(facts(State.PENDING_24H), r.saved, T0 + H)           // request registered, same boot
        assertEquals(Step.RESTART, r.step); assertEquals("boot-A", r.saved.bootAtRequest)
        assertTrue(DevVerifierFlow.shouldPromptRestart(r))
        r = DevVerifierFlow.advance(facts(State.PENDING_24H, boot = "boot-A"), r.saved, T0 + 2 * H)
        assertEquals(Step.RESTART, r.step)                                                  // still not rebooted
        val bootedAt = T0 + 3 * H
        r = DevVerifierFlow.advance(facts(State.PENDING_24H, boot = "boot-B", up = 60_000), r.saved, bootedAt + 60_000)
        assertEquals(Step.WAITING, r.step)
        assertEquals(bootedAt, r.saved.windowStart)                                         // boot time = now - uptime
        assertEquals(bootedAt + 24 * H, r.end)
        r = DevVerifierFlow.advance(facts(State.NOT_ALLOWED, boot = "boot-B"), r.saved, bootedAt + 25 * H)
        assertEquals(Step.FINISH, r.step)                                                   // window over, nothing chosen yet
        r = DevVerifierFlow.advance(facts(State.ALLOWED_INDEFINITELY, boot = "boot-B"), r.saved, bootedAt + 26 * H)
        assertEquals(Step.DONE, r.step); assertTrue(r.saved.wasAllowed)
    }

    @Test fun `developer options off sends to About phone first`() {
        val r = DevVerifierFlow.advance(facts(State.UNKNOWN, dev = false), Saved(), T0)
        assertEquals(Step.DEV_OPTIONS, r.step)
        assertTrue(DevVerifierFlow.openCmd(Step.DEV_OPTIONS).contains("DEVICE_INFO_SETTINGS"))
        assertEquals(DevVerifier.OPEN_CMD, DevVerifierFlow.openCmd(Step.REQUEST))
        assertEquals(Step.REQUEST, DevVerifierFlow.advance(facts(State.NOT_ALLOWED, dev = true), Saved(), T0).step)
    }

    @Test fun `the window survives an app restart and a reboot, and an unreadable checker never resets it`() {
        val saved = Saved(bootAtRequest = "boot-A", windowStart = T0)                        // what the prefs hold
        assertEquals(Step.WAITING, DevVerifierFlow.advance(facts(State.PENDING_24H, boot = "boot-A"), saved, T0 + 5 * H).step)  // pending seen again in the same boot id, window already started
        assertEquals(Step.WAITING, DevVerifierFlow.advance(facts(State.PENDING_24H, boot = "boot-C"), saved, T0 + 5 * H).step)  // another reboot
        val silent = DevVerifierFlow.advance(facts(State.UNKNOWN, boot = null), saved, T0 + 5 * H)
        assertEquals(Step.WAITING, silent.step); assertEquals(T0 + 24 * H, silent.end)
        assertEquals(Step.FINISH, DevVerifierFlow.advance(facts(State.UNKNOWN, boot = null), saved, T0 + 24 * H).step)
    }

    @Test fun `a request that vanishes before the window ends goes back to Request`() {
        val saved = Saved(bootAtRequest = "boot-A", windowStart = T0)
        val r = DevVerifierFlow.advance(facts(State.NOT_ALLOWED), saved, T0 + 5 * H)
        assertEquals(Step.REQUEST, r.step); assertEquals(0L, r.saved.windowStart); assertTrue(r.note.contains("starting again"))
    }

    @Test fun `green only for allowed indefinitely, a 7 day allowance is not done and shows its expiry`() {
        val exp = T0 + 7 * 24 * H
        val week = DevVerifierFlow.advance(facts(State.ALLOWED_UNTIL, until = exp), Saved(), T0)
        assertEquals(Step.TEMP, week.step); assertEquals(exp, week.expiry); assertFalse(week.saved.wasAllowed)
        val st = DevVerifierFlow.status(week, T0)
        assertEquals(PermState.DENIED, st.state); assertTrue(st.reason.contains("Indefinitely")); assertFalse(st.reason.contains("7 days is"))
        assertTrue(st.reason.contains("until 20"))
        for (s in State.values()) {
            val r = DevVerifierFlow.advance(facts(s, until = if (s == State.PENDING_24H || s == State.ALLOWED_UNTIL) exp else 0), Saved(), T0)
            val granted = DevVerifierFlow.status(r, T0).state == PermState.GRANTED
            assertEquals("$s", s == State.ALLOWED_INDEFINITELY || s == State.DISABLED, granted)   // DISABLED = verifier switched off, measured
        }
        assertEquals(PermState.GRANTED, DevVerifier.status(Reading(State.ALLOWED_INDEFINITELY)).state)
        assertEquals(PermState.DENIED, DevVerifier.status(Reading(State.ALLOWED_UNTIL, exp)).state)
        assertEquals(PermState.UNKNOWN, DevVerifier.status(Reading(State.UNKNOWN)).state)
    }

    @Test fun `a reset after done raises the notification and restarts the flow at the right step`() {
        val done = Saved(wasAllowed = true)
        val r = DevVerifierFlow.advance(facts(State.NOT_ALLOWED), done, T0)
        assertTrue(r.reset); assertEquals(Step.REQUEST, r.step); assertFalse(r.saved.wasAllowed)
        assertEquals(listOf(Kind.RESET), DevVerifierFlow.immediate(r))
        assertEquals(Step.DEV_OPTIONS, DevVerifierFlow.advance(facts(State.NOT_ALLOWED, dev = false), done, T0).step)
        // after the restart the next check raises nothing more
        assertTrue(DevVerifierFlow.immediate(DevVerifierFlow.advance(facts(State.NOT_ALLOWED), r.saved, T0 + H)).isEmpty())
        // an unreadable check is not a reset
        assertFalse(DevVerifierFlow.advance(facts(State.UNKNOWN), done, T0).reset)
    }

    @Test fun `notifications and alarms follow the step`() {
        val pending = DevVerifierFlow.advance(facts(State.PENDING_24H), Saved(), T0)
        assertEquals(listOf(Kind.RESTART_PROMPT), DevVerifierFlow.immediate(pending))
        assertTrue(DevVerifierFlow.immediate(pending.copy(saved = pending.saved.copy(restartPrompted = true))).isEmpty())
        val waiting = DevVerifierFlow.advance(facts(State.PENDING_24H, boot = "boot-B"), Saved(bootAtRequest = "boot-A"), T0)
        assertEquals(Step.WAITING, waiting.step)
        assertEquals(listOf(DevVerifierFlow.Alarm(Kind.WINDOW_END, T0 + 24 * H)), DevVerifierFlow.alarms(waiting, T0))
        for (s in listOf(State.NOT_ALLOWED, State.ALLOWED_INDEFINITELY, State.ALLOWED_UNTIL, State.NOT_PRESENT))
            assertTrue("$s", DevVerifierFlow.alarms(DevVerifierFlow.advance(facts(s, until = T0 + H), Saved(), T0), T0).isEmpty())
    }

    @Test fun `row text per state names the step and the adb fallback during the wait`() {
        fun t(s: State, saved: Saved = Saved(), f: Facts = facts(s), now: Long = T0) = DevVerifierFlow.text(DevVerifierFlow.advance(f, saved, now), now)
        assertTrue(t(State.NOT_ALLOWED).startsWith("Step 1 of 4, Request"))
        assertTrue(t(State.UNKNOWN, f = dev(false)).contains("Build number"))
        assertTrue(t(State.PENDING_24H).startsWith("Step 2 of 4, Restart"))
        val w = t(State.PENDING_24H, Saved(bootAtRequest = "boot-A", windowStart = T0), facts(State.PENDING_24H, boot = "boot-B"), T0 + 10 * H + 30 * 60_000)
        assertTrue(w, w.startsWith("Step 3 of 4, Waiting: 13 h 30 min left, ends "))
        assertTrue(w.contains("adb installs of fleet APKs keep working"))
        assertTrue(t(State.NOT_ALLOWED, Saved(windowStart = T0), now = T0 + 25 * H).startsWith("Step 4 of 4, Finish"))
        assertTrue(t(State.NOT_ALLOWED, Saved(windowStart = T0), now = T0 + 25 * H).contains("Indefinitely"))
        assertEquals("Step 4 of 4, done: allowed indefinitely.", t(State.ALLOWED_INDEFINITELY))
        assertEquals("less than a minute", DevVerifierFlow.remaining(0)); assertEquals("0 h 01 min", DevVerifierFlow.remaining(10_000))
    }
    private fun dev(on: Boolean) = facts(State.UNKNOWN, dev = on)

}

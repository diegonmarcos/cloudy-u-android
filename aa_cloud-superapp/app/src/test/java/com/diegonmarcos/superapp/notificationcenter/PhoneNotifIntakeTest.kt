package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.notificationcenter.PhoneNotifIntake.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1629 The notification listener's intake rule. Every post of every app used to be recorded in full (avatar PNG, label
 * lookup, the whole history parsed and written); an app that re-posts every second cost that every second.
 */
class PhoneNotifIntakeTest {

    @Before fun setUp() { PhoneNotifIntake.reset() }

    private val gap = PhoneNotifIntake.MIN_GAP_MS

    @Test fun `ten minutes of a player re-posting the same title and text is one full record`() {
        val sig = PhoneNotifIntake.signature("Song", "Artist")
        val verdicts = (0 until 600).map { PhoneNotifIntake.decide("k", sig, 1_000L + it * 1_000L) }
        assertEquals(Verdict.RECORD, verdicts.first())
        assertEquals("every re-post was a full record (was 600)", 1, verdicts.count { it == Verdict.RECORD })
        assertEquals(599, verdicts.count { it == Verdict.TOUCH })
    }

    @Test fun `a progress notification changing ten times a second is recorded once per gap and its last state is kept`() {
        // The service's loop: RECORD = full record now (anything held is older and dropped); HOLD = keep the newest and,
        // when the wait ends, put it through decide() again.
        var full = 0
        var held: String? = null
        var waitEnds = Long.MAX_VALUE
        var lastRecorded = ""
        fun offer(sig: String, now: Long) {
            when (PhoneNotifIntake.decide("dl", sig, now)) {
                Verdict.RECORD -> { held = null; full++; lastRecorded = sig }
                Verdict.HOLD -> { held = sig; if (waitEnds == Long.MAX_VALUE) waitEnds = now + gap }
                Verdict.TOUCH -> held = null
            }
        }
        var now = 1_000L
        var pct = 0
        while (now < 11_000L) {
            if (now >= waitEnds) { waitEnds = Long.MAX_VALUE; val h = held; held = null; if (h != null) offer(h, now) }
            offer(PhoneNotifIntake.signature("Downloading", "${pct++}%"), now)
            now += 100
        }
        // The app stops posting: the wait that is still queued records what is held.
        while (held != null) { now += 100; if (now >= waitEnds) { waitEnds = Long.MAX_VALUE; val h = held; held = null; if (h != null) offer(h, now) } }
        assertTrue("100 posts in 10 s were $full full records", full <= 10_000L / gap + 2)
        assertEquals("the last state was lost", PhoneNotifIntake.signature("Downloading", "${pct - 1}%"), lastRecorded)
    }

    @Test fun `a change after the gap is recorded at once, and a change inside it is held`() {
        assertEquals(Verdict.RECORD, PhoneNotifIntake.decide("k", "a", 10_000L))
        assertEquals(Verdict.HOLD, PhoneNotifIntake.decide("k", "b", 10_000L + gap - 1))
        assertEquals(Verdict.RECORD, PhoneNotifIntake.decide("k", "b", 10_000L + gap))
        assertEquals(Verdict.TOUCH, PhoneNotifIntake.decide("k", "b", 10_000L + gap + 1))
    }

    @Test fun `keys do not share a gap`() {
        assertEquals(Verdict.RECORD, PhoneNotifIntake.decide("chat:1", "hello", 5_000L))
        assertEquals("another conversation is never held back by this one", Verdict.RECORD, PhoneNotifIntake.decide("chat:2", "hello", 5_001L))
    }

    @Test fun `a forgotten key is recorded in full again and the table stays bounded`() {
        assertEquals(Verdict.RECORD, PhoneNotifIntake.decide("first", "x", 1L))
        for (i in 0 until 2_000) PhoneNotifIntake.decide("k$i", "x", 2L + i)
        assertEquals(Verdict.RECORD, PhoneNotifIntake.decide("first", "x", 5_000L))
    }

    @Test fun `title and text are both in the signature`() {
        assertTrue(PhoneNotifIntake.signature("a", "bc") != PhoneNotifIntake.signature("ab", "c"))
    }
}

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
        // The service's loop: RECORD = full record now; HOLD = keep the newest, record it (commit) when the gap ends.
        var full = 0
        var held: String? = null
        var flushAt = Long.MAX_VALUE
        var lastRecorded = ""
        val end = 1_000L + 10_000L
        var now = 1_000L
        var pct = 0
        while (now < end) {
            if (now >= flushAt) { held?.let { PhoneNotifIntake.commit("dl", it, now); full++; lastRecorded = it }; held = null; flushAt = Long.MAX_VALUE }
            val sig = PhoneNotifIntake.signature("Downloading", "${pct++}%")
            when (PhoneNotifIntake.decide("dl", sig, now)) {
                Verdict.RECORD -> { full++; lastRecorded = sig }
                Verdict.HOLD -> { if (held == null) flushAt = now + gap; held = sig }
                Verdict.TOUCH -> Unit
            }
            now += 100
        }
        held?.let { PhoneNotifIntake.commit("dl", it, now); full++; lastRecorded = it }
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

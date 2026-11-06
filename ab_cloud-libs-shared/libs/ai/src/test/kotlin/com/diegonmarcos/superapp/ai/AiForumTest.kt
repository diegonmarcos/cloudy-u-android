package com.diegonmarcos.superapp.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The forum's pure model (date-separator grouping, consecutive-sender grouping, receipts) proven on the JVM. */
class AiForumTest {
    private val utc = ZoneId.of("UTC")
    private fun at(date: String, time: String = "12:00"): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun user(c: String, day: String, t: String = "12:00") = AiMsg("user", c, at = at(day, t))
    private fun bot(c: String, day: String, t: String = "12:00") = AiMsg("assistant", c, at = at(day, t))

    @Test fun oneSeparatorPerDay() {
        val msgs = listOf(
            user("a", "2026-09-19", "09:00"), bot("b", "2026-09-19", "09:01"),
            user("c", "2026-09-20", "10:00"),
        )
        val rows = AiForum.rows(msgs, utc, at("2026-09-21"), at("2026-09-20"))
        val seps = rows.filterIsInstance<AiForum.Row.Separator>()
        assertEquals(2, seps.size)
        // Neither day is "today" (now is the 21st) so both show a month-day label, newest-day label distinct.
        assertEquals("September 19", seps[0].label)
        assertEquals("September 20", seps[1].label)
    }

    @Test fun todayAndYesterdayLabels() {
        val now = at("2026-11-06")
        val rows = AiForum.rows(
            listOf(user("x", "2026-11-05"), bot("y", "2026-11-06")),
            utc, now, now, today = "Today", yesterday = "Yesterday",
        )
        val seps = rows.filterIsInstance<AiForum.Row.Separator>().map { it.label }
        assertEquals(listOf("Yesterday", "Today"), seps)
    }

    @Test fun consecutiveSameSenderGroups() {
        val msgs = listOf(
            user("a", "2026-09-19", "09:00"), user("b", "2026-09-19", "09:01"),
            bot("c", "2026-09-19", "09:02"),
        )
        val m = AiForum.rows(msgs, utc, at("2026-09-19"), at("2026-09-19")).filterIsInstance<AiForum.Row.Message>()
        assertEquals(3, m.size)
        // a: first of the user run, not last; b: last of the user run, not first; c: its own run.
        assertTrue(m[0].firstInGroup && !m[0].lastInGroup)
        assertTrue(!m[1].firstInGroup && m[1].lastInGroup)
        assertTrue(m[2].firstInGroup && m[2].lastInGroup)
    }

    @Test fun groupBreaksAcrossDays() {
        val msgs = listOf(user("a", "2026-09-19"), user("b", "2026-09-20"))
        val m = AiForum.rows(msgs, utc, at("2026-09-21"), at("2026-09-21")).filterIsInstance<AiForum.Row.Message>()
        // Same sender but a day apart: each opens and closes its own run.
        assertTrue(m[0].firstInGroup && m[0].lastInGroup)
        assertTrue(m[1].firstInGroup && m[1].lastInGroup)
    }

    @Test fun receiptReadWhenAnswered() {
        val msgs = listOf(user("hi", "2026-09-19"), bot("hello", "2026-09-19"))
        assertEquals(AiForum.Receipt.READ, AiForum.receipt(msgs, 0, sending = false))
        assertNull("an assistant message has no receipt", AiForum.receipt(msgs, 1, sending = false))
    }

    @Test fun receiptSentWhileSending() {
        val msgs = listOf(user("hi", "2026-09-19"))
        assertEquals(AiForum.Receipt.SENT, AiForum.receipt(msgs, 0, sending = true))
    }

    @Test fun receiptDeliveredWhenLastAndIdle() {
        val msgs = listOf(user("hi", "2026-09-19"))
        assertEquals(AiForum.Receipt.DELIVERED, AiForum.receipt(msgs, 0, sending = false))
    }

    @Test fun instantFallsBackToSession() {
        assertEquals(500L, AiForum.instant(AiMsg("user", "x", at = 0), 500L))
        assertEquals(700L, AiForum.instant(AiMsg("user", "x", at = 700L), 500L))
    }

    @Test fun emptyMessagesNoRows() {
        assertTrue(AiForum.rows(emptyList(), utc, 0, 0).isEmpty())
    }
}

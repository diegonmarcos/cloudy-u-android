package com.diegonmarcos.superapp.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Store keep-alive badge always says where the updates stand: what runs, how many wait, when the last pass ended. */
class StoreKeepAliveTextTest {
    private val now = 10_000_000L

    @Test fun `a running pass shows its line and what still waits`() {
        val l = StoreKeepAliveText.lines("Auto > 2/4 download all  ·  Maps 1.2  ·  downloading  ·  40%", 3, now - 5_000, now)
        assertEquals(listOf("Auto > 2/4 download all  ·  Maps 1.2  ·  downloading  ·  40%", "3 updates pending"), l)
    }

    @Test fun `a pending count of one is singular`() {
        assertEquals("1 update pending · last check 2m ago", StoreKeepAliveText.lines(null, 1, now - 120_000, now).single())
    }

    @Test fun `idle with nothing pending says all up to date and when it last looked`() {
        assertEquals("All up to date · last check 2m ago", StoreKeepAliveText.lines(null, 0, now - 125_000, now).single())
    }

    @Test fun `no ledger yet says checking and is never empty`() {
        assertEquals(listOf("Checking…"), StoreKeepAliveText.lines(null, null, 0L, now))
        assertEquals(listOf("Checking…"), StoreKeepAliveText.lines("  ", null, 0L, now))
    }

    @Test fun `a running pass with nothing pending adds no pending line`() {
        assertEquals(listOf("Auto > 1/4 checking 3 of 9"), StoreKeepAliveText.lines("Auto > 1/4 checking 3 of 9", 0, 0L, now))
    }

    @Test fun `ages read as minutes hours and days`() {
        assertEquals("just now", StoreKeepAliveText.ago(10_000))
        assertEquals("5m ago", StoreKeepAliveText.ago(5 * 60_000L))
        assertEquals("3h ago", StoreKeepAliveText.ago(3 * 3_600_000L))
        assertEquals("2d ago", StoreKeepAliveText.ago(2 * 86_400_000L))
        assertTrue(StoreKeepAliveText.ago(-5) == "just now")
    }
}

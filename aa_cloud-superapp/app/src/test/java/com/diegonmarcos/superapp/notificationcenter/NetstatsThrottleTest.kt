package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.datamanager.DataUsageHistoryStore as H
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard for the netstats polling class (2774 openSession calls from one uid): the Data badge read is gated to
 * screen-on and floored, and the history fold queries 6 months at most daily, the current month at most per minute.
 */
class NetstatsThrottleTest {
    @Test fun screenOffNeverReads() {
        assertFalse(DataBadgeService.mayRead(force = true, interactive = false, nowMs = 1_000_000, lastReadMs = 0))
        assertFalse(DataBadgeService.mayRead(force = false, interactive = false, nowMs = 1_000_000, lastReadMs = 0))
    }

    @Test fun burstOfForcedReadsIsOneRead() {
        var last = 0L; var reads = 0
        for (t in 100_000L until 100_000L + 9_000L step 16L) { // 9 s at frame rate
            if (DataBadgeService.mayRead(true, true, t, last)) { reads++; last = t }
        }
        assertEquals(1, reads)
        assertTrue(DataBadgeService.mayRead(true, true, last + DataBadgeService.FORCE_FLOOR_MS, last))
    }

    @Test fun unforcedUsesThirtySecondGap() {
        assertFalse(DataBadgeService.mayRead(false, true, 129_000, 100_000))
        assertTrue(DataBadgeService.mayRead(false, true, 130_000, 100_000))
    }

    @Test fun historyFoldIsThrottled() {
        val now = 10L * 86_400_000
        assertEquals(H.BACKFILL_MONTHS, H.monthsToQuery(now, 0, 0))
        assertEquals(0, H.monthsToQuery(now, now - 1_000, now - 1_000))
        assertEquals(1, H.monthsToQuery(now, now - H.MIN_GAP_MS, now - 3_600_000))
        assertEquals(H.BACKFILL_MONTHS, H.monthsToQuery(now, now - H.MIN_GAP_MS, now - H.FULL_GAP_MS))
    }
}

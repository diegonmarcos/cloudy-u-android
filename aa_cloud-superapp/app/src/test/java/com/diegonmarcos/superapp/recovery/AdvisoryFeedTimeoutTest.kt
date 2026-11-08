package com.diegonmarcos.superapp.recovery

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the fix for the ~10s advisory-poll hang: [AdvisoryFeed] runs its two
 * topic fetches in turn on the one `advisory-feed` thread that also drives the
 * recovery notification, so a long connect timeout holds that thread (and the
 * banner it feeds) for up to two full timeouts on a bad network. The timeouts
 * must stay SHORT; this fails if either creeps back toward the old 10s/15s.
 */
class AdvisoryFeedTimeoutTest {

    /** The value that caused the hang — a connect timeout at or above it is the
     *  regression this test exists to stop. */
    private val OLD_HANG_MS = 10_000

    @Test fun `the connect timeout is short enough not to stall the poll thread`() {
        assertTrue(
            "connect timeout ${AdvisoryFeed.CONNECT_TIMEOUT_MS}ms is not clearly under the old ${OLD_HANG_MS}ms hang",
            AdvisoryFeed.CONNECT_TIMEOUT_MS <= 3_000,
        )
    }

    @Test fun `the read timeout is short and never shorter than the connect timeout`() {
        assertTrue(AdvisoryFeed.READ_TIMEOUT_MS in AdvisoryFeed.CONNECT_TIMEOUT_MS..6_000)
    }

    @Test fun `both timeouts are well under the old blocking values`() {
        assertTrue(AdvisoryFeed.CONNECT_TIMEOUT_MS < OLD_HANG_MS)
        assertTrue(AdvisoryFeed.READ_TIMEOUT_MS < 15_000)
    }
}

package app.sterna.push

import org.junit.Assert.assertEquals
import org.junit.Test

/** The push reconnect wait: doubles per failure, capped, reset only by a connection that held. */
class ReconnectBackoffTest {

    @Test fun `the wait doubles per consecutive failure from 5 s`() {
        assertEquals(listOf(5_000L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L),
            (-1..5).map { ReconnectBackoff.delayMs(it) })
    }

    @Test fun `the wait is capped at 5 minutes however long it fails`() {
        assertEquals(300_000L, ReconnectBackoff.delayMs(6))
        assertEquals(300_000L, ReconnectBackoff.delayMs(7))
        assertEquals(300_000L, ReconnectBackoff.delayMs(1_000))
    }

    @Test fun `a connection that never opened or dropped quickly is one more failure`() {
        assertEquals(1, ReconnectBackoff.next(0, null))
        assertEquals(4, ReconnectBackoff.next(3, null))
        assertEquals(3, ReconnectBackoff.next(2, 59_999L))
    }

    @Test fun `a connection that held a minute starts over`() {
        assertEquals(0, ReconnectBackoff.next(5, 60_000L))
        assertEquals(0, ReconnectBackoff.next(5, 3_600_000L))
    }
}

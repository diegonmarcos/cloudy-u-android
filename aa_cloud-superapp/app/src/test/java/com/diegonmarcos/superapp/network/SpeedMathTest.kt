package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1446 The speedmeter's unit conversion and rolling 24 h average. */
class SpeedMathTest {
    private val h = 3_600_000L
    private fun s(at: Long, bytes: Long, ms: Long) = SpeedSample(at, bytes, ms, active = false)

    @Test fun bytesOverTimeIsMbps() {
        assertEquals(8.0, SpeedMath.mbps(1_000_000, 1000), 1e-9)      // 1 MB in 1 s = 8 Mbps
        assertEquals(80.0, SpeedMath.mbps(10_000_000, 1000), 1e-9)
        assertEquals(4.0, SpeedMath.mbps(1_000_000, 2000), 1e-9)
        assertEquals(0.0, SpeedMath.mbps(1_000_000, 0), 0.0)
        assertEquals(0.0, SpeedMath.mbps(-1, 1000), 0.0)
    }

    @Test fun megabytesPerSecondIsMbpsOverEight() {
        assertEquals(1.0, SpeedMath.mbPerSec(8.0), 1e-12)
        assertEquals(12.5, SpeedMath.mbPerSec(100.0), 1e-12)
        assertEquals(0.0, SpeedMath.mbPerSec(0.0), 0.0)
    }

    @Test fun averageIsDurationWeighted() {
        val now = 100 * h
        // 8 Mbps for 1 s, 80 Mbps for 9 s: (1 MB + 90 MB) over 10 s = 72.8 Mbps, not the plain mean 44.
        val all = listOf(s(now - h, 1_000_000, 1000), s(now - 2 * h, 90_000_000, 9000))
        assertEquals(72.8, SpeedMath.average(all, now)!!, 1e-9)
    }

    @Test fun samplesOlderThan24hAreOutsideTheWindow() {
        val now = 100 * h
        val old = s(now - 24 * h - 1, 100_000_000, 1000)     // 800 Mbps, one ms too old
        val edge = s(now - 24 * h, 1_000_000, 1000)          // exactly 24 h: still in
        val fresh = s(now - h, 3_000_000, 1000)
        assertEquals(listOf(edge, fresh), SpeedMath.inWindow(listOf(old, fresh, edge), now))
        assertEquals(16.0, SpeedMath.average(listOf(old, edge, fresh), now)!!, 1e-9)   // (1+3) MB over 2 s
        assertEquals(listOf(edge, fresh), SpeedMath.prune(listOf(old, fresh, edge), now))
    }

    @Test fun noMeasuredTimeIsNoAverage() {
        assertNull(SpeedMath.average(emptyList(), 1000))
        assertNull(SpeedMath.average(listOf(s(1000, 5, 0)), 1000))
        assertNull(SpeedMath.average(listOf(s(0, 5_000_000, 1000)), 30 * h))
    }

    @Test fun pruneKeepsTheNewestCap() {
        val now = 10 * h
        val many = (0 until SpeedMath.MAX_SAMPLES + 5).map { s(now - 1000L + it.toLong() % 1000, 1, 1) }
        assertEquals(SpeedMath.MAX_SAMPLES, SpeedMath.prune(many, now).size)
    }

    @Test fun storedFormRoundTripsAndSkipsGarbage() {
        val a = listOf(SpeedSample(1, 2, 3, true), SpeedSample(4, 5, 6, false))
        assertEquals(a, SpeedMath.decode(SpeedMath.encode(a)))
        assertEquals(a, SpeedMath.decode(SpeedMath.encode(a) + "\nnope\n1,x,3,1"))
        assertEquals(emptyList<SpeedSample>(), SpeedMath.decode(""))
    }

    @Test fun linesShowBothUnits() {
        assertEquals("now 40.0Mb 5.0MB/s", SpeedMath.line("now", 40.0))
        assertEquals("24h 120Mb 15.0MB/s", SpeedMath.line("24h", 120.0))
        assertEquals("24h --", SpeedMath.line("24h", null))
        assertEquals("0.0", SpeedMath.fmt(0.0))
    }

    @Test fun uploadAverageRowLineAndLegacyCodec() {
        val now = 100 * h
        val all = listOf(SpeedSample(now - h, 1_000_000, 1000, false, txBytes = 500_000), SpeedSample(now - 2 * h, 0, 1000, false, txBytes = 500_000))
        assertEquals(4.0, SpeedMath.averageTx(all, now)!!, 1e-9)                    // 1 MB over 2 s = 4 Mbps
        assertNull(SpeedMath.averageTx(emptyList(), now))
        assertEquals("Now  \u2193 12.3Mb  \u2191 --Mb", SpeedMath.rowLine("Now", 12.34, null))
        assertEquals(all, SpeedMath.decode(SpeedMath.encode(all)))
        assertEquals(listOf(SpeedSample(1, 2, 3, true, 0L)), SpeedMath.decode("1,2,3,1"))   // pre-#1508 four-field line
    }

    @Test fun netDevParsesInterfacesAndSkipsLoopback() {
        val txt = "Inter-|   Receive\n face |bytes packets errs drop fifo frame compressed multicast|bytes packets\n" +
            "    lo: 100 1 0 0 0 0 0 0 200 1 0 0 0 0 0 0\n wlan0: 5000 10 0 0 0 0 0 0 7000 9 0 0 0 0 0 0\n"
        assertEquals(mapOf("wlan0" to (5000L to 7000L)), SpeedMath.parseNetDev(txt))
    }
}

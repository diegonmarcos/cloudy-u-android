package com.diegonmarcos.superapp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogGateTest {

    /** A controllable clock plus a captured sink: no android, no real time. */
    private class Harness(windowMs: Long = 60_000L) {
        var nowMs = 0L
        val out = mutableListOf<String>()
        val gate = LogGate(windowMs = windowMs, clock = { nowMs }, sink = { out.add(it) })
    }

    @Test fun `the first line of a key is emitted immediately`() {
        val h = Harness()
        h.gate.log("tick: alive")
        assertEquals(listOf("tick: alive"), h.out)
    }

    @Test fun `identical repeats inside the window are swallowed, not printed`() {
        val h = Harness(windowMs = 60_000L)
        repeat(30) { h.nowMs += 2_000L; h.gate.log("tick: alive") }
        // First log at t=2s emits and opens the window; every later tick up to
        // t=60s is inside [2s, 62s) and is swallowed. One line, not thirty.
        h.gate.log("tick: alive")   // still t=60s, inside the window -> swallowed
        assertEquals("one line, not thirty", 1, h.out.size)
    }

    @Test fun `the next window's emission carries the suppressed count and the last-seen age`() {
        val h = Harness(windowMs = 10_000L)
        h.gate.log("beat")                 // emit at t=0, opens window [0,10_000)
        assertEquals(1, h.out.size)
        h.nowMs = 3_000L; h.gate.log("beat")   // swallowed (+1), lastSeen=3_000
        h.nowMs = 6_000L; h.gate.log("beat")   // swallowed (+2), lastSeen=6_000
        h.nowMs = 11_000L; h.gate.log("beat")  // window elapsed -> emit, naming the 2 swallowed
        assertEquals(2, h.out.size)
        val second = h.out[1]
        assertTrue(second, second.startsWith("beat"))
        assertTrue(second, second.contains("+2 more"))
        assertTrue(second, second.contains("last 5s ago"))   // 11_000 - 6_000 = 5s
    }

    @Test fun `a line that changes is a different key and emits at once`() {
        val h = Harness()
        h.gate.log("tick: NOTHING")
        h.nowMs += 1_000L; h.gate.log("tick: NOTHING")  // swallowed
        h.nowMs += 1_000L; h.gate.log("tick: REARM")    // different -> emits now
        assertEquals(listOf("tick: NOTHING", "tick: REARM"), h.out)
    }

    @Test fun `distinct keys can share a message yet gate independently`() {
        val h = Harness()
        h.gate.log("k1", "same text")
        h.gate.log("k2", "same text")   // different key -> emitted
        assertEquals(2, h.out.size)
    }
}

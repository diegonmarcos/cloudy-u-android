package com.diegonmarcos.superapp.netwg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.io.IOException

/** #1798 Happy Eyeballs for the TLS-443 relay, with no network: routes and attempts are scripted. */
class HappyEyeballsTest {
    private val v6 = "2603:c026:c104:8f00:0:c704:63b:6eee"
    private val v4 = "129.151.228.66"

    private class Conn(val addr: String) : Closeable { var closed = false; override fun close() { closed = true } }

    @Test fun `a family with no route is skipped up front and says why`() {
        val (order, skipped) = HappyEyeballs.plan(listOf(v6, v4)) { ':' !in it }
        assertEquals(listOf(v4), order)
        assertEquals(v6, skipped.single().addr)
        assertNull(skipped.single().ok)
        assertTrue(skipped.single().line(), skipped.single().line().startsWith("v6 $v6 skipped: no IPv6 route"))
    }

    @Test fun `families interleave, the first address's family first`() {
        val a = listOf("2001:db8::1", "2001:db8::2", "192.0.2.1", "192.0.2.2")
        assertEquals(listOf("2001:db8::1", "192.0.2.1", "2001:db8::2", "192.0.2.2"), HappyEyeballs.plan(a) { true }.first)
        assertEquals(listOf("192.0.2.1", "2001:db8::1"), HappyEyeballs.plan(listOf("192.0.2.1", "2001:db8::1")) { true }.first)
    }

    @Test fun `a hanging first address does not hold the race - the next one starts after the stagger and wins`() {
        val t0 = System.currentTimeMillis()
        val (won, log) = HappyEyeballs.race(listOf(v6, v4), { a -> if (a == v6) { Thread.sleep(3_000); Conn(a) } else Conn(a) }, staggerMs = 100)
        val ms = System.currentTimeMillis() - t0
        assertEquals(v4, won!!.second)
        assertTrue("the v4 attempt won in $ms ms, not after the v6 timeout", ms < 1_500)
        assertTrue(log.any { it.addr == v4 && it.ok == true })
        assertTrue(log.any { it.addr == v6 && it.detail.startsWith("abandoned") })
    }

    @Test fun `a fast failure starts the next attempt at once and every failure is named`() {
        val (won, log) = HappyEyeballs.race(listOf(v6, v4), { a -> throw IOException("connect failed: ENETUNREACH ($a)") }, staggerMs = 5_000)
        assertNull(won)
        assertEquals(listOf(v6, v4), log.map { it.addr })
        assertTrue(log.all { it.ok == false && "ENETUNREACH" in it.detail })
        val text = HappyEyeballs.text(log)
        assertTrue(text, "v6 $v6 FAIL" in text && "v4 $v4 FAIL" in text)
    }

    @Test fun `a loser that connects after the winner is closed`() {
        val conns = java.util.concurrent.ConcurrentLinkedQueue<Conn>()
        val (won, _) = HappyEyeballs.race(listOf("a", "b"), { a ->
            if (a == "a") { Thread.sleep(300); Conn(a).also { conns += it } } else Conn(a).also { conns += it }
        }, staggerMs = 50)
        assertEquals("b", won!!.second)
        Thread.sleep(800)
        assertTrue("the late connection to a was closed", conns.single { it.addr == "a" }.closed)
    }
}

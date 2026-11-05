package com.diegonmarcos.superapp.appstore.dl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1517 Update checks: short TTL cache, one in-flight check per key, forced checks skip the cache but still join. */
class CheckGateTest {
    @Test fun answersFromTheCacheInsideTheTtlAndRecomputesAfterIt() {
        var t = 0L
        val g = CheckGate<String, Int>(1_000, { t })
        val n = AtomicInteger()
        assertEquals(1, g.get("a") { n.incrementAndGet() })
        assertEquals(1, g.get("a") { n.incrementAndGet() })
        t = 999; assertEquals(1, g.get("a") { n.incrementAndGet() })
        t = 1_001; assertEquals(2, g.get("a") { n.incrementAndGet() })
        assertEquals("another key is its own check", 3, g.get("b") { n.incrementAndGet() })
    }

    @Test fun forceSkipsTheCache() {
        val g = CheckGate<String, Int>(60_000)
        val n = AtomicInteger()
        g.get("a") { n.incrementAndGet() }
        assertEquals(2, g.get("a", force = true) { n.incrementAndGet() })
        assertEquals(2, g.peek("a"))
        g.invalidate("a")
        assertEquals(null, g.peek("a"))
    }

    @Test fun twoCallersAtOnceRunOneCheckAndBothGetItsAnswer() {
        val g = CheckGate<String, Int>(0)   // no TTL: only the in-flight de-duplication is under test
        val n = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val results = java.util.Collections.synchronizedList(ArrayList<Int>())
        val a = thread { results += g.get("k", force = true) { started.countDown(); release.await(); n.incrementAndGet() } }
        started.await()
        val b = thread { results += g.get("k", force = true) { n.incrementAndGet() } }
        Thread.sleep(100)
        release.countDown()
        a.join(); b.join()
        assertEquals("one network check", 1, n.get())
        assertEquals(listOf(1, 1), results.toList())
    }

    @Test fun aFailedCheckIsNotCachedAndThrowsToEveryCaller() {
        val g = CheckGate<String, Int>(60_000)
        try { g.get("a") { error("offline") }; assertTrue(false) } catch (e: IllegalStateException) { assertEquals("offline", e.message) }
        assertEquals(7, g.get("a") { 7 })
    }

    @Test fun throttleLetsOneThrough() {
        var t = 0L
        val th = Throttle(1_000, { t })
        assertTrue(th.tryAcquire())
        assertFalse(th.tryAcquire())
        t = 1_000; assertTrue(th.tryAcquire())
    }
}

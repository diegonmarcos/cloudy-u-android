package org.fossify.phone.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #1735 A fake PackageInstaller with Android's per-UID cap: proves no session leaks on any failure path, the cap path reaps, installs are bounded. */
class DialerInstallSessionLeakTest {
    class FakeInstaller(val cap: Int = 50) : SessionPort {
        val sessions = LinkedHashMap<Int, SessionView>()
        var next = 1
        var clock = 1_000L
        var failCreate = 0   // fail this many create() calls with a non-cap error
        override fun mine() = sessions.values.toList()
        override fun abandon(id: Int) { sessions.remove(id) ?: error("no session $id") }
        override fun create(): Int {
            if (failCreate > 0) { failCreate--; throw IllegalStateException("Too many active sessions for UID 10469") }
            if (sessions.size >= cap) throw IllegalStateException("Too many active sessions for UID 10469")
            val id = next++
            sessions[id] = SessionView(id, "p$id", committed = false, active = false, createdMs = clock++)
            return id
        }
        fun commit(id: Int) { sessions[id] = sessions.getValue(id).copy(committed = true) }
        fun finish(id: Int) { sessions.remove(id) }
    }

    private fun open(f: FakeInstaller, sleeps: MutableList<Long> = ArrayList(), superseded: MutableList<Int> = ArrayList()) =
        InstallSessions.open(f, now = { f.clock }, sleep = { sleeps += it }, onSuperseded = { superseded += it })

    @Test fun aThrowBetweenCreateAndCommitAbandonsTheSession() {
        val f = FakeInstaller()
        val id = open(f)
        try { InstallSessions.guarded<Unit>(f, id) { error("openWrite died") } } catch (_: IllegalStateException) {}
        assertEquals(0, f.sessions.size)
    }

    @Test fun cancellationAndTimeoutAbandonTheSession() {
        for (t in listOf<Throwable>(InterruptedException("cancelled"), java.util.concurrent.CancellationException("c"), java.util.concurrent.TimeoutException("t"), OutOfMemoryError("oom"))) {
            val f = FakeInstaller()
            val id = open(f)
            try { InstallSessions.guarded<Unit>(f, id) { throw t } } catch (_: Throwable) {}
            assertEquals("leaked after ${t.javaClass.simpleName}", 0, f.sessions.size)
        }
    }

    @Test fun aCommittedSessionIsNotAbandoned() {
        val f = FakeInstaller()
        val id = open(f)
        InstallSessions.guarded<Unit>(f, id) { f.commit(id); true to Unit }
        assertTrue(f.sessions.getValue(id).committed)
    }

    @Test fun anAbandonThatFailsStillPropagatesTheOriginalError() {
        val f = object : SessionPort by FakeInstaller() { override fun abandon(id: Int) = error("gone already") }
        try { InstallSessions.guarded<Unit>(f, 1) { throw IllegalArgumentException("first") }; fail() }
        catch (e: IllegalArgumentException) { assertEquals("first", e.message) }
    }

    @Test fun fortyInstallsWithMixedFailuresLeaveZeroOpenSessions() {
        val f = FakeInstaller()
        var peak = 0
        repeat(40) { i ->
            val id = open(f)
            peak = maxOf(peak, f.sessions.size)
            try {
                InstallSessions.guarded<Unit>(f, id) {
                    if (i % 4 == 0) error("write failed")
                    if (i % 7 == 0) throw InterruptedException("cancelled")
                    f.commit(id); true to Unit
                }
                f.finish(id)   // the system answers the committed install
            } catch (_: Throwable) {}
        }
        assertEquals(0, f.sessions.size)
        assertTrue("one in flight at a time, peak was $peak", peak <= 1)
    }

    @Test fun theCapErrorReapsAndBacksOffInsteadOfRetryingBlind() {
        val f = FakeInstaller()
        // 50 unanswered prompts: committed, so no plain reap frees anything and the cap is real.
        repeat(50) { val id = f.create(); f.commit(id) }
        val sleeps = ArrayList<Long>(); val sup = ArrayList<Int>()
        val id = open(f, sleeps, sup)
        assertTrue("a session opened", id > 50)
        assertTrue("the oldest unanswered prompts were reclaimed", sup.isNotEmpty() && sup.size >= 26)
        assertTrue("held count is back under the budget", f.sessions.size <= InstallSessions.BUDGET_CAP - InstallSessions.HEADROOM + 1)
        assertTrue("the newest prompts survive", f.sessions.containsKey(50))
    }

    @Test fun aCapErrorRetriesWithBackoffThenGivesUp() {
        val f = FakeInstaller(); f.failCreate = 2
        val sleeps = ArrayList<Long>()
        val id = open(f, sleeps)
        assertTrue(id > 0)
        assertEquals(listOf(500L, 2_000L), sleeps)

        val g = FakeInstaller(); g.failCreate = 99
        val gs = ArrayList<Long>()
        try { open(g, gs); fail("must throw") } catch (e: IllegalStateException) { assertTrue(InstallSessions.isCapError(e)) }
        assertEquals(InstallSessions.CAP_BACKOFF_MS.toList(), gs)
    }

    @Test fun aNonCapErrorIsNotRetried() {
        val port = object : SessionPort {
            var calls = 0
            override fun mine() = emptyList<SessionView>()
            override fun abandon(id: Int) {}
            override fun create(): Int { calls++; throw SecurityException("denied") }
        }
        try { InstallSessions.open(port, sleep = { fail("no sleep") }); fail() } catch (_: SecurityException) {}
        assertEquals(1, port.calls)
    }

    @Test fun uncommittedLeaksAreReapedBeforeTheCreateAndActiveOnesNever() {
        val f = FakeInstaller()
        repeat(35) { f.create() }   // 35 leaked, uncommitted
        f.sessions[1] = f.sessions.getValue(1).copy(active = true)   // a write in flight
        val id = open(f)
        assertTrue(f.sessions.containsKey(1))
        assertEquals(setOf(1, id), f.sessions.keys)
    }

    @Test fun theSamePackageIsKept() {
        val s = listOf(SessionView(1, "a", true, false, 1), SessionView(2, "b", true, false, 2))
        assertTrue(InstallSessions.victims(s + (3..60).map { SessionView(it, "x$it", true, false, it.toLong()) }, 99, true, keep = setOf("a"))
            .none { it.pkg == "a" })
        assertTrue(InstallSessions.victims(s, 99, aggressive = false).isEmpty())
    }

    @Test fun staleUncommittedAreReapedBelowTheCapButYoungOnesAreNot() {
        val now = 10L * 60 * 60 * 1000
        val old = SessionView(1, "a", false, false, now - InstallSessions.STALE_SESSION_MS - 1)
        val young = SessionView(2, "b", false, false, now - 1000)
        val prompt = SessionView(3, "c", true, false, 1)
        assertEquals(listOf(1), InstallSessions.victims(listOf(old, young, prompt), now, false).map { it.id })
    }

    @Test fun capErrorDetectionLooksThroughCauses() {
        assertTrue(InstallSessions.isCapError(RuntimeException("x", IllegalStateException("Too many active sessions for UID 1"))))
        assertFalse(InstallSessions.isCapError(RuntimeException("other")))
    }
}

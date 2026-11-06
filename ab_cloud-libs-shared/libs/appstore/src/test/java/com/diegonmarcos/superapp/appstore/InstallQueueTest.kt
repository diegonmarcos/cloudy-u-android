package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.appstore.StoreActivity.Phase
import com.diegonmarcos.superapp.appstore.dl.CheckGate
import com.diegonmarcos.superapp.devtools.RetryBackoff
import com.diegonmarcos.superapp.updater.install.InstallFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1551 THE INSTALL QUEUE FINISHES THE JOB: what a failure means, how retries are counted and spaced, that the ledger survives a
 * restart, what the Activity panel says per item, and that page opens inside the check TTL cost one network check. Every claim has
 * its control beside it.
 */
class InstallQueueTest {

    // ── what a failure means ─────────────────────────────────────────────────

    /** The receiver's note for the phone's case: the status message, then the legacy code name. */
    private val abandoned = "INSTALL_FAILED_ABORTED: Session was abandoned · code: INSTALL_FAILED_ABORTED (the session was abandoned or cancelled before it finished)"

    @Test fun anAbandonedSessionIsRetriedNotHeld() {
        assertEquals(InstallFailure.Kind.TRANSIENT, InstallFailure.classify(abandoned))
        assertEquals(InstallFailure.Kind.TRANSIENT, InstallFailure.classify("TIMEOUT (the system stopped waiting on this session before it finished)"))
        assertEquals(InstallFailure.Kind.TRANSIENT, InstallFailure.classify("install of x did not settle in 180000ms"))
    }

    @Test fun theUsersNoIsADeclineEvenThoughItIsAlsoAborted() {
        // Same status and legacy code as the abandoned session: only the message tells them apart.
        assertEquals(InstallFailure.Kind.DECLINED, InstallFailure.classify(
            "User rejected permissions · code: INSTALL_FAILED_ABORTED (the session was abandoned or cancelled before it finished)"))
    }

    @Test fun aFactAboutTheBytesIsPermanent() {
        assertEquals(InstallFailure.Kind.PERMANENT, InstallFailure.classify("INSTALL_FAILED_UPDATE_INCOMPATIBLE (signature differs from the installed copy)"))
        assertEquals(InstallFailure.Kind.PERMANENT, InstallFailure.classify("INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(InstallFailure.Kind.PERMANENT, InstallFailure.classify(""))
    }

    // ── retries: counted once, spaced by the backoff with the 30 s floor, bounded ──

    @Test fun theFirstRetryWaitsTheFloorAndEachNextOneLonger() {
        val e1 = InstallRetries.register(null, "a", "b1", abandoned, at = 1_000)
        assertEquals(1, e1.attempts)
        assertEquals(1_000 + RetryBackoff.MIN_MS, e1.nextAt)
        val e2 = InstallRetries.register(e1, "a", "b1", abandoned, at = 50_000)
        assertEquals(2, e2.attempts)
        assertEquals(50_000 + RetryBackoff.afterFailures(2), e2.nextAt)
        assertTrue("each wait is longer than the one before", e2.nextAt - 50_000 > e1.nextAt - 1_000)
        assertFalse(e1.due(1_000 + RetryBackoff.MIN_MS - 1))
        assertTrue(e1.due(1_000 + RetryBackoff.MIN_MS))
    }

    @Test fun noWaitIsEverBelowTheFloor() {
        val e = InstallRetries.register(null, "a", "b1", abandoned, at = 0, backoff = { 5L })
        assertEquals(RetryBackoff.MIN_MS, e.nextAt)
        assertEquals(RetryBackoff.MIN_MS, InstallRetries.nextDelay(listOf(e), max = 3, now = e.nextAt + 1))
    }

    @Test fun theSameFailureReadTwiceIsCountedOnce() {
        val e1 = InstallRetries.register(null, "a", "b1", abandoned, at = 1_000)
        assertSame(e1, InstallRetries.register(e1, "a", "b1", abandoned, at = 1_000))
        // Control: a later failure counts.
        assertEquals(2, InstallRetries.register(e1, "a", "b1", abandoned, at = 2_000).attempts)
    }

    @Test fun aNewBuildStartsTheCountAgain() {
        val old = InstallRetries.register(InstallRetries.register(null, "a", "b1", abandoned, 1_000), "a", "b1", abandoned, 2_000)
        assertEquals(2, old.attempts)
        assertEquals(1, InstallRetries.register(old, "a", "b2", abandoned, 3_000).attempts)
        assertEquals(null, InstallRetries.forBuild(old, "b2"))
    }

    @Test fun theFinalFailureComesOnlyAfterTheRetriesAreUsedUp() {
        var e: InstallRetries.Entry? = null
        for (n in 1..3) { e = InstallRetries.register(e, "a", "b1", abandoned, n * 1_000L); assertFalse("attempt $n still retries", e.exhausted(3)) }
        e = InstallRetries.register(e, "a", "b1", abandoned, 4_000)
        assertTrue("the 4th failure with 3 retries is final", e.exhausted(3))
        assertEquals(null, InstallRetries.nextDelay(listOf(e), max = 3, now = 0))
        assertEquals("failed after 4 attempts", e.text(3) { "x" })
    }

    // ── persistence: the ledger survives the process ─────────────────────────

    @Test fun theLedgerRoundTripsThroughItsPersistedForm() {
        val e = InstallRetries.register(null, "lib-a", "abc123", abandoned, at = 7_000)
        val back = InstallRetries.fromJson(org.json.JSONObject(InstallRetries.toJson(mapOf("lib-a" to e)).toString()))
        assertEquals(e, back["lib-a"])
        assertTrue(InstallRetries.fromJson(null).isEmpty())
    }

    // ── the Activity panel: per-item queue state, every old field kept ───────

    @Test fun thePanelSaysWhatEachQueuedAppWaitsFor() {
        val q = listOf(
            StoreActivity.QueueEntry("p.wait", "Wait", "pending"),
            StoreActivity.QueueEntry("p.retry", "Retry", "retry", attempts = 1, retryMax = 3, nextText = "14:05", reason = "Session was abandoned"),
            StoreActivity.QueueEntry("p.fail", "Fail", "failed", reason = "install did not finish after 4 attempts: Session was abandoned"),
            StoreActivity.QueueEntry("p.done", "Done", "installed"),
            StoreActivity.QueueEntry("p.q", "Q", "queued"),
            StoreActivity.QueueEntry("host", "Host", "handed"),
        )
        val items = StoreActivity.items(emptyList(), emptyList(), q).associateBy { it.key }
        assertEquals(Phase.WAITING_CONFIRM, items["p.wait"]!!.phase)
        assertEquals("Waiting for your confirmation · tap the notification", items["p.wait"]!!.phaseText())
        assertEquals("Retry 1/3 · next 14:05 · Session was abandoned", items["p.retry"]!!.phaseText())
        assertEquals("Failed: install did not finish after 4 attempts: Session was abandoned", items["p.fail"]!!.phaseText())
        assertEquals(Phase.DONE, items["p.done"]!!.phase)
        assertEquals(Phase.QUEUED, items["p.q"]!!.phase)
        assertFalse("the handed-over host is not a queue row", "host" in items)
        val o = StoreActivity.overall(items.values.toList())
        assertEquals(1, o.confirming); assertEquals(1, o.retrying)
        assertTrue("waiting and retrying keep the panel busy, never 'All up to date'", o.busy)
        assertTrue(o.text(), o.text().contains("1 waiting for confirmation") && o.text().contains("1 retrying"))
    }

    @Test fun aRunningTransferSpeaksForItselfOverTheQueue() {
        val dl = com.diegonmarcos.superapp.appstore.dl.DlJob(id = "a", pkg = "p.a", versionCode = 1, label = "A", namePrefix = "dl-a-",
            priority = 0, userStarted = false, addedAt = 1_000, state = com.diegonmarcos.superapp.appstore.dl.DlState.RUNNING, bytes = 10, total = 100)
        val one = StoreActivity.items(listOf(dl), emptyList(), listOf(StoreActivity.QueueEntry("p.a", "A", "retry", 1, 3))).single()
        assertEquals(Phase.DOWNLOADING, one.phase)
        // Control: once it is no longer active, the queue's retry state shows.
        val idle = dl.copy(state = com.diegonmarcos.superapp.appstore.dl.DlState.DONE)
        assertEquals(Phase.RETRY, StoreActivity.items(listOf(idle), emptyList(), listOf(StoreActivity.QueueEntry("p.a", "A", "retry", 1, 3))).single().phase)
    }

    @Test fun withoutAQueueTheModelIsUnchanged() {
        assertEquals(StoreActivity.items(emptyList(), emptyList()), StoreActivity.items(emptyList(), emptyList(), emptyList()))
    }

    // ── the update check: at most once per declared TTL, lazily on page open ──

    @Test fun theCheckTtlIsTheDeclaredMinute() {
        assertEquals(60_000L, BuildConfig.DL_CHECK_TTL_MS)
    }

    @Test fun twoPageOpensWithinSixtySecondsCauseOneNetworkCheck() {
        var clock = 0L
        var network = 0
        val gate = CheckGate<String, String>(BuildConfig.DL_CHECK_TTL_MS) { clock }
        fun pageOpen() = gate.get("app") { network++; "update" }
        pageOpen()
        clock += 59_000
        assertEquals("update", pageOpen())
        assertEquals("the second open inside 60 s is answered from the cache", 1, network)
        clock += 2_000
        pageOpen()
        assertEquals("control: an open after the cache is older than 60 s checks again", 2, network)
        gate.get("app", force = true) { network++; "update" }
        assertEquals("pull-to-refresh always checks", 3, network)
    }
}

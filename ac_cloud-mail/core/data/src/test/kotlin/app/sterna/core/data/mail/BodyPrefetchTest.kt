package app.sterna.core.data.mail

import app.sterna.core.data.account.AGE_WINDOW_SAFETY_CEILING
import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.db.BodyCacheRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1763: body prefetch into the cache, its throttle, the 90-day window and the eviction. */
class BodyPrefetchTest {

    /** A folder newest first; [stored] is the body cache; [fetchLog] the Email/get batches in call order. */
    private class FakePort(val folder: List<String>, stored: Set<String> = emptySet(), val latencyMs: Long = 0) : BodyPrefetchPort {
        val stored = stored.toMutableSet()
        val fetchLog = mutableListOf<List<String>>()
        var inFlight = 0
        var maxInFlight = 0
        var onFetch: () -> Unit = {}

        override suspend fun missingNewest(limit: Int) = folder.filter { it !in stored }.take(limit)
        override suspend fun missingAmong(ids: List<String>) = ids.filter { it !in stored }
        override suspend fun fetchAndStore(ids: List<String>) {
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            fetchLog += ids
            onFetch()
            if (latencyMs > 0) delay(latencyMs)
            stored += ids
            inFlight--
        }
    }

    private val wifi = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)

    // -- prefetch stores bodies ---------------------------------------------------------------------

    @Test fun `a prefetch pass stores the body of every message in the window, newest first, in batches`() = runTest {
        val folder = (1..60).map { "m$it" } // m1 newest
        val port = FakePort(folder)
        val result = BodyPrefetcher(port, { wifi }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals(folder.toSet(), port.stored)
        assertEquals(60, result.requested)
        assertEquals(3, result.batches)
        assertEquals("batched Email/get, not one call per message", listOf(25, 25, 10), port.fetchLog.map { it.size })
        assertEquals("newest first", folder, port.fetchLog.flatten())
        assertNull(result.skipped)
    }

    @Test fun `a message that already has a body is not fetched again`() = runTest {
        val port = FakePort((1..30).map { "m$it" }, stored = setOf("m1", "m2", "m3"))
        BodyPrefetcher(port, { wifi }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals((4..30).map { "m$it" }, port.fetchLog.flatten())
    }

    @Test fun `the neighbours of the open mail are fetched and the ones already stored are not`() = runTest {
        val port = FakePort(listOf("a", "b", "c", "d", "e"), stored = setOf("b"))
        val result = BodyPrefetcher(port, { wifi }).run(PrefetchTrigger.NEIGHBOUR, listOf("b", "d", "a", "e"))
        assertEquals(listOf(listOf("d", "a", "e")), port.fetchLog)
        assertEquals(3, result.requested)
        assertTrue(port.stored.containsAll(listOf("a", "b", "d", "e")))
    }

    @Test fun `a failing batch does not stop the next one`() = runTest {
        val port = FakePort((1..50).map { "m$it" })
        var calls = 0
        port.onFetch = { if (calls++ == 0) error("timeout") }
        val result = BodyPrefetcher(port, { wifi }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals(2, port.fetchLog.size)
        assertEquals(1, result.batches)
        assertEquals("incomplete", result.skipped)
    }

    // -- throttle conditions --------------------------------------------------------------------------

    @Test fun `the throttle by device condition`() {
        fun b(connected: Boolean, unmetered: Boolean, saver: Boolean, t: PrefetchTrigger) =
            prefetchBudget(PrefetchConditions(connected, unmetered, saver), t)
        val list = PrefetchTrigger.LIST_SYNC
        val near = PrefetchTrigger.NEIGHBOUR
        // No network: nothing, for either trigger.
        assertFalse(b(false, false, false, list).allowed)
        assertFalse(b(false, true, false, near).allowed)
        // Wi-Fi: the whole window, capped concurrency.
        val wifiBudget = b(true, true, false, list)
        assertEquals(PREFETCH_PASS_MAX_IDS, wifiBudget.maxIds)
        assertEquals(PREFETCH_MAX_CONCURRENCY, wifiBudget.concurrency)
        assertEquals(PREFETCH_BATCH, wifiBudget.batchSize)
        // Metered: only the newest batch, one call at a time.
        val metered = b(true, false, false, list)
        assertEquals(PREFETCH_METERED_MAX_IDS, metered.maxIds)
        assertEquals(1, metered.concurrency)
        // Battery saver: no list prefetch, on Wi-Fi or mobile data.
        assertFalse(b(true, true, true, list).allowed)
        assertFalse(b(true, false, true, list).allowed)
        // Neighbours: a few messages; none on battery saver over a metered link.
        assertEquals(PREFETCH_NEIGHBOURS_EACH_SIDE * 2, b(true, false, false, near).maxIds)
        assertFalse(b(true, false, true, near).allowed)
        assertTrue(b(true, true, true, near).allowed)
    }

    @Test fun `battery saver or no network means no fetch at all`() = runTest {
        val port = FakePort((1..40).map { "m$it" })
        val saver = BodyPrefetcher(port, { PrefetchConditions(true, true, batterySaver = true) }).run(PrefetchTrigger.LIST_SYNC)
        val offline = BodyPrefetcher(port, { PrefetchConditions(false, false, false) }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals("conditions", saver.skipped)
        assertEquals("conditions", offline.skipped)
        assertTrue(port.fetchLog.isEmpty())
    }

    @Test fun `a metered link fetches only the newest batch`() = runTest {
        val folder = (1..100).map { "m$it" }
        val port = FakePort(folder)
        BodyPrefetcher(port, { PrefetchConditions(true, unmetered = false, batterySaver = false) }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals(folder.take(PREFETCH_METERED_MAX_IDS), port.fetchLog.flatten())
    }

    @Test fun `concurrency never exceeds the cap`() = runTest {
        val port = FakePort((1..500).map { "m$it" }, latencyMs = 50)
        BodyPrefetcher(port, { wifi }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals(500, port.stored.size)
        assertEquals(PREFETCH_MAX_CONCURRENCY, port.maxInFlight)
    }

    @Test fun `battery saver switched on mid-pass stops the remaining batches`() = runTest {
        val port = FakePort((1..200).map { "m$it" })
        var saver = false
        port.onFetch = { if (port.fetchLog.size == 2) saver = true }
        val result = BodyPrefetcher(port, { PrefetchConditions(true, true, saver) }).run(PrefetchTrigger.LIST_SYNC)
        assertEquals(2, port.fetchLog.size)
        assertEquals("incomplete", result.skipped)
    }

    // -- the 90-day window ------------------------------------------------------------------------------

    @Test fun `the list window defaults to 90 days and the bodies to every message`() {
        assertEquals(SyncWindow.DAYS_90, StoredAccount(id = "a", server = "s", username = "u").syncWindow)
        assertEquals(90, SyncWindow.DAYS_90.maxAgeDays)
        assertEquals("#1952 fully cached: every body, whatever its age", OfflineWindow.ALL, OfflineWindow.DEFAULT)
        assertNull(OfflineWindow.DEFAULT.cutoffMillis(1_000L * 86_400_000L))
        assertEquals(90, OfflineWindow.THREE.days)
    }

    @Test fun `an age window is not capped at 200 messages`() {
        for (w in listOf(SyncWindow.DAYS_30, SyncWindow.DAYS_90, SyncWindow.YEAR_1)) {
            assertEquals("${w.name} walks its whole age", AGE_WINDOW_SAFETY_CEILING, w.walkLimit)
            assertTrue(w.walkLimit > 200)
        }
        // A count window stays what it says.
        assertEquals(1_000, SyncWindow.COUNT_1000.walkLimit)
        assertNull(SyncWindow.COUNT_1000.walkCutoffMillis(1_000_000L))
        val day = 86_400_000L
        assertEquals(1_000L * day - 90 * day, SyncWindow.DAYS_90.walkCutoffMillis(1_000L * day))
    }

    // -- eviction ----------------------------------------------------------------------------------------

    private val day = 86_400_000L
    private fun row(id: String, ageDays: Long, bytes: Long = 100) = BodyCacheRow(id, 1_000L * day - ageDays * day, bytes)

    @Test fun `bodies older than the window are evicted and the ones inside stay`() {
        val rows = listOf(row("new", 1), row("edge", 89), row("old", 91), row("ancient", 400))
        val drop = planBodyEviction(rows, cutoffMillis = 1_000L * day - 90 * day)
        assertEquals(setOf("old", "ancient"), drop.toSet())
    }

    @Test fun `all keeps every body unless the byte cap is hit, then the oldest go first`() {
        val rows = listOf(row("a", 1, 400), row("b", 50, 400), row("c", 300, 400))
        assertTrue(planBodyEviction(rows, cutoffMillis = null, maxBytes = 10_000).isEmpty())
        assertEquals(listOf("c"), planBodyEviction(rows, cutoffMillis = null, maxBytes = 800))
        assertEquals(listOf("c", "b"), planBodyEviction(rows, cutoffMillis = null, maxBytes = 400))
    }

    @Test fun `the cap counts only what the window kept`() {
        val rows = listOf(row("new", 1, 500), row("old", 200, 5_000))
        assertEquals(listOf("old"), planBodyEviction(rows, cutoffMillis = 1_000L * day - 90 * day, maxBytes = 600))
    }

    // -- wiring (source lint, like the other repository pins: MailRepository cannot be built in a JVM test) ----

    private fun bodyOf(function: String) = DaoQuerySource.mailFunctionBody("MailRepository", function)

    @Test fun `an open with a cached body makes no network call`() {
        val open = bodyOf("openMessage")
        val cached = open.indexOf("cachedMessage(credentials.id, emailId)?.let")
        val network = open.indexOf("openEmail(credentials, emailId, markRead)")
        assertTrue("openMessage reads the body cache", cached > 0)
        assertTrue("and only then the network", network > cached)
        val hit = open.substring(cached, network)
        for (call in listOf("connect(", "client.", "openEmail(", "fetchInlineImages(", "downloadAttachment(")) {
            assertFalse("the cache-hit branch must not call $call", call in hit.replace("bgScope.launch { runCatching { ensureInlineImages(", ""))
        }
        assertTrue("a hit returns the cached body", "return cached" in hit)
        // cachedMessage itself is a Room read and nothing else.
        val read = bodyOf("cachedMessage")
        assertFalse("connect(" in read || "client." in read)
    }

    @Test fun `every sync chunk requests a prefetch and the prefetch stays in the inbox`() {
        assertTrue("onSyncChunkLanded(" in bodyOf("catchUpFolder"))
        assertTrue("onSyncChunkLanded(" in bodyOf("syncMailboxWindow"))
        assertTrue(
            "a prefetched body hands the sender's key to OpenKeychain: only the inbox is ever warmed",
            """mailboxDao.roleForId(credentials.id, mailboxId) != "inbox") return""" in bodyOf("prefetchFolderBodies"),
        )
        // #1869: the neighbours are warmed in EVERY folder, but outside the Inbox only through the raw store, which
        // never imports a key (the #1768 rule). The role decides the path, never whether the folder is warmed.
        val neighbours = bodyOf("prefetchNeighbours")
        assertTrue("""val raw = mailboxDao.roleForId(credentials.id, mailboxId) != "inbox"""" in neighbours)
        assertTrue("bodyPrefetchPort(credentials, mailboxId, raw)" in neighbours)
        assertFalse("""!= "inbox") return@launch""" in neighbours)
        val port = bodyOf("bodyPrefetchPort")
        assertTrue(
            "a raw port stores raw, the Inbox port keeps the pinned path",
            "if (raw) fetchAndStoreRawBodies(credentials, ids) else fetchAndStoreBodies(credentials, ids)" in port,
        )
        assertTrue("the list prefetch keeps the default (Inbox) port", "bodyPrefetchPort(credentials, mailboxId), prefetchConditions" in bodyOf("prefetchFolderBodies"))
        val raw = bodyOf("fetchAndStoreRawBodies")
        assertFalse("the raw store never imports a key", "importAutocryptKey" in raw || "originalSendersOf(" in raw || "pgpEngine" in raw)
    }

    @Test fun `the open is timed phase by phase`() {
        val open = bodyOf("openMessage")
        for (phase in listOf("open-from-cache", "fetch-email-get", "inline-images(", "open-from-network")) {
            assertTrue("openMessage logs $phase", phase in open)
        }
    }
}

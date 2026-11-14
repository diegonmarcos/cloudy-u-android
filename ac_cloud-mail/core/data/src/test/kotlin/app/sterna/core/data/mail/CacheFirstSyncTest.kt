package app.sterna.core.data.mail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1752: cache-first, incremental, chunked sync. The three behaviours the owner asked for. */
class CacheFirstSyncTest {

    /** A server folder, newest first, answering anchor-chained chunks like Email/query. */
    private class Server(val ids: List<String>) {
        val requests = mutableListOf<Pair<String?, Int>>()
        var failOnRequest = Int.MAX_VALUE

        suspend fun page(anchor: String?, limit: Int): CatchUpPage<String>? {
            requests += anchor to limit
            if (requests.size >= failOnRequest) error("timeout")
            val start = if (anchor == null) 0 else ids.indexOf(anchor) + 1
            return CatchUpPage(ids.drop(start).take(limit), "q${ids.size}", "e${ids.size}")
        }
    }

    // -- cache survives a failed sync ----------------------------------------------------------------

    @Test fun `a failed chunk leaves every cached row and the chunks that landed`() {
        val server = Server((1..400).map { "n$it" })
        val cache = (300..400).map { "n$it" }.toMutableSet() // the day-before mail
        val before = cache.toSet()
        server.failOnRequest = 2 // chunk 2 times out
        val thrown = runCatching {
            runBlocking {
                catchUpNewestFirst<String>(
                    cachedIds = cache.toSet(), idOf = { it },
                    fetchPage = server::page,
                    writePage = { cache += it },
                )
            }
        }.exceptionOrNull()
        assertTrue("the failure reaches the caller", thrown != null)
        assertTrue("nothing the cache held was removed", cache.containsAll(before))
        assertEquals("chunk 1 stayed", before + server.ids.take(INITIAL_PAGE_LIMIT), cache)
    }

    @Test fun `a stale walk that finished does not reconcile and an honest one does`() {
        val cache = (1..100).map { "m$it" }.toMutableSet()
        var reconciled = false
        val keep = setOf("m90", "m91")
        runBlocking {
            fullQueryWriteThrough<List<String>, List<String>>(
                folderIds = { cache.toSet() },
                walk = { it(listOf("m90")); listOf("m90", "m91") },
                writePage = { cache += it },
                keepIds = { keep },
                spareIds = { emptyList() },
                reconcile = { _, _, _, _ -> reconciled = true },
                mayReconcile = { _, _ -> reconcileAnswerIsCurrent(cachedNewestBefore = 2_000L, walkNewest = 1_000L) },
            )
        }
        assertFalse("a walk older than the cache must never delete the newer mail", reconciled)
        runBlocking {
            fullQueryWriteThrough<List<String>, List<String>>(
                folderIds = { cache.toSet() },
                walk = { it(listOf("m90")); listOf("m90", "m91") },
                writePage = { cache += it },
                keepIds = { keep },
                spareIds = { emptyList() },
                reconcile = { _, _, _, _ -> reconciled = true },
                mayReconcile = { _, _ -> reconcileAnswerIsCurrent(cachedNewestBefore = 1_000L, walkNewest = 2_000L) },
            )
        }
        assertTrue(reconciled)
    }

    @Test fun `unknown dates never veto a reconcile`() {
        assertTrue(reconcileAnswerIsCurrent(null, 5L))
        assertTrue(reconcileAnswerIsCurrent(5L, null))
        assertTrue(reconcileAnswerIsCurrent(0L, 0L))
        assertTrue(reconcileAnswerIsCurrent(5L, 5L))
        assertFalse(reconcileAnswerIsCurrent(6L, 5L))
    }

    // -- folder switch does not refetch everything ---------------------------------------------------

    @Test fun `switching to a cached folder with three new mails asks one chunk and writes three rows`() {
        val server = Server((1..10_000).map { "n${10_001 - it}" }) // n10000 newest
        val cache = server.ids.drop(3).take(2_000).toMutableSet()
        val written = mutableListOf<String>()
        val result = runBlocking {
            catchUpNewestFirst<String>(cache.toSet(), { it }, server::page, { written += it; cache += it })
        }
        assertEquals(1, server.requests.size)
        assertEquals(INITIAL_PAGE_LIMIT, server.requests.single().second)
        assertEquals(server.ids.take(3), written)
        assertTrue(result.reachedCached)
        assertEquals("q10000", result.queryState)
    }

    @Test fun `no new mail asks one chunk and writes nothing`() {
        val server = Server((1..500).map { "n$it" })
        val written = mutableListOf<String>()
        val result = runBlocking {
            catchUpNewestFirst<String>(server.ids.toSet(), { it }, server::page, { written += it })
        }
        assertEquals(1, server.requests.size)
        assertTrue(written.isEmpty())
        assertTrue(result.reachedCached)
    }

    @Test fun `the repository tries the catch-up before the window walk and the inbox id list is throttled`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow").lines().map { it.trim() }
        val catchUp = body.indexOfFirst { it.startsWith("catchUpFolder(") }
        val full = body.indexOfFirst { it.startsWith("val full = fullQuerySizing(") }
        assertTrue("catchUpFolder is not called in syncMailboxWindow", catchUp >= 0)
        assertTrue("the catch-up must come before the window walk", catchUp < full)
        val sync = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailbox")
        assertTrue("the whole-inbox id walk must be claimed from the schedule", "inboxSnapshots.claim(" in sync)
    }

    @Test fun `the inbox id list is read once, then not again inside the interval, and again after a failure`() {
        var now = 1_000L
        val schedule = InboxSnapshotSchedule(minIntervalMs = 600_000L, clock = { now })
        assertTrue(schedule.claim("a", "inbox"))
        now += 60_000
        assertFalse(schedule.claim("a", "inbox"))
        assertTrue("another folder is its own entry", schedule.claim("a", "other"))
        schedule.release("a", "inbox")
        assertTrue(schedule.claim("a", "inbox"))
        now += 600_000
        assertTrue(schedule.claim("a", "inbox"))
    }

    // -- chunk paging order and limit ----------------------------------------------------------------

    @Test fun `chunks come newest first, 50 at a time, chained by the last row of the previous chunk`() {
        val server = Server((1..1_000).map { "n${1_001 - it}" }) // n1000 newest
        val cache = server.ids.drop(120).toSet() // 120 new mails
        val written = mutableListOf<String>()
        val result = runBlocking {
            catchUpNewestFirst<String>(cache, { it }, server::page, { written += it })
        }
        assertEquals(server.ids.take(120), written) // newest first, no gaps, no duplicates
        assertTrue(server.requests.all { it.second == INITIAL_PAGE_LIMIT })
        assertEquals(listOf<String?>(null, server.ids[49], server.ids[99]), server.requests.map { it.first })
        assertEquals(3, result.pages)
        assertTrue(result.reachedCached)
    }

    @Test fun `a long gap stops at the page cap and says it did not reach the cache`() {
        val server = Server((1..5_000).map { "n${5_001 - it}" })
        val cache = server.ids.drop(2_000).toSet()
        val written = mutableListOf<String>()
        val result = runBlocking {
            catchUpNewestFirst<String>(cache, { it }, server::page, { written += it })
        }
        assertEquals(CATCH_UP_MAX_PAGES, server.requests.size)
        assertEquals(CATCH_UP_MAX_PAGES * INITIAL_PAGE_LIMIT, written.size)
        assertEquals(server.ids.take(written.size), written)
        assertFalse(result.reachedCached)
    }

    @Test fun `an anchor that left the folder falls back instead of throwing`() {
        val result = runBlocking {
            catchUpNewestFirst<String>(emptySet(), { it }, { anchor, _ ->
                if (anchor == null) CatchUpPage((1..50).map { "x$it" }, "q", "e") else null
            }, { })
        }
        assertFalse(result.reachedCached)
        assertEquals(1, result.pages)
    }

    @Test fun `the first page limit is about 50 and equals the scroll chunk`() {
        assertEquals(50, INITIAL_PAGE_LIMIT)
        assertEquals(50, pagingConfig().pageSize)
    }
}

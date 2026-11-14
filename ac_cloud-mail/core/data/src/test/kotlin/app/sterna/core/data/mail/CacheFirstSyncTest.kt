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

    // -- a short, stale or partial answer never deletes (#1752 follow-up) ----------------------------------

    @Test fun `a short server answer leaves every cached row in place`() {
        val cache = (1..100).map { "m$it" }.toMutableSet()
        val before = cache.toSet()
        // The server answers with 2 rows only (degraded index): neither the catch-up nor the sweep may delete.
        val short = Server(listOf("m1", "m2"))
        runBlocking {
            catchUpNewestFirst<String>(cache.toSet(), { it }, short::page, { cache += it })
        }
        assertEquals(before, cache)
        // The full-walk path: a walk of 2 ids finishes, its reconcile is the server's notFound word only.
        val notFoundFromAShortAnswer: Set<String>? = emptySet()
        assertTrue(ghostEvictions(before.toList(), notFoundFromAShortAnswer).isEmpty())
        assertTrue("an unanswered question deletes nothing", ghostEvictions(before.toList(), null).isEmpty())
        assertEquals(listOf("m5"), ghostEvictions(before.toList(), setOf("m5", "not-cached")))
    }

    @Test fun `the JMAP sync has no delete by absence left in it`() {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "syncMailboxWindow")
        assertFalse("a reconcile by absence is back in syncMailboxWindow", "emailDao.reconcileMailbox(" in body)
        assertFalse("a bare delete is back in syncMailboxWindow", "emailDao.deleteByIds(" in body)
        assertTrue("the delta no longer writes through the transaction", "emailDao.applyDelta(" in body)
        val dao = DaoQuerySource.daoFunctionBody("EmailDao", "applyDelta")
        assertTrue(dao.contains("deleteRowsAndIndexByIds(") && dao.contains("upsertAll("))
        assertTrue("applyDelta must be one transaction", DaoQuerySource.isTransactional("EmailDao", "applyDelta"))
    }

    @Test fun `a cold folder fetches one chunk of 50 and stops, older mail waits for the scroll`() {
        val server = Server((1..10_000).map { "n${10_001 - it}" })
        val written = mutableListOf<String>()
        val result = runBlocking {
            catchUpNewestFirst<String>(emptySet(), { it }, server::page, { written += it }, maxPages = COLD_FIRST_SYNC_PAGES)
        }
        assertEquals(1, server.requests.size)
        assertEquals(INITIAL_PAGE_LIMIT, written.size)
        assertEquals(server.ids.take(INITIAL_PAGE_LIMIT), written)
        assertEquals("q10000", result.queryState)
        val body = DaoQuerySource.mailFunctionBody("MailRepository", "catchUpFolder")
        assertTrue("the cold branch no longer caps the catch-up", "maxPages = if (cold) COLD_FIRST_SYNC_PAGES else CATCH_UP_MAX_PAGES" in body)
    }
}

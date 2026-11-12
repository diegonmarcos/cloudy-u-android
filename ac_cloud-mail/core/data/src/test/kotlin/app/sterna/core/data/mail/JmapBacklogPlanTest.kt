package app.sterna.core.data.mail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1527: the JMAP Inbox converges on EVERY server message, paged, not a window. */
class JmapBacklogPlanTest {

    private val server = (1..3_000).map { "m$it" } // newest first

    @Test fun `cold cache fetches the newest bounded batch`() {
        val plan = JmapBacklogPlan.next(server, emptySet(), newCap = 100, backfill = 50)
        assertEquals(server.take(150), plan)
    }

    @Test fun `new mail above the newest cached comes first, then older newest first`() {
        val cached = server.subList(10, 200).toSet()
        val plan = JmapBacklogPlan.next(server, cached, newCap = 1000, backfill = 30)
        assertEquals(server.take(10) + server.subList(200, 230), plan)
    }

    @Test fun `repeated refreshes converge on the whole inbox beyond any window`() {
        val cached = server.take(200).toHashSet() // the old 200-message window
        var rounds = 0
        while (true) {
            val plan = JmapBacklogPlan.next(server, cached)
            if (plan.isEmpty()) break
            cached += plan; rounds++
            assertTrue("bounded per refresh", plan.size <= JMAP_NEW_CAP + JMAP_BACKFILL_BATCH)
        }
        assertEquals(server.toSet(), cached)
        assertTrue(rounds in 2..10)
    }

    @Test fun `holes inside the cached range are backfilled`() {
        val cached = server.take(100).toHashSet().apply { remove("m50") }
        assertEquals(listOf("m50") + server.subList(100, 109), JmapBacklogPlan.next(server, cached, backfill = 10))
    }

    @Test fun `walkAllIds pages by position until the total`() = runBlocking {
        val asked = mutableListOf<Pair<Int, Int>>()
        val ids = walkAllIds(pageSize = 500) { pos, lim ->
            asked += pos to lim
            server.drop(pos).take(minOf(lim, 256)) to server.size // server caps silently at 256
        }
        assertEquals(server, ids)
        assertEquals(0, asked.first().first)
        assertEquals(256, asked[1].first)
    }

    @Test fun `walkAllIds stops at an empty page and at the cap`() = runBlocking {
        assertEquals(10, walkAllIds(100) { pos, lim -> server.take(10).drop(pos).take(lim) to null }.size)
        assertEquals(700, walkAllIds(500, cap = 700) { pos, lim -> server.drop(pos).take(lim) to null }.size)
    }

    @Test(expected = IllegalStateException::class)
    fun `walkAllIds propagates a failed page so nothing reconciles against half a list`() {
        runBlocking { walkAllIds(100) { pos, _ -> if (pos > 0) error("boom") else server.take(100) to 3000 } }
    }
}

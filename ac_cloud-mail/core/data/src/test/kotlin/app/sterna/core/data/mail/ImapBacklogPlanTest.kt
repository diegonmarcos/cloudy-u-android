package app.sterna.core.data.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The IMAP sync resumes from the last UID it holds and backfills older mail, a bounded batch at a time. */
class ImapBacklogPlanTest {
    private val server = (1L..1_000L).toList()

    @Test fun `a folder with nothing missing plans nothing`() {
        assertEquals(emptyList<Long>(), ImapBacklogPlan.next(server, server.toSet()))
    }

    @Test fun `new mail above the last cached UID comes first, then the newest older gap`() {
        val cached = (301L..1_000L).toSet() // the newest window
        val server = (1L..1_020L).toList() // 20 arrived since
        val plan = ImapBacklogPlan.next(server, cached, newCap = 1_000, backfill = 50)
        assertEquals((1_020L downTo 1_001L).toList(), plan.take(20))
        assertEquals((300L downTo 251L).toList(), plan.drop(20))
    }

    @Test fun `paging reaches beyond the first window, whatever its size`() {
        val firstWindow = (801L..1_000L).toSet() // the newest 200, all a window-only sync ever held
        val plan = ImapBacklogPlan.next(server, firstWindow, backfill = 400)
        assertEquals(400, plan.size)
        assertTrue(plan.all { it < 801 })
        assertEquals(800L, plan.first())
    }

    @Test fun `repeated syncs converge on the whole folder with every message exactly once`() {
        var cached = (801L..1_000L).toMutableSet()
        var rounds = 0
        while (true) {
            val plan = ImapBacklogPlan.next(server, cached)
            if (plan.isEmpty()) break
            assertTrue("a plan repeats a UID it already holds", plan.none { it in cached })
            assertEquals("a plan repeats itself", plan.size, plan.toSet().size)
            cached.addAll(plan)
            rounds++
            assertTrue("does not converge", rounds < 20)
        }
        assertEquals(server.toSet(), cached)
        assertEquals(2, rounds) // 800 older at 400 a sync
    }

    @Test fun `a gap below the highest cached UID is backfilled, and a first sync resumes from nothing`() {
        val cached = (1L..1_000L).filter { it != 500L }.toSet()
        assertEquals(listOf(500L), ImapBacklogPlan.next(server, cached))
        val fresh = ImapBacklogPlan.next(server, emptySet(), newCap = 300)
        assertEquals((1_000L downTo 701L).toList(), fresh)
    }

    @Test fun `UIDs that left the server are never planned`() {
        val plan = ImapBacklogPlan.next(listOf(5L, 4L, 3L), setOf(5L, 9L))
        assertEquals(listOf(4L, 3L), plan)
    }
}

package app.sterna.core.data.mail

import app.sterna.core.data.filter.SourceText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1869 step 8: neighbours in the pager's order as (account, id) pairs, and the VISIBLE trigger's throttle. */
class PagerOrderPrefetch1869Test {
    private val wifi = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)
    private val metered = PrefetchConditions(connected = true, unmetered = false, batterySaver = false)
    private val meteredSaver = PrefetchConditions(connected = true, unmetered = false, batterySaver = true)
    private val wifiSaver = PrefetchConditions(connected = true, unmetered = true, batterySaver = true)
    private val offline = PrefetchConditions(connected = false, unmetered = false, batterySaver = false)

    // A unified, grouped list: neighbours come from two accounts and three folders, so no folder order can produce them.
    private val pages = listOf("a" to "1", "b" to "9", "a" to "2", "a" to "7", "b" to "3", "a" to "4")

    @Test fun `two on each side, nearest first, after before alternating, as account id pairs`() {
        assertEquals(listOf("a" to "7", "b" to "9", "b" to "3", "a" to "1"), pagerNeighbours(2, pages.size, 2) { pages.getOrNull(it) })
        assertEquals(pagerNeighbours(2, pages.size, 2) { pages.getOrNull(it) }, pagerPrefetchOrder(2, pages.size) { pages.getOrNull(it) })
    }

    @Test fun `the pager's ends and pages not loaded yet are skipped`() {
        assertEquals(listOf("b" to "9", "a" to "2"), pagerNeighbours(0, pages.size, 2) { pages.getOrNull(it) })
        assertEquals(listOf("a" to "4", "a" to "7"), pagerNeighbours(4, pages.size, 2) { pages.getOrNull(it) })
        assertEquals(listOf("a" to "2"), pagerNeighbours(1, pages.size, 2) { i -> pages.getOrNull(i)?.takeIf { i == 2 } })
        assertTrue(pagerNeighbours(0, 1, 2) { pages.getOrNull(it) }.isEmpty())
    }

    @Test fun `VISIBLE is allowed on a metered link, bounded to 12, one call`() {
        val m = prefetchBudget(metered, PrefetchTrigger.VISIBLE)
        assertTrue(m.allowed)
        assertEquals(PrefetchBudget(PREFETCH_VISIBLE_MAX, PREFETCH_VISIBLE_MAX, 1), m)
        assertEquals(12, PREFETCH_VISIBLE_MAX)
        assertEquals(m, prefetchBudget(wifi, PrefetchTrigger.VISIBLE))
        assertTrue("battery saver on Wi-Fi keeps what is on screen", prefetchBudget(wifiSaver, PrefetchTrigger.VISIBLE).allowed)
        assertFalse("battery saver on a metered link stops it", prefetchBudget(meteredSaver, PrefetchTrigger.VISIBLE).allowed)
        assertFalse(prefetchBudget(offline, PrefetchTrigger.VISIBLE).allowed)
        assertEquals(150L, PREFETCH_VISIBLE_DEBOUNCE_MS)
    }

    @Test fun `a VISIBLE pass fetches only what is missing, in screen order, at most 12`() = runBlocking {
        val asked = mutableListOf<List<String>>()
        val port = object : BodyPrefetchPort {
            override suspend fun missingNewest(limit: Int) = error("a visible pass never reads the folder")
            override suspend fun missingAmong(ids: List<String>) = ids.filter { it != "s3" }
            override suspend fun fetchAndStore(ids: List<String>) { asked += ids }
        }
        val rows = (1..20).map { "s$it" }
        val result = BodyPrefetcher(port, { metered }).run(PrefetchTrigger.VISIBLE, rows)
        assertEquals(12, result.requested)
        assertEquals(listOf(rows.filter { it != "s3" }.take(12)), asked)
    }

    @Test fun `the pager hands its order over, the folder order stays the lone-message fallback, no body is fetched twice at once`() {
        val repo = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val nb = repo.substringAfter("fun prefetchNeighbours(credentials: AccountCredentials, emailId: String) {").substringBefore("bgScope.launch {")
        assertTrue(nb.contains("if (pagerAnchors.containsKey(\"\${credentials.id}/\$emailId\") || pagerAnchors.containsKey(\"/\$emailId\")) return"))
        val pm = repo.substringAfter("private fun prefetchMessages(").substringBefore("private fun bodyPrefetchPort(")
        assertTrue(pm.contains("val taken = ids.filter { prefetchInFlight.add(\"\$accountId/\$it\") }"))
        assertTrue(pm.contains("taken.forEach { prefetchInFlight.remove(\"\$accountId/\$it\") }"))
        assertTrue("outside the Inbox nothing plants a key", pm.contains("if (mailboxDao.roleForId(accountId, mailboxId) == \"inbox\") inbox += id else raw += id"))
        assertTrue(pm.contains("bodyPrefetchPort(credentials, \"\", isRaw)"))
    }
}

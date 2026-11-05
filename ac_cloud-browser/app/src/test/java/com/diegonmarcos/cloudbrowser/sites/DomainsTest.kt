package com.diegonmarcos.cloudbrowser.sites

import com.diegonmarcos.superapp.browser.BrowserVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** #1493 Domains: the visit log, History per day, Visited and the per-site actions. */
class DomainsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val utc: ZoneId = ZoneOffset.UTC
    private val visits = listOf(
        BrowserVisit("https://www.a.org/1", "A1", at(2026, 11, 4, 9)),
        BrowserVisit("https://a.org/2", "A2", at(2026, 11, 4, 18)),
        BrowserVisit("https://b.net/x", "B", at(2026, 11, 4, 12)),
        BrowserVisit("https://b.net/y", "B2", at(2026, 11, 3, 23)),
        BrowserVisit("about:blank", "blank", at(2026, 11, 3, 1)),
    )

    @Test
    fun `history groups pages by day, newest day and newest page first`() {
        val d = HistoryDays.pagesByDay(visits, utc)
        assertEquals(listOf("2026-11-04", "2026-11-03"), d.map { it.day })
        assertEquals(listOf("A2", "B", "A1"), d[0].pages.map { it.title })
        assertEquals(listOf("B2", "blank"), d[1].pages.map { it.title })
    }

    @Test
    fun `the day depends on the zone`() {
        val late = listOf(BrowserVisit("https://a.org", "t", at(2026, 11, 3, 23)))
        assertEquals("2026-11-03", HistoryDays.pagesByDay(late, utc)[0].day)
        assertEquals("2026-11-04", HistoryDays.pagesByDay(late, ZoneId.of("Asia/Tokyo"))[0].day)
    }

    @Test
    fun `the domains toggle groups the same visits by domain per day, www folded, most pages first`() {
        val d = HistoryDays.domainsByDay(visits, utc)
        assertEquals(listOf("2026-11-04", "2026-11-03"), d.map { it.day })
        assertEquals(listOf("a.org" to 2, "b.net" to 1), d[0].domains.map { it.domain to it.pages })
        assertEquals(listOf("b.net" to 1), d[1].domains.map { it.domain to it.pages })   // about:blank has no domain
    }

    @Test
    fun `visited lists every domain once and sorts by name or by last visit`() {
        val s = Visited.sites(visits)
        assertEquals(setOf("a.org", "b.net"), s.map { it.domain }.toSet())
        assertEquals(listOf("a.org", "b.net"), Visited.sort(s, Visited.BY_NAME).map { it.domain })
        assertEquals(listOf("a.org", "b.net"), Visited.sort(s, Visited.BY_LAST).map { it.domain })
        val flipped = s.map { if (it.domain == "b.net") it.copy(last = at(2026, 12, 1, 0)) else it }
        assertEquals(listOf("b.net", "a.org"), Visited.sort(flipped, Visited.BY_LAST).map { it.domain })
        assertEquals(listOf("a.org", "b.net"), Visited.sort(flipped, Visited.BY_NAME).map { it.domain })
        assertEquals(listOf("Alpha.io", "beta.io"), Visited.sort(listOf(Visited.Site("beta.io", 1, 1, 1), Visited.Site("Alpha.io", 2, 2, 1)), "anything").map { it.domain })
        val a = s.first { it.domain == "a.org" }
        assertEquals(at(2026, 11, 4, 9), a.first); assertEquals(at(2026, 11, 4, 18), a.last); assertEquals(2, a.pages)
    }

    @Test
    fun `every site ever seen is the visited ones and the ones with a permission rule`() {
        assertEquals(listOf("a.org", "b.net", "c.com"), Visited.everSeen(Visited.sites(visits), listOf("www.c.com", "*", "a.org", "")))
    }

    @Test
    fun `the visit log keeps rows past the history cap and does not repeat one`() {
        val log = VisitLog.merge(emptyList(), visits)
        assertEquals(visits.size, log.size)
        assertEquals(log, VisitLog.merge(log, visits))
        val more = VisitLog.merge(log, listOf(BrowserVisit("https://new.io", "n", at(2026, 11, 5, 1))))
        assertEquals("https://new.io", more.first().url)
        assertEquals(2, VisitLog.merge(log, emptyList(), cap = 2).size)
        assertEquals(more, VisitLog.fromJson(VisitLog.toJson(more)))
    }

    private val files = listOf(
        CachedFile("a.org", "/c/1", "image/png"), CachedFile("a.org", "/c/2", "text/html"), CachedFile("a.org", "/c/3", "video/mp4"), CachedFile("b.net", "/c/4", "image/png"),
    )
    private val media = listOf("image/", "audio/", "video/")

    private class Fake : SiteExec {
        val log = ArrayList<String>()
        override fun deleteFiles(paths: List<String>) { log += "del:" + paths.joinToString(",") }
        override fun cookies(host: String) { log += "cookies:$host" }
        override fun storage(host: String) { log += "storage:$host" }
        override fun resetPerms(host: String) { log += "reset:$host" }
    }

    private fun run(id: String, host: String): List<String> {
        val f = Fake()
        SiteActions.run(SiteActions.plan(id, host, files, media)!!, f)
        return f.log
    }

    @Test
    fun `each site action touches only that site`() {
        assertEquals(listOf("cookies:a.org"), run(SiteActions.COOKIES, "a.org"))
        assertEquals(listOf("storage:a.org"), run(SiteActions.STORAGE, "a.org"))
        assertEquals(listOf("reset:b.net"), run(SiteActions.RESET_PERMS, "b.net"))
        assertEquals(listOf("del:/c/1,/c/2,/c/3"), run(SiteActions.CACHE, "a.org"))
        assertEquals(listOf("del:/c/1,/c/3"), run(SiteActions.MEDIA, "a.org"))
        assertEquals(listOf("del:/c/4"), run(SiteActions.CACHE, "b.net"))
        assertEquals(listOf("del:"), run(SiteActions.CACHE, "c.com"))
        assertNull(SiteActions.plan("nope", "a.org", files, media))
    }

    @Test
    fun `the cache parser names a site from its storage folder`() {
        assertEquals("example.org", SiteCache.hostOfOriginDir("https_example.org_0.indexeddb.leveldb"))
        assertEquals("a.b.io", SiteCache.hostOfOriginDir("https_www.a.b.io_0.indexeddb.blob"))
        assertNull(SiteCache.hostOfOriginDir("Local Storage"))
        assertEquals("a.org", SiteCache.hostOf("https://www.a.org/x/y.css"))
        assertEquals("x/y", SiteCache.dirOf("https://a.org/x/y/z.css"))
        assertEquals("index.html", SiteCache.nameOf("https://a.org/"))
        assertTrue(SiteCache.hosts(listOf(SiteFile(SiteCache.APP_HOST, "f", "p", 1, 1), SiteFile("z.org", "f", "p", 1, 1), SiteFile("a.org", "f", "p", 1, 1))).let { it == listOf("a.org", "z.org", SiteCache.APP_HOST) })
    }
}

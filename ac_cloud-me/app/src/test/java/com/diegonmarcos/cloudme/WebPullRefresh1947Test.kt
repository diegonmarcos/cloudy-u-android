package com.diegonmarcos.cloudme

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1947 pull-down refresh on every embedded page, and network_first: the live page wins online, the cache is the offline fallback. Pure JVM. */
class WebPullRefresh1947Test {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the refresh is armed for cached and non-cached pages`() {
        assertTrue(WebPullRefresh.armed(cached = true))
        assertTrue(WebPullRefresh.armed(cached = false))
    }

    @Test fun `a cached page runs the hard refresh, empties the cache and reloads, in that order`() {
        val log = ArrayList<String>()
        runBlocking { WebPullRefresh.run(true, { log += "hard" }, { log += "clear" }, { log += "reload" }) }
        assertEquals(listOf("hard", "clear", "reload"), log)
    }

    @Test fun `a non-cached page empties the cache and reloads`() {
        val log = ArrayList<String>()
        runBlocking { WebPullRefresh.run(false, { log += "hard" }, { log += "clear" }, { log += "reload" }) }
        assertEquals(listOf("clear", "reload"), log)
    }

    @Test fun `a page scrolled down, or scrolling inside an inner element, never lets the pull claim the drag`() {
        assertFalse(WebPullRefresh.canChildScrollUp(0, false, false))
        assertTrue(WebPullRefresh.canChildScrollUp(40, false, false))
        assertTrue(WebPullRefresh.canChildScrollUp(0, true, false))
        assertTrue(WebPullRefresh.canChildScrollUp(0, false, true))
    }

    // ---- network_first resolver
    private val page = "https://p.example/"
    private var now = 1_000_000_000_000L
    private val cfg = LnktreeCacheConfig(
        enabled = true, section = "pers", ttlMs = 7 * LnktreeCacheConfig.DAY_MS, prefetchEnabled = true, prefetchConcurrency = 1,
        prefetchNetwork = "unmetered", prefetchMaxPages = 3, maxSubresourcesPerPage = 5, maxBytes = 1_000_000,
        holderMaxWebViews = 8, hardRefreshScope = "page", excludePages = emptySet(), strategy = LnktreeCacheConfig.NETWORK_FIRST,
    )
    private fun store() = LnktreeCacheStore(tmp.newFolder("c"), { now })
    private fun live(body: String) = Fetched("text/html", "utf-8", 200, body.toByteArray())

    @Test fun `online the network wins even when a fresh cache entry exists, and the entry is refreshed`() {
        val s = store(); s.put("pers", page, "text/html", "utf-8", 200, "old".toByteArray())
        val r = LnktreeResolver.serve("pers", page, cfg, s, { live("live") }, { error("no background work") }, page)!!
        assertEquals("live", r.body.readText()); assertEquals("network", r.source)
        assertEquals("live", s.get("pers", page)!!.body.readText())
    }

    @Test fun `same-origin css and js are network first too`() {
        val s = store(); s.put("pers", "https://p.example/a.css", "text/css", null, 200, "old".toByteArray())
        val r = LnktreeResolver.serve("pers", "https://p.example/a.css", cfg, s, { Fetched("text/css", null, 200, "new".toByteArray()) }, {}, page)!!
        assertEquals("new", r.body.readText())
    }

    @Test fun `offline the cached copy is served and says so`() {
        val s = store(); s.put("pers", page, "text/html", "utf-8", 200, "old".toByteArray())
        val r = LnktreeResolver.serve("pers", page, cfg, s, { null }, {}, page)!!
        assertEquals("old", r.body.readText()); assertEquals("offline-cache", r.source); assertEquals(now, r.fetchedAt)
    }

    @Test fun `a 5xx or timeout is a failed fetch and falls back to the cache`() {
        val s = store(); s.put("pers", page, "text/html", "utf-8", 200, "old".toByteArray())
        // LnktreeCache.fetch answers null for every status but 200 and 404
        assertEquals("old", LnktreeResolver.serve("pers", page, cfg, s, { null }, {}, page)!!.body.readText())
    }

    @Test fun `a failed fetch never serves an empty body, online or from the cache`() {
        val s = store()
        assertEquals(null, LnktreeResolver.serve("pers", page, cfg, s, { null }, {}, page))
        // an empty 200 is not a page: the stored copy is the fallback
        s.put("pers", page, "text/html", "utf-8", 200, "old".toByteArray())
        assertEquals("old", LnktreeResolver.serve("pers", page, cfg, s, { live("") }, {}, page)!!.body.readText())
        // and an empty stored copy is not served as a fallback either
        val s2 = LnktreeCacheStore(tmp.newFolder("c2"), { now }); s2.put("pers", page, "text/html", "utf-8", 200, ByteArray(0))
        assertEquals(null, LnktreeResolver.serve("pers", page, cfg, s2, { null }, {}, page))
        assertFalse(s.get("pers", page)!!.body.length() == 0L)
    }


    @Test fun `trim evicts images before a page's html, css and js, so an opened page keeps its offline copy`() {
        val s = store()
        s.put("pers", page, "text/html", "utf-8", 200, ByteArray(100))
        s.put("pers", "https://p.example/a.css", "text/css", null, 200, ByteArray(100))
        s.put("pers", "https://p.example/a.js", "application/javascript", null, 200, ByteArray(100))
        // the page's entries are the OLDEST; a section's worth of newer images follows
        s.root.walkTopDown().filter { it.name.endsWith(".meta") }.forEach { it.setLastModified(1_000_000L) }
        for (i in 1..5) {
            s.put("other$i", "https://o.example/$i.png", "image/png", null, 200, ByteArray(1000))
            s.metaFile("other$i", "https://o.example/$i.png").setLastModified(2_000_000L + i)
        }
        s.trim(1_000)
        assertTrue(s.get("pers", page)!!.body.length() == 100L)
        assertTrue(s.get("pers", "https://p.example/a.css")!!.body.length() == 100L)
        assertTrue(s.get("pers", "https://p.example/a.js")!!.body.length() == 100L)
    }

    @Test fun `trim spares the pages opened this session, even when they are the oldest`() {
        val s = store()
        s.put("opened", page, "text/html", "utf-8", 200, ByteArray(100)); s.metaFile("opened", page).setLastModified(1_000_000L)
        for (i in 1..4) {
            s.put("sib$i", "https://o.example/$i/", "text/html", "utf-8", 200, ByteArray(1000))
            s.metaFile("sib$i", "https://o.example/$i/").setLastModified(2_000_000L + i)
        }
        s.keep.add(CacheKey.dirName("opened"))
        s.trim(1_000)
        assertTrue(s.get("opened", page)!!.body.length() == 100L)
    }
}

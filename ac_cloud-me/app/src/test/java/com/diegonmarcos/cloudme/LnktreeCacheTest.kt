package com.diegonmarcos.cloudme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * #1323 Lnktree page cache: the TTL decision, the key and path mapping, the hard-refresh scope, the prefetch planner and
 * runner, the stale-while-revalidate resolver and the WebView holder's LRU. Pure JVM: LnktreeCache.kt touches no Android.
 */
class LnktreeCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day = LnktreeCacheConfig.DAY_MS
    private val cfg = LnktreeCacheConfig(
        enabled = true, section = "lnktree", ttlMs = 7 * day, prefetchEnabled = true, prefetchConcurrency = 2,
        prefetchNetwork = "unmetered", prefetchMaxPages = 3, maxSubresourcesPerPage = 5, maxBytes = 1_000_000,
        holderMaxWebViews = 8, hardRefreshScope = "page", excludePages = setOf("astro"),
    )
    private var now = 1_000_000_000_000L
    private fun store() = LnktreeCacheStore(tmp.newFolder("cache"), { now })
    private fun html(vararg refs: String) = Fetched("text/html", "utf-8", 200, refs.joinToString("") { it }.toByteArray())
    private fun put(s: LnktreeCacheStore, page: String, url: String, body: String = "x") =
        s.put(page, url, "text/html", "utf-8", 200, body.toByteArray())

    // ---- TTL decision

    @Test fun `no entry is missing`() = assertEquals(CacheState.MISSING, CacheTtl.decide(null, now, 7 * day))

    @Test fun `an entry up to 7 days old is fresh, older is stale`() {
        assertEquals(CacheState.FRESH, CacheTtl.decide(now, now, 7 * day))
        assertEquals(CacheState.FRESH, CacheTtl.decide(now - 7 * day, now, 7 * day))
        assertEquals(CacheState.STALE, CacheTtl.decide(now - 7 * day - 1, now, 7 * day))
        assertEquals(CacheState.STALE, CacheTtl.decide(now - 30 * day, now, 7 * day))
    }

    @Test fun `an entry stamped in the future counts as stale`() = assertEquals(CacheState.STALE, CacheTtl.decide(now + 1, now, 7 * day))

    @Test fun `the store ages entries with its clock`() {
        val s = store()
        assertEquals(CacheState.MISSING, s.state("leafy", "https://a.example/", cfg.ttlMs))
        put(s, "leafy", "https://a.example/")
        assertEquals(CacheState.FRESH, s.state("leafy", "https://a.example/", cfg.ttlMs))
        now += 6 * day
        assertEquals(CacheState.FRESH, s.state("leafy", "https://a.example/", cfg.ttlMs))
        now += 2 * day
        assertEquals(CacheState.STALE, s.state("leafy", "https://a.example/", cfg.ttlMs))
    }

    // ---- key and path mapping

    @Test fun `urls that name the same resource share a key`() {
        val k = CacheKey.of("https://Site.example/a/b.css?v=1")
        assertEquals(k, CacheKey.of("https://site.example:443/a/b.css?v=1#frag"))
        assertEquals(CacheKey.of("https://site.example/"), CacheKey.of("https://site.example"))
    }

    @Test fun `urls that differ in path or query have different keys`() {
        val keys = listOf("https://s.example/a.css", "https://s.example/b.css", "https://s.example/a.css?v=2", "https://o.example/a.css", "https://s.example:8443/a.css")
            .map(CacheKey::of)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.length == 32 && it.all { c -> c in "0123456789abcdef" } })
    }

    @Test fun `files live under the page's own directory`() {
        val s = store()
        val body = s.bodyFile("nexus", "https://n.example/x.js")
        assertEquals(s.pageDir("nexus"), body.parentFile)
        assertEquals(CacheKey.of("https://n.example/x.js") + ".body", body.name)
        assertEquals(s.root, s.pageDir("nexus").parentFile)
        assertEquals("a_b_c", CacheKey.dirName("a/b.c"))
        assertEquals(CacheKey.dirName("lnktree-site"), "lnktree-site")
    }

    @Test fun `a stored entry reads back`() {
        val s = store()
        s.put("leafy", "https://a.example/s.css", "text/css", "utf-8", 200, "body{}".toByteArray())
        val e = s.get("leafy", "https://a.example/s.css")!!
        assertEquals("text/css", e.mime); assertEquals("utf-8", e.encoding); assertEquals(now, e.fetchedAt)
        assertEquals("body{}", e.body.readText())
        assertNull(s.get("nexus", "https://a.example/s.css"))
    }

    // ---- hard refresh scope

    private fun twoPages(s: LnktreeCacheStore) {
        put(s, "leafy", "https://l.example/"); put(s, "leafy", "https://l.example/a.css")
        put(s, "nexus", "https://n.example/"); put(s, "nexus", "https://n.example/a.css")
    }

    @Test fun `hard refresh of a page deletes only that page's files`() {
        val s = store(); twoPages(s)
        val removed = LnktreeResolver.hardRefresh("leafy", cfg, s)
        assertEquals(4, removed)   // two bodies, two metas
        assertNull(s.get("leafy", "https://l.example/")); assertNull(s.get("leafy", "https://l.example/a.css"))
        assertNotNull(s.get("nexus", "https://n.example/")); assertNotNull(s.get("nexus", "https://n.example/a.css"))
    }

    @Test fun `hard refresh with scope all deletes every Lnktree cache file`() {
        val s = store(); twoPages(s)
        LnktreeResolver.hardRefresh("leafy", cfg.copy(hardRefreshScope = "all"), s)
        assertNull(s.get("nexus", "https://n.example/")); assertNull(s.get("leafy", "https://l.example/"))
    }

    @Test fun `hard refresh of an uncached page removes nothing`() {
        val s = store(); twoPages(s)
        assertEquals(0, LnktreeResolver.hardRefresh("stark", cfg, s))
        assertNotNull(s.get("leafy", "https://l.example/"))
    }

    @Test fun `trim keeps the cache under the cap, oldest first`() {
        val s = store()
        put(s, "p", "https://a.example/old", "x".repeat(400)); s.metaFile("p", "https://a.example/old").setLastModified(1000)
        put(s, "p", "https://a.example/new", "y".repeat(400)); s.metaFile("p", "https://a.example/new").setLastModified(9000)
        s.trim(700)
        assertNull(s.get("p", "https://a.example/old")); assertNotNull(s.get("p", "https://a.example/new"))
    }

    // ---- prefetch planner

    private val pages = listOf("leafy", "nexus", "stark", "astro", "lnktree-site").map { LnktreePage(it, "https://$it.example/") }

    @Test fun `the planner takes missing and stale pages and leaves fresh ones`() {
        val s = store()
        put(s, "nexus", "https://nexus.example/"); now += 8 * day          // stale
        put(s, "leafy", "https://leafy.example/")                          // fresh (written after the jump)
        put(s, "lnktree-site", "https://lnktree-site.example/")            // fresh
        val plan = PrefetchPlanner.plan(pages, cfg, s)
        assertEquals(listOf("stark", "nexus"), plan.map { it.id })          // missing first, then stale; astro opted out
    }

    @Test fun `the planner is bounded by max_pages and respects the switch`() {
        val s = store()
        assertEquals(2, PrefetchPlanner.plan(pages, cfg.copy(prefetchMaxPages = 2), s).size)
        assertTrue(PrefetchPlanner.plan(pages, cfg.copy(prefetchEnabled = false), s).isEmpty())
        assertTrue(PrefetchPlanner.plan(pages, cfg.copy(enabled = false), s).isEmpty())
    }

    @Test fun `a fully cached set plans nothing`() {
        val s = store()
        pages.forEach { put(s, it.id, it.url) }
        assertTrue(PrefetchPlanner.plan(pages, cfg, s).isEmpty())
    }

    // ---- prefetch runner

    @Test fun `prefetch stores the html and its same-origin css, js and images, and what the css names`() {
        val s = store()
        val site = "https://leafy.example/"
        val net = mapOf(
            site to html("<link rel=\"stylesheet\" href=\"/s.css\"><script src=\"app.js\"></script><img src='logo.png'>",
                "<img src=\"https://cdn.other.example/x.png\">"),
            "https://leafy.example/s.css" to Fetched("text/css", null, 200, "a{background:url(bg.png)}".toByteArray()),
            "https://leafy.example/app.js" to Fetched("application/javascript", null, 200, "1".toByteArray()),
            "https://leafy.example/logo.png" to Fetched("image/png", null, 200, byteArrayOf(1)),
            "https://leafy.example/bg.png" to Fetched("image/png", null, 200, byteArrayOf(2)),
        )
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val failed = LnktreePrefetch.run(listOf(LnktreePage("leafy", site)), cfg, s) { asked += it; net[it] }
        assertTrue(failed.isEmpty())
        for (u in net.keys) assertNotNull("cached $u", s.get("leafy", u))
        assertFalse("cross-origin is not downloaded", asked.any { "other.example" in it })
    }

    @Test fun `prefetch fetches at most max_subresources_per_page files per page`() {
        val s = store()
        val refs = (1..20).joinToString("") { "<script src=\"/j$it.js\"></script>" }
        LnktreePrefetch.run(listOf(LnktreePage("leafy", "https://leafy.example/")), cfg, s) {
            if (it == "https://leafy.example/") html(refs) else Fetched("application/javascript", null, 200, "1".toByteArray())
        }
        assertEquals(1 + cfg.maxSubresourcesPerPage, s.pageDir("leafy").listFiles()!!.count { it.name.endsWith(".body") })
    }

    @Test fun `prefetch runs at most prefetchConcurrency pages at once and reports the pages it could not fetch`() {
        val s = store()
        val running = AtomicInteger(); val peak = AtomicInteger()
        val failed = LnktreePrefetch.run(pages.filter { it.id != "astro" } + LnktreePage("down", "https://down.example/"), cfg, s) { u ->
            val n = running.incrementAndGet(); peak.accumulateAndGet(n, ::maxOf)
            Thread.sleep(40); running.decrementAndGet()
            if ("down" in u) null else html()
        }
        assertEquals(listOf("down"), failed)
        assertTrue("peak ${peak.get()}", peak.get() in 1..cfg.prefetchConcurrency)
        assertNotNull(s.get("stark", "https://stark.example/"))
    }

    @Test fun `a non-200 main page is not cached`() {
        val s = store()
        val failed = LnktreePrefetch.run(listOf(LnktreePage("leafy", "https://leafy.example/")), cfg, s) { Fetched("text/html", null, 404, ByteArray(0)) }
        assertEquals(listOf("leafy"), failed); assertNull(s.get("leafy", "https://leafy.example/"))
    }

    // ---- stale-while-revalidate resolver

    @Test fun `a fresh hit is served without the network`() {
        val s = store(); put(s, "leafy", "https://l.example/", "cached")
        val r = LnktreeResolver.serve("leafy", "https://l.example/", cfg, s, { error("no network for a fresh hit") }, { error("no refresh") })
        assertEquals("cached", r!!.body.readText())
    }

    @Test fun `a stale hit is served at once and refreshed in the background`() {
        val s = store(); put(s, "leafy", "https://l.example/", "old"); now += 8 * day
        val refreshed = ArrayList<String>()
        val r = LnktreeResolver.serve("leafy", "https://l.example/", cfg, s, { error("served from disk") }, { refreshed += it })
        assertEquals("old", r!!.body.readText()); assertEquals(listOf("https://l.example/"), refreshed)
    }

    @Test fun `a miss is fetched and stored, offline it falls through`() {
        val s = store()
        assertNull(LnktreeResolver.serve("leafy", "https://l.example/", cfg, s, { null }, {}))
        val r = LnktreeResolver.serve("leafy", "https://l.example/", cfg, s, { Fetched("text/html", "utf-8", 200, "new".toByteArray()) }, {})
        assertEquals("new", r!!.body.readText())
        assertEquals(CacheState.FRESH, s.state("leafy", "https://l.example/", cfg.ttlMs))
    }

    // ---- #1947 incomplete pages (HTML cached, CSS/JS not) are never served

    private val page = "https://p.example/"
    private val pageHtml = "<link rel=\"stylesheet\" href=\"style.css?v=1.1\"><script src=\"/app.js\"></script>"

    @Test fun `cached html without its css or js is incomplete and is not served`() {
        val s = store(); put(s, "pers", page, pageHtml)
        assertFalse(s.complete("pers", page))
        val refreshed = ArrayList<String>()
        assertNull(LnktreeResolver.serve("pers", page, cfg, s, { error("no blocking fetch") }, { refreshed += it }, page))
        assertEquals("the page is completed in the background", listOf(page), refreshed)
    }

    @Test fun `html is complete only once every stylesheet and script is stored under the key the page asks for`() {
        val s = store(); put(s, "pers", page, pageHtml)
        s.put("pers", "https://p.example/style.css", "text/css", null, 200, "a{}".toByteArray())   // another key than style.css?v=1.1
        assertFalse(s.complete("pers", page))
        s.put("pers", "https://p.example/style.css?v=1.1", "text/css", null, 200, "a{}".toByteArray())
        s.put("pers", "https://p.example/app.js", "application/javascript", null, 200, "1".toByteArray())
        assertTrue(s.complete("pers", page))
        assertNotNull(LnktreeResolver.serve("pers", page, cfg, s, { error("complete: served from disk") }, {}, page))
    }

    @Test fun `a sub-resource miss goes to the network at once and is filled in the background, never a blocking fetch`() {
        val s = store(); val refreshed = ArrayList<String>()
        assertNull(LnktreeResolver.serve("pers", "https://p.example/data.json", cfg, s, { error("blocking fetch") }, { refreshed += it }, page))
        assertEquals(listOf("https://p.example/data.json"), refreshed)
    }

    @Test fun `a prefetch whose css failed leaves the page planned again, and a 404 tombstone does not`() {
        val s = store()
        LnktreePrefetch.run(listOf(LnktreePage("pers", page)), cfg, s) { if (it == page) html(pageHtml) else null }
        assertEquals(listOf("pers"), PrefetchPlanner.plan(listOf(LnktreePage("pers", page)), cfg, s).map { it.id })
        LnktreePrefetch.run(listOf(LnktreePage("pers", page)), cfg, s) {
            if (it == page) html(pageHtml) else Fetched("text/plain", null, 404, ByteArray(0))
        }
        assertTrue(s.complete("pers", page))
        assertTrue(PrefetchPlanner.plan(listOf(LnktreePage("pers", page)), cfg, s).isEmpty())
    }

    // ---- reference scan

    @Test fun `RefScan keeps only same-origin css, js and images`() {
        val doc = """<link rel="stylesheet" href="a.css"><link rel="canonical" href="/c"><link rel="icon" href="/f.ico">
            <script src="https://other.example/off.js"></script><script src="/m.js?v=3"></script><img src="data:image/png;base64,AA">
            <img srcset="/s1.png 1x, /s2.png 2x"><a href="/page.html">x</a>"""
        assertEquals(listOf("https://x.example/a.css", "https://x.example/f.ico", "https://x.example/m.js?v=3", "https://x.example/s1.png", "https://x.example/s2.png"),
            RefScan.html(doc, "https://x.example/"))
    }

    // ---- WebView holder LRU

    @Test fun `the holder keeps at most max entries and evicts the least recently used`() {
        val evicted = ArrayList<String>()
        val h = LruHolder<String>(3) { k, _ -> evicted += k }
        h.getOrPut("a") { "A" }; h.getOrPut("b") { "B" }; h.getOrPut("c") { "C" }
        h.getOrPut("a") { error("kept, not recreated") }       // a is now the most recent
        h.getOrPut("d") { "D" }
        assertEquals(listOf("b"), evicted); assertEquals(3, h.size)
        assertEquals(setOf("a", "c", "d"), h.keys.toSet())
    }

    @Test fun `the holder never evicts the entry just created and destroys all on clear`() {
        val evicted = ArrayList<String>()
        val h = LruHolder<String>(1) { k, _ -> evicted += k }
        h.getOrPut("a") { "A" }; h.getOrPut("b") { "B" }
        assertEquals(listOf("a"), evicted); assertEquals(listOf("b"), h.keys)
        h.clear(); assertEquals(listOf("a", "b"), evicted); assertEquals(0, h.size)
    }
}

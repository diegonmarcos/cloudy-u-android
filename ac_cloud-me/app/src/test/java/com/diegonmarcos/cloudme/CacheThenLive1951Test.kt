package com.diegonmarcos.cloudme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1951 cache_then_live: a complete cached copy paints at once, the live page is compared in parallel and swapped in when it differs,
 * a page with no complete copy never waits on the app's own fetch, every page of every section is prefetched, completeness counts the
 * CSS/JS, and the blank probe judges only a settled page. Pure JVM.
 */
class CacheThenLive1951Test {
    @get:Rule val tmp = TemporaryFolder()
    private val page = "https://p.example/a/"
    private val css = "https://p.example/a/s.css"
    private val cfg = LnktreeCacheConfig(
        enabled = true, section = "pers", ttlMs = 7 * LnktreeCacheConfig.DAY_MS, prefetchEnabled = true, prefetchConcurrency = 2,
        prefetchNetwork = "connected", prefetchMaxPages = 0, maxSubresourcesPerPage = 0, maxBytes = 50_000_000,
        holderMaxWebViews = 8, hardRefreshScope = "page", excludePages = emptySet(), strategy = LnktreeCacheConfig.CACHE_THEN_LIVE,
    )
    private fun store() = LnktreeCacheStore(tmp.newFolder(), { 1_000_000_000_000L })
    private fun h(body: String) = Fetched("text/html", "utf-8", 200, body.toByteArray())
    private fun c(body: String) = Fetched("text/css", "utf-8", 200, body.toByteArray())
    private val pageHtml = """<html><link rel="stylesheet" href="s.css"><body>v1</body></html>"""
    private fun fill(s: LnktreeCacheStore, p: String = "p", html: String = pageHtml, cssBody: String = "a{}") {
        s.put(p, page, "text/html", "utf-8", 200, html.toByteArray()); s.put(p, css, "text/css", "utf-8", 200, cssBody.toByteArray())
    }

    @Test fun `a complete cached copy paints from the cache with no network wait, and the live check is started`() {
        val s = store(); fill(s)
        val checked = ArrayList<String>()
        val r = LnktreeResolver.serve("p", page, cfg, s, { error("the network must not be touched") }, { checked += it }, page)!!
        assertEquals("v1", r.body.readText().substringAfter("<body>").substringBefore("</body>")); assertEquals("cache-live", r.source)
        assertEquals(listOf(page), checked)   // the live compare of the main frame
        assertEquals("a{}", LnktreeResolver.serve("p", css, cfg, s, { error("no network") }, { checked += it }, page)!!.body.readText())
    }

    @Test fun `a page with no complete copy is never held on the network and is filled afterwards`() {
        val s = store(); val filled = ArrayList<String>()
        assertNull(LnktreeResolver.serve("p", page, cfg, s, { error("a blocking fetch") }, { filled += it }, page))
        assertEquals(listOf(page), filled)
        s.put("p", page, "text/html", "utf-8", 200, pageHtml.toByteArray())   // HTML without its css: incomplete
        assertNull(LnktreeResolver.serve("p", page, cfg, s, { error("a blocking fetch") }, { }, page))
    }

    @Test fun `the live swap detects a changed page or stylesheet by content and updates the cache`() {
        val s = store(); fill(s)
        val live = { u: String -> if (u == page) h(pageHtml.replace("v1", "v2")) else c("a{}") }
        assertEquals(1, LiveSwap.refresh(LnktreePage("p", page), cfg, s, live))
        assertTrue(s.get("p", page)!!.body.readText().contains("v2"))
        assertEquals(0, LiveSwap.refresh(LnktreePage("p", page), cfg, s, live))   // same content: no swap, no loop
        assertEquals(1, LiveSwap.refresh(LnktreePage("p", page), cfg, s) { u -> if (u == page) h(pageHtml.replace("v1", "v2")) else c("b{}") })
        assertEquals("b{}", s.get("p", css)!!.body.readText())
        assertEquals(0, LiveSwap.refresh(LnktreePage("p", page), cfg, s) { null })   // offline: the cached copy stays
        assertTrue(s.get("p", page)!!.body.readText().contains("v2"))
    }

    @Test fun `the prefetch plan covers every page, and a refresh run re-checks the stored ones`() {
        val s = store(); val pages = (1..30).map { LnktreePage("pg$it", "https://p.example/$it/") }
        assertEquals(30, PrefetchPlanner.plan(pages, cfg, s).size)   // max_pages 0 = all
        LnktreePrefetch.run(pages.take(10), cfg, s) { u -> h("<html>$u</html>") }
        assertEquals(20, PrefetchPlanner.plan(pages, cfg, s).size)
        assertEquals(30, PrefetchPlanner.plan(pages, cfg, s, refreshAll = true).size)
        assertEquals(5, PrefetchPlanner.plan(pages, cfg.copy(prefetchMaxPages = 5), s).size)   // a declared cap still applies
    }

    @Test fun `no subresource cap leaves no page incomplete, and the per-page report says so`() {
        val s = store(); val refs = (1..60).joinToString("") { """<link rel="stylesheet" href="c$it.css">""" } + (1..60).joinToString("") { """<img src="i$it.png">""" }
        val fetch = { u: String -> if (u == page) h("<html>$refs</html>") else if (u.endsWith(".css")) c("x{}") else Fetched("image/png", null, 200, byteArrayOf(1, 2, 3)) }
        LnktreePrefetch.run(listOf(LnktreePage("p", page)), cfg, s, fetch)
        val r = s.report("p", page)
        assertEquals(121, r.entries); assertTrue(r.complete); assertTrue(r.bytes > 0)
        assertTrue(r.line().startsWith("CACHE_PAGE page=p entries=121 bytes=") && r.line().endsWith("complete=yes"))
        val capped = store(); LnktreePrefetch.run(listOf(LnktreePage("p", page)), cfg.copy(maxSubresourcesPerPage = 5), capped, fetch)
        assertFalse(capped.report("p", page).complete); assertTrue(capped.report("p", page).line().endsWith("complete=no"))
    }

    @Test fun `a page is complete only when its css and js are stored too`() {
        val s = store(); s.put("p", page, "text/html", "utf-8", 200, pageHtml.toByteArray())
        assertFalse(s.complete("p", page)); assertFalse(s.report("p", page).complete)
        s.put("p", css, "text/css", "utf-8", 200, "a{}".toByteArray())
        assertTrue(s.complete("p", page))
    }

    @Test fun `the blank probe waits for a page that is still loading or swapping and never errors for it`() {
        assertEquals(WebLoadState.Probe.WAIT, WebLoadState.probeAction(40, false, 3_000))
        assertEquals(WebLoadState.Probe.WAIT, WebLoadState.probeAction(100, true, 3_000))
        assertEquals(WebLoadState.Probe.PROBE, WebLoadState.probeAction(100, false, 3_000))
        assertEquals(WebLoadState.Probe.GIVE_UP, WebLoadState.probeAction(40, false, WebLoadState.PROBE_GIVE_UP_MS))
        assertTrue(WebLoadState.PROBE_DELAY_MS >= 2000)
    }
}

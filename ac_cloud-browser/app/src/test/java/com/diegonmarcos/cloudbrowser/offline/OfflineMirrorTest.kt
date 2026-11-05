package com.diegonmarcos.cloudbrowser.offline

import com.diegonmarcos.cloudlib.web.CachedSites
import com.diegonmarcos.cloudlib.web.Fetched
import com.diegonmarcos.cloudlib.web.Fetcher
import com.diegonmarcos.cloudlib.web.MirrorCrawl
import com.diegonmarcos.cloudlib.web.MirrorGate
import com.diegonmarcos.cloudlib.web.MirrorLimits
import com.diegonmarcos.cloudlib.web.MirrorScope
import com.diegonmarcos.cloudlib.web.PinnedSites
import com.diegonmarcos.cloudlib.web.RefreshRatePolicy
import com.diegonmarcos.cloudlib.web.RefreshRateToggle
import com.diegonmarcos.cloudlib.web.SiteCache
import com.diegonmarcos.cloudlib.web.SiteMirrorEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** #1518 the offline copy's rules (pure, over a temp-dir SiteCache and a fake network): scope, gate, cached-sites ops, refresh. */
class OfflineMirrorTest {
    private lateinit var dir: File
    private lateinit var cache: SiteCache

    @Before fun setUp() { dir = File.createTempFile("mirror", "").apply { delete(); mkdirs() }; cache = SiteCache(dir) }
    @After fun tearDown() { dir.deleteRecursively() }

    private val html = "text/html; charset=utf-8"
    private fun page(vararg refs: String) = Fetched(200, html, mapOf("ETag" to "\"v1\""), ("<html>" + refs.joinToString("") { "<a href=\"$it\">x</a>" } + "</html>").toByteArray())
    private fun file(mime: String, body: String) = Fetched(200, mime, emptyMap(), body.toByteArray())

    /** A fake site: the requested urls are recorded, an unknown url fails. */
    private class Net(val site: Map<String, Fetched>) : Fetcher {
        val asked = ArrayList<String>()
        override fun fetch(url: String, etag: String?): Fetched? { asked += url; return site[url] }
    }

    // ── crawl scope: same origin only ──

    @Test fun `only the same origin is in scope`() {
        assertTrue(MirrorScope.sameOrigin("https://a.org/x", "https://a.org/y?q=1"))
        assertTrue("a www. prefix is the same site", MirrorScope.sameOrigin("https://a.org/", "https://www.a.org/p"))
        assertFalse("a subdomain is another site", MirrorScope.sameOrigin("https://a.org/", "https://cdn.a.org/p"))
        assertFalse("another scheme", MirrorScope.sameOrigin("https://a.org/", "http://a.org/p"))
        assertFalse("another port", MirrorScope.sameOrigin("https://a.org/", "https://a.org:8443/p"))
        assertFalse("another site", MirrorScope.sameOrigin("https://a.org/", "https://b.org/p"))
        assertNull(MirrorScope.resolve("https://a.org/", "mailto:x@a.org"))
        assertNull(MirrorScope.resolve("https://a.org/", "javascript:void(0)"))
        assertEquals("https://a.org/d/p.js", MirrorScope.resolve("https://a.org/d/i.html", "p.js#top"))
    }

    @Test fun `links keep the same-origin ones, in html and css`() {
        val body = "<a href=\"/a\"><img src=\"https://a.org/i.png\"><a href=\"https://evil.org/x\"><script src=\"//cdn.a.org/s.js\"></script><a href=\"/a\">" +
            "<style>b{background:url(/bg.webp)} @import \"/s.css\";</style>"
        assertEquals(listOf("https://a.org/a", "https://a.org/i.png", "https://a.org/bg.webp", "https://a.org/s.css"), MirrorScope.links("https://a.org/", body, html))
        assertEquals(listOf("https://a.org/f/x.woff2"), MirrorScope.links("https://a.org/c/s.css", "@font-face{src:url('../f/x.woff2'),url(https://o.org/y.woff2)}", "text/css"))
    }

    @Test fun `the crawl never fetches an off-origin address and stops at its limits`() {
        val net = Net(mapOf(
            "https://a.org/" to page("/p1", "https://b.org/other", "https://cdn.a.org/x.js", "/missing"),
            "https://a.org/p1" to page("/p2"),
            "https://a.org/p2" to page("/p3"),
            "https://a.org/p3" to page("/p4"),
        ))
        val r = SiteMirrorEngine.crawl(cache, "https://a.org/", MirrorLimits(maxFiles = 100, maxDepth = 2), net)
        assertTrue("nothing off origin was asked: ${net.asked}", net.asked.all { it.startsWith("https://a.org/") })
        assertEquals("/ , p1, p2 (depth 2); p3 is a page beyond the depth, /missing failed", 3, r.files)
        assertFalse(net.asked.contains("https://a.org/p3"))
        assertEquals(listOf("a.org"), cache.hosts())
        val capped = MirrorCrawl("https://a.org/", MirrorLimits(maxFiles = 2)).also { c -> c.next(); c.saved(10); c.offer(listOf("https://a.org/1", "https://a.org/2"), 1); c.next(); c.saved(10) }
        assertNull("the file limit ends the walk", capped.next())
        assertEquals("file limit reached", capped.stopReason)
    }

    @Test fun `assets are taken at any depth`() {
        val crawl = MirrorCrawl("https://a.org/", MirrorLimits(maxDepth = 1))
        assertEquals("a page beyond the depth is refused, a script is not", 1, crawl.offer(listOf("https://a.org/deep", "https://a.org/app.js"), 2))
    }

    // ── charging / Wi-Fi gate ──

    @Test fun `an unattended download needs the charger and Wi-Fi, a tap needs neither`() {
        val charging = MirrorGate.Env(charging = true, wifi = false)
        assertFalse(MirrorGate.allowed(MirrorGate.Env(false, false), false))
        assertFalse(MirrorGate.allowed(MirrorGate.Env(true, false), false))
        assertFalse(MirrorGate.allowed(MirrorGate.Env(false, true), false))
        assertTrue(MirrorGate.allowed(MirrorGate.Env(true, true), false))
        assertTrue("a tap runs on battery and mobile data", MirrorGate.allowed(MirrorGate.Env(false, false), true))
        assertEquals("waiting for Wi-Fi", MirrorGate.waiting(charging, false))
        assertEquals("waiting for the charger", MirrorGate.waiting(MirrorGate.Env(false, true), false))
        assertNull(MirrorGate.waiting(charging, true))
    }

    @Test fun `a retry never comes sooner than 30 seconds`() {
        assertEquals(30_000L, MirrorGate.RETRY_FLOOR_MS)
        assertEquals(30_000L, MirrorGate.retryDelay(0))
        assertEquals(60_000L, MirrorGate.retryDelay(1))
        assertTrue((0..12).all { MirrorGate.retryDelay(it) >= 30_000L })
        assertEquals("a shorter ask is lifted to the floor", 1_030_000L, MirrorGate.retryAt(1_000_000L, 5_000L))
        assertEquals("a longer ask stands", 1_090_000L, MirrorGate.retryAt(1_000_000L, 90_000L))
    }

    // ── pin list and the Cached sites list ──

    @Test fun `the declared pin is the default until the user changes the list`() {
        assertEquals(listOf("floor796.com"), PinnedSites.parse(null, listOf("floor796.com")))
        assertEquals(emptyList<String>(), PinnedSites.parse("", listOf("floor796.com")))
        val two = PinnedSites.pin(PinnedSites.parse(null, listOf("floor796.com")), "https://www.Example.org/page")
        assertEquals(listOf("floor796.com", "example.org"), two)
        assertEquals(two, PinnedSites.parse(PinnedSites.serialise(two), emptyList()))
        assertTrue(PinnedSites.isPinned(two, "www.floor796.com"))
        assertEquals(listOf("example.org"), PinnedSites.unpin(two, "FLOOR796.com"))
        assertEquals("example.org", PinnedSites.keyOf("https://www.example.org:8443/x?y"))
    }

    @Test fun `the cached sites list shows size, files and last refresh and supports unpin and delete`() {
        cache.put("a.org", "https://a.org/", 200, html, emptyMap(), "<html>1</html>".toByteArray(), now = 111L)
        cache.put("a.org", "https://a.org/s.js", 200, "text/javascript", emptyMap(), "x=1".toByteArray(), now = 222L)
        cache.put("b.org", "https://b.org/", 200, html, emptyMap(), "<html>b</html>".toByteArray(), now = 333L)
        val pinned = listOf("a.org", "c.org")
        val rows = CachedSites.rows(cache, pinned)
        assertEquals("pinned-but-empty sites are listed too, A to Z", listOf("a.org", "b.org", "c.org"), rows.map { it.host })
        val a = rows.first { it.host == "a.org" }
        assertEquals(2, a.files); assertEquals(14L + 3L, a.bytes); assertEquals(222L, a.lastRefresh); assertTrue(a.pinned)
        assertFalse(rows.first { it.host == "b.org" }.pinned)
        assertEquals(0, rows.first { it.host == "c.org" }.files)
        // Unpin: the copy stays, the pin goes
        val unpinned = CachedSites.unpin(pinned, "a.org")
        assertEquals(listOf("c.org"), unpinned)
        assertEquals(2, CachedSites.rows(cache, unpinned).first { it.host == "a.org" }.files)
        assertFalse(CachedSites.rows(cache, unpinned).first { it.host == "a.org" }.pinned)
        // Delete: the copy and the pin go
        val after = CachedSites.delete(cache, pinned, "a.org")
        assertEquals(listOf("c.org"), after)
        assertEquals(listOf("b.org", "c.org"), CachedSites.rows(cache, after).map { it.host })
        assertNull(cache.lookup("a.org", "https://a.org/"))
    }

    @Test fun `only Refresh refetches, a failed refresh keeps the old copy, a 304 keeps it too`() {
        cache.put("a.org", "https://a.org/", 200, html, mapOf("ETag" to "\"v1\""), "old".toByteArray())
        cache.put("a.org", "https://a.org/s.js", 200, "text/javascript", emptyMap(), "s=1".toByteArray())
        // serving never touches the network
        assertNotNull(cache.serve("a.org", "https://a.org/"))
        val down = Net(emptyMap())
        assertEquals(0, SiteMirrorEngine.refresh(cache, "a.org", down))
        assertEquals("both files were asked", 2, down.asked.size)
        assertEquals("old", String(cache.serve("a.org", "https://a.org/")!!.body))
        val etags = ArrayList<String?>()
        val changed = SiteMirrorEngine.refresh(cache, "a.org", Fetcher { url, etag ->
            etags += etag
            if (url.endsWith("/s.js")) file("text/javascript", "s=2") else Fetched(304, "", emptyMap(), ByteArray(0))
        })
        assertEquals(1, changed)
        assertTrue("the page's ETag made its request conditional", etags.contains("\"v1\""))
        assertEquals("old", String(cache.serve("a.org", "https://a.org/")!!.body))
        assertEquals("s=2", String(cache.serve("a.org", "https://a.org/s.js")!!.body))
    }

    // ── refresh-rate toggle ──

    @Test fun `the max refresh rate is wanted only for a fullscreen GPU canvas and never under Super Saving`() {
        assertTrue(RefreshRatePolicy.wantMax(fullscreen = true, gpuCanvas = true, superSaving = false))
        assertFalse(RefreshRatePolicy.wantMax(false, true, false))
        assertFalse(RefreshRatePolicy.wantMax(true, false, false))
        assertFalse(RefreshRatePolicy.wantMax(true, true, true))
    }

    @Test fun `the toggle turns the rate on once and back off once`() {
        val calls = ArrayList<Boolean>()
        val t = RefreshRateToggle { calls += it }
        t.update(false, false, false); assertEquals(emptyList<Boolean>(), calls)
        t.update(true, true, false); t.update(true, true, false)
        assertEquals("on, once", listOf(true), calls); assertTrue(t.on)
        t.update(false, true, false)
        assertEquals("off when fullscreen ends", listOf(true, false), calls); assertFalse(t.on)
        t.update(true, true, false); t.update(true, true, true)
        assertEquals("Super Saving turns it off at once", listOf(true, false, true, false), calls)
        t.release(); assertEquals("release when already off does nothing", 4, calls.size)
        t.update(true, true, false); t.release()
        assertEquals(listOf(true, false, true, false, true, false), calls)
    }
}

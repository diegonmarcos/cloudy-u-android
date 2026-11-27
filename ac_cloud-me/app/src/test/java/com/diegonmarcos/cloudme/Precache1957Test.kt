package com.diegonmarcos.cloudme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * #1957 every page complete on disk before any tap, under the owner's cache_ttl policy: cross-origin and script-named files are planned and
 * stored, a page is complete only when its whole manifest is stored, the site's service worker is answered from the app cache, the startup
 * job fills pages nobody opened, copies younger than ttl_days are left alone, older ones are refetched and swapped in atomically, opening a
 * fresh page touches no network, and pull-down always refetches. Pure JVM; every named test has a mutation leg in me-cache-1957-mutation.yml.
 */
class Precache1957Test {
    @get:Rule val tmp = TemporaryFolder()
    private val day = LnktreeCacheConfig.DAY_MS
    private var now = 1_000_000_000_000L
    private val cfg = LnktreeCacheConfig(
        enabled = true, section = "pers", ttlMs = 7 * day, prefetchEnabled = true, prefetchConcurrency = 2,
        prefetchNetwork = "connected", prefetchMaxPages = 0, maxSubresourcesPerPage = 0, maxBytes = 500_000_000,
        holderMaxWebViews = 8, hardRefreshScope = "page", excludePages = emptySet(), strategy = LnktreeCacheConfig.CACHE_TTL,
        crossOrigin = true, scriptUrls = true, subresourceConcurrency = 4, videoMaxFileBytes = 10, videoMaxTotalBytes = 15, cacheVersion = "1957",
    )
    private fun store() = LnktreeCacheStore(tmp.newFolder(), { now })

    private val site = "https://diegonmarcos.github.io/mySocials/"
    private val cdnImg = "https://cdn.jsdelivr.net/gh/diegonmarcos/cloudy_db_assets-cdn@main/b_Media/p/1.jpg"
    private val rawImg = "https://raw.githubusercontent.com/diegonmarcos/cloudy_db_assets-cdn/main/b_Media/p/2.webp"
    private val three = "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.min.js"
    private val dataJs = site + "data-ig2-diegonmarcos.json.js"
    private val pageHtml = """<html><link rel="stylesheet" href="style.css"><script src="data-ig2-diegonmarcos.json.js"></script>
        <script src="script.js"></script><img src="$cdnImg"></html>"""

    /** The live site: v is the content version (a changed site after the ttl). [down] answers null (the network failed) for those URLs. */
    private fun net(v: String = "v1", down: Set<String> = emptySet(), asked: MutableList<String>? = null): (String) -> Fetched? = { u ->
        asked?.add(u)
        when {
            u in down -> null
            u == site -> Fetched("text/html", "utf-8", 200, pageHtml.replace("</html>", "<!--$v--></html>").toByteArray())
            u == site + "style.css" -> Fetched("text/css", null, 200, "body{background:url(bg.png)}".toByteArray())
            u == site + "bg.png" -> Fetched("image/png", null, 200, byteArrayOf(1))
            u == dataJs -> Fetched("application/javascript", null, 200, """globalThis.PORTAL_DATA={"ig2":{"posts":[{"media":"${rawImg.replace("/", "\\/")}"}]}}""".toByteArray())
            u == site + "script.js" -> Fetched("application/javascript", null, 200, "var r=\"$three\";".toByteArray())
            u.startsWith("https://cdn.jsdelivr.net/") || u.startsWith("https://raw.githubusercontent.com/") -> Fetched(if (u.endsWith(".js")) "application/javascript" else "image/jpeg", null, 200, "$v$u".toByteArray())
            else -> Fetched("text/plain", null, 404, ByteArray(0))
        }
    }
    private val page = LnktreePage("personal", site)

    @Test fun `cross-origin subresources and the URLs a script or data file names are planned and stored`() {
        val s = store()
        assertNotNull(LnktreePrefetch.fill(page, cfg, s, net(), null))
        for (u in listOf(site, site + "style.css", site + "bg.png", dataJs, site + "script.js", cdnImg, rawImg, three)) assertNotNull("stored $u", s.get("personal", u))
        assertTrue(s.refs("personal").containsAll(listOf(cdnImg, rawImg, three)))
        // the runtime pass (an offscreen WebView) adds what no static scan can see
        val runtime = "https://covers.openlibrary.org/b/isbn/123-M.jpg"
        val s2 = store(); LnktreePrefetch.fill(page, cfg, s2, { u -> if (u == runtime) Fetched("image/jpeg", null, 200, byteArrayOf(9)) else net()(u) }, null, listOf(runtime))
        assertNotNull(s2.get("personal", runtime))
        assertEquals(listOf(cdnImg), RefScan.html("""<img src="$cdnImg"><img src="https://x.example/a.png">""", site, true).take(1))
    }

    @Test fun `a page counts as complete only when every referenced file is stored`() {
        val s = store()
        LnktreePrefetch.fill(page, cfg, s, net(down = setOf(rawImg)), null)
        assertTrue("html, css and js are there: it paints", s.paintable("personal", site))
        assertFalse("the data file's image is missing: not complete", s.complete("personal", site))
        assertEquals(listOf(rawImg), s.missing("personal", site))
        assertTrue(s.report("personal", site).line().endsWith("complete=no"))
        assertEquals(TtlPrefetch.Action.COMPLETE, TtlPrefetch.decide(page, cfg, s))
        val asked = Collections.synchronizedList(ArrayList<String>())
        assertEquals("complete", TtlPrefetch.page(page, cfg, s, net(asked = asked)).result)
        assertEquals("a fresh incomplete copy fetches only what it lacks", listOf(rawImg), asked.toList())
        assertTrue(s.complete("personal", site)); assertTrue(s.report("personal", site).line().endsWith("complete=yes"))
    }

    @Test fun `service-worker requests are served from the app cache`() {
        val s = store(); LnktreePrefetch.fill(page, cfg, s, net(), null)
        val r = SwPolicy.serve(cdnImg, "GET", cfg, s)!!
        assertEquals("sw-cache", r.source); assertEquals("v1$cdnImg", r.body.readText())
        assertNotNull(SwPolicy.serve(dataJs, "GET", cfg, s))
        assertNull(SwPolicy.serve("https://never.example/x.png", "GET", cfg, s))   // not stored: the worker's own network
        assertNull(SwPolicy.serve(cdnImg, "POST", cfg, s))
        // the block the cached WebViews run at document start: register() refused, old registrations and their caches removed
        assertTrue(SwPolicy.BLOCK_JS.contains("'register'") && SwPolicy.BLOCK_JS.contains("unregister()") && SwPolicy.BLOCK_JS.contains("caches.delete"))
    }

    @Test fun `the startup job fills every page with no page ever opened`() {
        val s = store()
        val pages = listOf(page, LnktreePage("leafy", "https://leafy.example/"), LnktreePage("astro", "https://astro.example/"))
        val fetch = { u: String -> if (u.startsWith(site) || u.startsWith("https://cdn") || u.startsWith("https://raw")) net()(u) else Fetched("text/html", null, 200, "<html><img src=\"a.png\"></html>".toByteArray()).takeIf { !u.endsWith(".png") } ?: Fetched("image/png", null, 200, byteArrayOf(1)) }
        val out = TtlPrefetch.run(pages, cfg, s, fetch)
        assertEquals(pages.map { it.id }.toSet(), out.map { it.page }.toSet())
        assertTrue(out.all { it.action == TtlPrefetch.Action.FILL && it.result == "complete" })
        assertTrue(pages.all { s.complete(it.id, it.url) })
        assertTrue(out.first().line().startsWith("CACHE_FETCH page="))
    }

    @Test fun `a copy younger than ttl_days is not fetched at startup`() {
        val s = store(); TtlPrefetch.run(listOf(page), cfg, s, net())
        now += 6 * day
        val asked = Collections.synchronizedList(ArrayList<String>())
        val out = TtlPrefetch.run(listOf(page), cfg, s, net("v2", asked = asked))
        assertEquals(TtlPrefetch.Action.SKIP, out.single().action)
        assertTrue("nothing downloaded: $asked", asked.isEmpty())
        assertTrue(s.get("personal", site)!!.body.readText().contains("v1"))
    }

    @Test fun `a copy older than ttl_days is refetched and replaced`() {
        val s = store(); TtlPrefetch.run(listOf(page), cfg, s, net())
        now += 8 * day
        val out = TtlPrefetch.run(listOf(page), cfg, s, net("v2"))
        assertEquals(TtlPrefetch.Action.REFETCH, out.single().action); assertEquals("replaced", out.single().result)
        assertTrue(s.get("personal", site)!!.body.readText().contains("v2"))
        assertEquals("v2$cdnImg", s.get("personal", cdnImg)!!.body.readText())
        assertTrue(s.complete("personal", site)); assertEquals(CacheState.FRESH, s.state("personal", site, cfg.ttlMs))
        assertFalse("staging is emptied", s.stagingFor("personal").root.exists())
    }

    @Test fun `a failed refetch keeps the old copy`() {
        val s = store(); TtlPrefetch.run(listOf(page), cfg, s, net())
        now += 8 * day
        // the page itself fails
        assertEquals("kept-old", TtlPrefetch.run(listOf(page), cfg, s, net("v2", down = setOf(site))).single().result)
        assertTrue(s.get("personal", site)!!.body.readText().contains("v1")); assertTrue(s.complete("personal", site))
        // the page comes, one of its own files does not: the new copy is incomplete and is NOT swapped in
        assertEquals("kept-old", TtlPrefetch.run(listOf(page), cfg, s, net("v2", down = setOf(site + "bg.png"))).single().result)
        assertTrue(s.get("personal", site)!!.body.readText().contains("v1")); assertNotNull(s.get("personal", site + "bg.png"))
        assertEquals("a kept-old refetch changes no shared file either", "v1$cdnImg", s.get("personal", cdnImg)!!.body.readText())
        // a shared file whose refetch fails keeps its stored copy: the page stays whole
        TtlPrefetch.run(listOf(page), cfg, s, net("v2", down = setOf(rawImg)))
        assertEquals("v1$rawImg", s.get("personal", rawImg)!!.body.readText())
        assertTrue(s.complete("personal", site))
    }

    @Test fun `opening a fresh page causes no network request for the main frame or its files`() {
        val s = store(); TtlPrefetch.run(listOf(page), cfg, s, net())
        now += 3 * day
        for (u in listOf(site, site + "style.css", dataJs, site + "script.js", cdnImg, rawImg, three, site + "bg.png")) {
            val r = LnktreeResolver.serve("personal", u, cfg, s, { error("network for $u") }, { error("background fetch for $u") }, site)
            assertEquals("cache", r!!.source)
        }
        // older than ttl_days: still painted at once, the refetch goes to the background
        now += 5 * day
        val bg = ArrayList<String>()
        assertNotNull(LnktreeResolver.serve("personal", site, cfg, s, { error("never blocks") }, { bg += it }, site))
        assertEquals(listOf(site), bg)
    }

    @Test fun `pull-down always refetches`() {
        val s = store(); TtlPrefetch.run(listOf(page), cfg, s, net())
        now += 1 * day
        assertEquals(TtlPrefetch.Action.SKIP, TtlPrefetch.decide(page, cfg, s))
        assertTrue(LnktreeResolver.hardRefresh("personal", cfg, s) > 0)
        assertEquals(TtlPrefetch.Action.FILL, TtlPrefetch.decide(page, cfg, s))
        val asked = Collections.synchronizedList(ArrayList<String>())
        assertEquals("complete", TtlPrefetch.page(page, cfg, s, net("v3", asked = asked)).result)
        assertTrue(site in asked); assertTrue(s.get("personal", site)!!.body.readText().contains("v3"))
    }

    @Test fun `videos keep to the per-file and total caps and images are always stored`() {
        val s = store()
        val vids = (1..4).map { "https://cdn.example/v$it.mp4" }
        val html = "<html>" + vids.joinToString("") { "<video><source src=\"$it\"></video>" } + "<img src=\"https://cdn.example/big.jpg\"></html>"
        val sizes = mapOf(vids[0] to 12, vids[1] to 8, vids[2] to 8, vids[3] to 1)
        LnktreePrefetch.fill(LnktreePage("v", "https://v.example/"), cfg, s, { u ->
            when { u == "https://v.example/" -> Fetched("text/html", null, 200, html.toByteArray()); u.endsWith(".mp4") -> Fetched("video/mp4", null, 200, ByteArray(sizes[u]!!))
                else -> Fetched("image/jpeg", null, 200, ByteArray(50)) } }, null)
        assertEquals(LnktreePrefetch.VIDEO_CAPPED, s.get("v", vids[0])!!.status)      // 12 > 10 per file
        assertEquals(200, s.get("v", vids[1])!!.status)                               // 8 <= 10 per file, total 8
        assertEquals(LnktreePrefetch.VIDEO_CAPPED, s.get("v", vids[2])!!.status)      // 8 + 8 > 15 total
        assertEquals(200, s.get("v", vids[3])!!.status)                               // 8 + 1 <= 15
        assertEquals(50L, s.get("v", "https://cdn.example/big.jpg")!!.body.length())  // an image is stored whatever its size
        assertTrue("a capped video is a known skip", s.complete("v", "https://v.example/"))
        // eviction: videos go first, images stay
        s.trim(s.root.walkTopDown().filter { it.isFile && !it.name.startsWith("_") && !it.name.startsWith(".") }.sumOf { it.length() } - 1)
        assertNotNull(s.get("v", "https://cdn.example/big.jpg"))
    }

    @Test fun `a new cache_version wipes the copies taken before it once`() {
        val s = store(); LnktreePrefetch.fill(page, cfg, s, net(), null)
        assertTrue(s.ensureVersion("1957")); assertNull(s.get("personal", site))
        LnktreePrefetch.fill(page, cfg, s, net(), null)
        assertFalse(s.ensureVersion("1957")); assertNotNull(s.get("personal", site))
        assertTrue(File(s.root, ".version").readText() == "1957")
    }

    @Test fun `a shared file is downloaded and stored once for every page and counted once`() {
        val s = store(); val asked = Collections.synchronizedList(ArrayList<String>())
        val img = "https://cdn.example/same.jpg"
        val pages = listOf(LnktreePage("a", "https://a.example/"), LnktreePage("b", "https://b.example/"), LnktreePage("c", "https://c.example/"))
        TtlPrefetch.run(pages, cfg.copy(prefetchConcurrency = 1), s, { u: String -> asked += u
            if (u == img) Fetched("image/jpeg", null, 200, ByteArray(1000)) else Fetched("text/html", null, 200, "<img src=\"$img\">".toByteArray()) })
        assertEquals("downloaded once", 1, asked.count { it == img })
        assertEquals("stored once", 1, s.root.walkTopDown().count { it.isFile && it.name == CacheKey.of(img) + ".body" })
        assertTrue(pages.all { s.complete(it.id, it.url) })
        assertTrue("counted once: ${s.totalBytes()}", s.totalBytes() in 1000L..2900L)
        assertEquals(1000L, s.shared!!.totalBytes() - s.shared!!.metaFile(LnktreeCacheStore.SHARED_ID, img).length())
    }

    @Test fun `the cache cap holds while the job runs, videos stop first and images still finish`() {
        val s = store(); val c = cfg.copy(videoMaxFileBytes = 0, videoMaxTotalBytes = 0, maxBytes = 3000)
        val imgs = (1..2).map { "https://cdn.example/i$it.jpg" }; val vids = (1..2).map { "https://cdn.example/v$it.mp4" }
        val html = "<html>" + imgs.joinToString("") { "<img src=\"$it\">" } + vids.joinToString("") { "<video><source src=\"$it\"></video>" } + "</html>"
        LnktreePrefetch.fill(LnktreePage("v", "https://v.example/"), c, s, { u -> when { u == "https://v.example/" -> Fetched("text/html", null, 200, html.toByteArray())
            u.endsWith(".mp4") -> Fetched("video/mp4", null, 200, ByteArray(500)); else -> Fetched("image/jpeg", null, 200, ByteArray(1000)) } }, null)
        imgs.forEach { assertEquals(200, s.get("v", it)!!.status) }
        vids.forEach { assertEquals(LnktreePrefetch.VIDEO_CAPPED, s.get("v", it)!!.status); assertEquals("cache-cap", s.reason("v", it)) }
        assertTrue("known-missing still counts as complete", s.complete("v", "https://v.example/"))
        assertEquals(s.totalBytes(true), s.budget.used.get())
    }

    @Test fun `the data-file scan plans at most max_script_media_per_page media files and marks the rest known-missing`() {
        val s = store(); val c = cfg.copy(maxScriptMediaPerPage = 2); val asked = Collections.synchronizedList(ArrayList<String>())
        val media = (1..5).map { "https://cdn.example/m$it.jpg" }
        LnktreePrefetch.fill(LnktreePage("d", "https://d.example/"), c, s, { u -> asked += u; when (u) {
            "https://d.example/" -> Fetched("text/html", null, 200, "<script src=\"d.js\"></script>".toByteArray())
            "https://d.example/d.js" -> Fetched("application/javascript", null, 200, media.joinToString(",", "var m=[", "]") { "\"$it\"" }.toByteArray())
            else -> Fetched("image/jpeg", null, 200, ByteArray(10)) } }, null)
        assertEquals(2, asked.count { it in media })
        assertEquals(3, media.count { s.reason("d", it) == "plan-cap" })
        assertTrue(s.complete("d", "https://d.example/"))
        assertTrue(s.refs("d").containsAll(media))
    }

    @Test(timeout = 10_000) fun `a download that trickles for ever or never ends is cut off and never stalls the job`() {
        // a body that sends one byte per read and never ends (a stalled CDN): the deadline ends it
        var t = 0L
        val trickle = object : java.io.InputStream() { override fun read() = 1; override fun read(b: ByteArray, o: Int, n: Int): Int { b[o] = 1; return 1 } }
        assertTrue(BoundedRead.read(trickle, 0, 1_000) { t += 10; t } is BoundedRead.Result.Timeout)
        // an endless fast body (a video served with no Content-Length): the byte cap ends it, it is never read whole into memory
        val endless = object : java.io.InputStream() { override fun read() = 1; override fun read(b: ByteArray, o: Int, n: Int) = n }
        assertTrue(BoundedRead.read(endless, 1_000_000, 0) is BoundedRead.Result.TooLarge)
        val ok = BoundedRead.read(java.io.ByteArrayInputStream(ByteArray(5000) { 7 }), 1_000_000, 1_000)
        assertEquals(5000, (ok as BoundedRead.Result.Ok).bytes.size)
    }
}

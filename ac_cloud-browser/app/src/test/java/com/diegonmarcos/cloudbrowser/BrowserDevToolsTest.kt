package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.ConsoleLog
import com.diegonmarcos.superapp.browser.GraphicsStats
import com.diegonmarcos.superapp.browser.LineSelection
import com.diegonmarcos.superapp.browser.NetLog
import com.diegonmarcos.superapp.browser.RefreshMode
import com.diegonmarcos.superapp.browser.RefreshPlan
import com.diegonmarcos.superapp.browser.RingBuffer
import com.diegonmarcos.superapp.browser.SourceDoc
import com.diegonmarcos.superapp.browser.ViewModePlan
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1141 menu rows, refresh scopes, view mode, console / network logs, source viewer logic. */
class BrowserDevToolsTest {
    private val cfg = BrowserConfig.parse(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").toString())
    private val facts = mapOf("page" to true, "can_go_back" to true, "can_go_forward" to true, "text_tools" to true)

    @Test fun `#1401 no icon rows - the former icon actions are Page Actions and Page Tools rows`() {
        assertTrue(cfg.menu.iconRows(facts).isEmpty())
        val sec = { id: String -> cfg.menu.item(id)!!.section }
        listOf("back", "forward", "reload", "bookmark", "copy_url", "open_external", "add_to_home", "hard_refresh", "light_refresh").forEach { assertEquals(it, "actions", sec(it)) }
        listOf("find", "share", "desktop", "print", "download_page", "save_page", "save_site", "fill_profile", "translate").forEach { assertEquals(it, "page_tools", sec(it)) }
        assertEquals(listOf("Actions", "Page Tools", "Site Data", "Site Perms", "Dev Tools"), cfg.menu.sections.map { it.label })
    }

    @Test fun `Page Tools rows are grouped under categories`() {
        val cats = cfg.menu.items.filter { it.section == "page_tools" && it.category != null }.groupBy { it.category }.mapValues { e -> e.value.map { it.id } }
        assertEquals(listOf("Reading", "Save & Export", "Find & Share", "View mode", "Autofill"), cats.keys.toList())
        assertEquals(listOf("reader", "translate", "zoom", "summarize_topics", "view_source"), cats["Reading"])
        assertEquals(listOf("print", "convert_pdf", "download_page", "save_page", "save_site"), cats["Save & Export"])
        assertEquals(listOf("find", "share"), cats["Find & Share"])
        assertEquals(listOf("desktop"), cats["View mode"])
        assertEquals(listOf("fill_profile"), cats["Autofill"])
        assertEquals(null, cfg.menu.item("back")!!.category)
    }

    @Test fun `dev tools is the last section holding the debugger rows and the JS toggle, view source is a Page Tool`() {
        val ids = cfg.menu.sections.map { it.id }
        assertEquals("Dev Tools", cfg.menu.sections.first { it.id == "dev_tools" }.label)
        assertEquals("dev_tools", ids.last())
        val dev = cfg.menu.listSections(facts).first { it.first.id == "dev_tools" }.second.map { it.item.id }
        assertEquals(listOf("dev_console", "dev_network", "dev_elements", "dev_storage", "dev_graphics", "dev_remote_debug", "shield_js"), dev)
        assertEquals("page_tools", cfg.menu.item("view_source")!!.section)
        assertTrue("no debugger row is reachable over the loopback API", dev.none { cfg.menu.item(it)!!.api })
        assertTrue("icon sections are not list sections", cfg.menu.listSections(facts).none { it.first.id.startsWith("icons") })
    }

    @Test fun `graphics stats parse the injected sampler's answer and degrade to n-a`() {
        val ok = GraphicsStats.parse("""{"v":{"fps":59.6,"ms":16.8,"draws":42,"tris":12000,"gpu":"Adreno (TM) 740","webgl":true}}""")
        assertTrue(ok.available); assertEquals(59.6, ok.fps!!, 0.001); assertEquals(42L, ok.draws); assertEquals(12000L, ok.tris)
        assertEquals("Adreno (TM) 740", ok.gpu); assertEquals(null, ok.note)
        assertEquals("59.6", ok.rows()[0].second); assertEquals("16.8 ms", ok.rows()[1].second)
        val noGl = GraphicsStats.parse("""{"v":{"fps":30,"ms":33,"draws":0,"tris":0,"gpu":null,"webgl":false}}""")
        assertEquals(listOf("30.0", "33.0 ms", "n/a", "n/a", "n/a"), noGl.rows().map { it.second })
        assertTrue(noGl.note!!.contains("no WebGL"))
        val warm = GraphicsStats.parse("""{"v":{"fps":0,"ms":0,"draws":0,"tris":0,"gpu":"x","webgl":true}}""")
        assertEquals("n/a", warm.rows()[0].second); assertTrue(warm.note!!.contains("sampling"))
        listOf(null, "", "not json", """{"v":null}""").forEach {
            val g = GraphicsStats.parse(it)
            assertFalse(g.available); assertEquals(GraphicsStats.BLOCKED, g.note); assertTrue(g.rows().all { r -> r.second == "n/a" })
        }
        assertEquals("script error: boom", GraphicsStats.parse("""{"error":"boom"}""").note)
        assertTrue(GraphicsStats.JS.contains("requestAnimationFrame") && GraphicsStats.JS.contains("WEBGL_debug_renderer_info") && GraphicsStats.JS.contains("drawElements"))
        assertTrue(ok.asText().contains("GPU: Adreno"))
    }

    @Test fun `hard refresh clears cache cookies storage and workers, light only cache and workers`() {
        assertEquals(listOf("cache", "cookies", "storage", "service_workers"), RefreshPlan.of(RefreshMode.HARD).scopes())
        assertEquals(listOf("cache", "service_workers"), RefreshPlan.of(RefreshMode.LIGHT).scopes())
        val light = RefreshPlan.of(RefreshMode.LIGHT)
        assertFalse(light.cookies); assertFalse(light.domStorage)
        assertTrue(RefreshPlan.of(RefreshMode.HARD).reloadHeaders.containsKey("Cache-Control"))
        assertTrue(light.reloadHeaders.containsKey("Cache-Control"))
    }

    @Test fun `desktop and mobile set the user agent and the viewport`() {
        val d = "Mozilla/5.0 (X11; Linux x86_64) Chrome/124 Safari/537.36"
        val m = "Mozilla/5.0 (Linux; Android 14; Pixel 8; wv) Version/4.0 Chrome/124 Mobile Safari/537.36"
        val desk = ViewModePlan.of(true, d, m, "cur")
        val mob = ViewModePlan.of(false, d, m, "cur")
        assertEquals(d, desk.userAgent)
        assertEquals("#1420 never disguised: the declared agent as it is", m, mob.userAgent)
        assertTrue(desk.wideViewPort && desk.overview && mob.wideViewPort && mob.overview)
        assertEquals(1, desk.initialScale); assertEquals(0, mob.initialScale)
        assertEquals(ViewModePlan.DESKTOP_VIEWPORT_JS, desk.viewportJs)
        assertEquals(null, mob.viewportJs)
        assertEquals("falls back to the current UA", "cur", ViewModePlan.of(true, null, null, "cur").userAgent)
    }

    @Test fun `the console ring buffer never exceeds its capacity and drops the oldest`() {
        val b = RingBuffer<Int>(3)
        (1..10).forEach { b.add(it) }
        assertEquals(listOf(8, 9, 10), b.snapshot())
        assertEquals(7L, b.dropped)
        val v = b.version; b.clear()
        assertTrue(b.snapshot().isEmpty() && b.version > v)
        assertEquals(500, ConsoleLog.CAPACITY)
    }

    @Test fun `console entries keep source and line, filter by level and text, and are cut when huge`() {
        val all = listOf(
            ConsoleLog.entry(1, "log", "hello", "a.js", 3), ConsoleLog.entry(2, "error", "Uncaught TypeError: x", "b.js", 12),
            ConsoleLog.entry(3, "warning", "careful", null, 0), ConsoleLog.entry(4, "log", "x".repeat(10000), "", 0))
        assertEquals("b.js:12", all[1].location()); assertEquals("", all[2].location())
        assertEquals(listOf("ERROR"), ConsoleLog.filter(all, setOf("ERROR")).map { it.level })
        assertEquals(4, ConsoleLog.filter(all, emptySet()).size)
        assertEquals(listOf(1L), ConsoleLog.filter(all, emptySet(), "HELLO").map { it.ts })
        assertEquals(listOf(2L), ConsoleLog.filter(all, emptySet(), "b.js:12").map { it.ts })
        assertEquals(ConsoleLog.MAX_MESSAGE, all[3].message.length)
        assertTrue(ConsoleLog.asText(all).contains("[ERROR] Uncaught TypeError: x  (b.js:12)"))
    }

    @Test fun `network rows get a type, filter by type, text and blocked`() {
        assertEquals("document", NetLog.typeOf("https://a.org/x", true, null))
        assertEquals("script", NetLog.typeOf("https://a.org/app.js?v=2", false, null))
        assertEquals("data", NetLog.typeOf("https://a.org/api", false, "application/json"))
        val rows = listOf(NetLog.entry(1, "get", "https://a.org/", true, null, false), NetLog.entry(2, null, "https://t.net/p.gif", false, null, true))
        assertEquals("GET", rows[1].method)
        assertEquals(listOf("https://t.net/p.gif"), NetLog.filter(rows, emptySet(), blockedOnly = true).map { it.url })
        assertEquals(listOf("https://a.org/"), NetLog.filter(rows, setOf("document")).map { it.url })
        assertEquals(1, NetLog.filter(rows, emptySet(), "t.net").size)
        assertTrue(NetLog.line(rows[1].copy(status = 404)).contains("404") && NetLog.line(rows[1]).contains("BLOCKED"))
        assertEquals(1000, NetLog.CAPACITY)
    }

    @Test fun `source viewer numbers lines, searches case-insensitively, steps with wrap, highlights ranges`() {
        val doc = SourceDoc("<html>\r\n<body>\n  <p>Hello</p>\n  <P>hello again</P>\n</body>\n</html>")
        assertEquals(6, doc.lines.size)
        assertEquals("1", doc.number(0)); assertEquals(1, doc.gutterDigits)
        val hits = doc.search("HELLO")
        assertEquals(listOf(2, 3), hits)
        assertEquals(emptyList<Int>(), doc.search(""))
        assertEquals(1, doc.step(hits, 0, true)); assertEquals(0, doc.step(hits, 1, true))
        assertEquals(1, doc.step(hits, 0, false)); assertEquals(0, doc.step(hits, -1, true)); assertEquals(1, doc.step(hits, -1, false))
        assertEquals(-1, doc.step(emptyList(), 0, true))
        assertEquals(listOf(5..9), doc.ranges(2, "hello"))
        assertEquals(2, SourceDoc("a\nb\n").lines.size - 1)
    }

    @Test fun `range selection anchors, extends, resets, and yields the lines in either order`() {
        val doc = SourceDoc("l0\nl1\nl2\nl3\nl4")
        var s = LineSelection()
        assertFalse(s.active); assertEquals("", s.text(doc))
        s = s.tap(3)
        assertEquals("l3", s.text(doc)); assertEquals(1, s.count())
        s = s.tap(1)
        assertEquals("l1\nl2\nl3", s.text(doc)); assertEquals(3, s.count())
        assertTrue(s.contains(2)); assertFalse(s.contains(4))
        s = s.tap(4)
        assertEquals("l4", s.text(doc)); assertEquals(null, s.end)
        assertEquals("l3\nl4", doc.slice(9, 3))
    }
}

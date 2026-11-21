package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.superapp.browser.BrowserSearch
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1841 the Kotlin twins answer what src/tests.rs holds the Rust core to (the JVM has no libbrowser_core.so, so these ARE the fallback), and the old pure rules agree with the core's resolve. */
class BrowserCoreParityTest {
    private val tpl = "https://q.example/?q={q}"
    private val engine = BrowserSearchEngine("q", "Q", tpl)

    @Test fun `the omnibox resolves like the cases of the Rust suite`() {
        for ((typed, want) in listOf(
            "qwant.com" to "https://qwant.com", "localhost:8000" to "http://localhost:8000", "192.168.1.5/x" to "http://192.168.1.5/x",
            "172.20.0.1" to "http://172.20.0.1", "172.40.0.1" to "https://q.example/?q=172.40.0.1", "nas.local" to "http://nas.local",
            "http://a.b/c d" to "http://a.b/c d", "2.5" to "https://q.example/?q=2.5", "rust lang" to "https://q.example/?q=rust+lang",
            "a.b.co.uk/p" to "https://a.b.co.uk/p", "café & co" to "https://q.example/?q=caf%C3%A9+%26+co", "  " to "",
            "hello" to "https://q.example/?q=hello", "x.1a" to "https://q.example/?q=x.1a",
        )) assertEquals(typed, want, BrowserCore.resolve(typed, tpl))
    }

    @Test fun `BrowserSearch resolve is the core's answer and equals the readable rules`() {
        val corpus = listOf("qwant.com", "localhost:8000", "10.0.0.2", "172.16.5.5/a", "172.31.0.1", "172.32.0.1", "foo.bar", "foo bar", "a.b.c.d", "https://x.y/z?q=1",
            "  spaced.com  ", "1.2.3.4", "dev.lan", "ünï.de", "what is rust?", "c++", "a.b", "e.g.", "x@y.com", "site.com/path?q=a b")
        for (s in corpus) {
            val rules = s.trim().let { t -> if (t.isEmpty()) "" else if (BrowserSearch.isUrlLike(t)) BrowserSearch.normalizeUrl(t) else BrowserSearch.searchUrl(t, engine) }
            assertEquals(s, rules, BrowserSearch.resolve(s, engine))
        }
    }

    @Test fun `the start decision never reopens the last page after an unclean end`() {
        assertEquals("restore" to false, BrowserCore.startDecision(false, true, true, false, false))
        assertEquals("grid" to true, BrowserCore.startDecision(true, true, true, false, false))
        assertEquals("grid" to false, BrowserCore.startDecision(true, false, true, false, false))
        assertEquals("grid" to false, BrowserCore.startDecision(false, true, false, false, false))
        assertEquals("open_url" to false, BrowserCore.startDecision(true, true, true, true, false))
        assertEquals("kept" to false, BrowserCore.startDecision(true, true, true, false, true))
    }

    @Test fun `the reader keeps the article and drops the chrome`() {
        val html = "<html><head><title>My &amp; Story</title><style>p{color:red}</style></head><body><nav><p>Menu menu menu menu menu menu</p></nav>" +
            "<h1>Headline</h1><p>The first paragraph is long enough to keep, with an &lt;entity&gt;.</p><script>var x = 'a long script string that must go';</script>" +
            "<p>short</p><ul><li>A list item that is also long enough to be kept here</li></ul><footer><p>Footer footer footer footer footer footer</p></footer></body></html>"
        val (title, paras) = BrowserCore.reader(html)
        assertEquals("My & Story", title)
        assertEquals(listOf("Headline", "The first paragraph is long enough to keep, with an <entity>.", "A list item that is also long enough to be kept here"), paras)
    }

    @Test fun `rows hold the stores and the history rule dedupes and caps`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "rows-test-" + System.nanoTime()).apply { mkdirs() }
        val r = BrowserCore.Rows(File(dir, "session.db").path)
        r.put("setting", "desktop_mode", "true", 1); r.put("perm", "a.example|camera", "deny", 2)
        assertEquals("true", r.get("setting", "desktop_mode")); assertNull(r.get("setting", "nope")); assertEquals(1, r.count("perm"))
        assertFalse(r.put("", "k", "v", 0))
        assertTrue(r.delete("perm", "a.example|camera")); assertEquals(1, r.clear("setting"))
        for (i in 0 until 30) r.historyVisit("https://h.example/$i", "t", 100L + i, 10)
        r.historyVisit("https://h.example/5", "again", 500, 10)
        val rows = r.list("history", 100)
        assertEquals(10, rows.size); assertEquals("https://h.example/5", rows[0].first)
        assertEquals("a reopen reads what was written", "again", org.json.JSONObject(BrowserCore.Rows(File(dir, "session.db").path).get("history", "https://h.example/5")!!).getString("title"))
        dir.deleteRecursively()
    }

    @Test fun `the download machine and the list merge`() {
        assertEquals("running", BrowserCore.downloadNext("queued", "start")); assertEquals("paused", BrowserCore.downloadNext("running", "pause"))
        assertEquals("done", BrowserCore.downloadNext("running", "finish")); assertEquals("queued", BrowserCore.downloadNext("failed", "retry"))
        assertNull(BrowserCore.downloadNext("done", "start")); assertNull(BrowserCore.downloadNext("queued", "finish"))
        assertEquals("||t.example^\n/ads/banner\n@@/ads/banner/ok\n||x.net^\$third-party",
            BrowserCore.mergeLists(listOf("! a\n||t.example^\n\n/ads/banner\n", "||t.example^\n@@/ads/banner/ok\n# c\n||x.net^\$third-party")))
    }

    @Test fun `path rules block a sub-request unless an exception holds`() {
        val f = BrowserCore.Filter("||t.example^\n/ads/banner\n@@/ads/banner/ok\n||x.net^\$third-party")
        assertTrue(f.isTracker("x.net"))
        assertTrue(f.shouldBlockUrl("https://cdn.site.example/ads/banner/1.png", "cdn.site.example", "news.example", false))
        assertFalse(f.shouldBlockUrl("https://cdn.site.example/ads/banner/ok/1.png", "cdn.site.example", "news.example", false))
        assertFalse(f.shouldBlockUrl("https://cdn.site.example/ads/banner/1.png", "cdn.site.example", "news.example", true))
        assertTrue(f.shouldBlockUrl("https://a.t.example/x", "a.t.example", "news.example", false))
    }
}

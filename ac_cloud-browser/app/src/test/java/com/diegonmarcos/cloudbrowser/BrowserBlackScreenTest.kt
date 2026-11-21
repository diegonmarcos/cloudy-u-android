package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.cloudlib.browsercore.SearchEntry
import com.diegonmarcos.cloudlib.browsercore.TabSnapshot
import com.diegonmarcos.superapp.browser.BrowserAddToHome
import com.diegonmarcos.superapp.browser.BrowserKeepAlive
import com.diegonmarcos.superapp.browser.BrowserLog
import com.diegonmarcos.superapp.browser.BrowserRenderGuard
import com.diegonmarcos.superapp.browser.BrowserSessionHealth
import com.diegonmarcos.superapp.browser.BrowserSessionSnapshots
import com.diegonmarcos.superapp.browser.BrowserTrackers
import com.diegonmarcos.superapp.browser.TabThumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1841 restore and renderer-gone recovery, the log's redaction, the Kotlin twins of the Rust core, the unclean-start marker. */
class BrowserBlackScreenTest {

    // ── renderer gone ────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `a renderer that dies is rebuilt from the snapshot, a tab that keeps dying is given up on`() {
        var now = 0L
        val g = BrowserRenderGuard(max = 3, windowMs = 60_000, now = { now })
        repeat(3) { now += 1_000; assertEquals(BrowserRenderGuard.Action.RECREATE_FROM_SNAPSHOT, g.onGone("t1")) }
        now += 1_000
        assertEquals(BrowserRenderGuard.Action.GIVE_UP_SHOW_ERROR, g.onGone("t1"))
        assertEquals("another tab is judged on its own deaths", BrowserRenderGuard.Action.RECREATE_FROM_SNAPSHOT, g.onGone("t2"))
        now += 120_000
        assertEquals("deaths older than the window are forgotten", BrowserRenderGuard.Action.RECREATE_FROM_SNAPSHOT, g.onGone("t1"))
        g.onHealthy("t1"); assertEquals(0, g.deathsOf("t1"))
    }

    @Test fun `the error page never carries markup from the host`() {
        assertFalse(BrowserRenderGuard.errorPage("a<script>.example").contains("<script>"))
    }

    // ── restore ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `restore order is the WebView state, then the snapshot at its scroll, then the tab url`() {
        val snap = TabSnapshot("k", "https://a.example/page", "T", 0, 900, history = listOf("https://a.example/", "https://a.example/page"), index = 1)
        assertEquals(BrowserSessionSnapshots.Source.WEBVIEW_STATE, BrowserSessionSnapshots.plan(true, snap, "https://tab.example/").source)
        val p = BrowserSessionSnapshots.plan(false, snap, "https://tab.example/")
        assertEquals(BrowserSessionSnapshots.Source.SNAPSHOT, p.source); assertEquals("https://a.example/page", p.url); assertEquals(900, p.scrollY)
        val q = BrowserSessionSnapshots.plan(false, null, "https://tab.example/")
        assertEquals(BrowserSessionSnapshots.Source.TAB_URL, q.source); assertEquals("https://tab.example/", q.url)
        assertEquals("a snapshot of a blank page is not a page", BrowserSessionSnapshots.Source.TAB_URL,
            BrowserSessionSnapshots.plan(false, snap.copy(url = "about:blank"), "https://tab.example/").source)
    }

    @Test fun `a snapshot survives the Kotlin session store and a reopen`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bc-test-" + System.nanoTime()).apply { mkdirs() }
        val path = File(dir, "session.db").path
        val s = BrowserCore.SessionStore(path)
        val a = TabSnapshot("a", "https://a.example/", "A", 1, 2, "/f.png", listOf("https://a.example/"), 0, "/s.bin", 10)
        assertTrue(s.put(a)); assertTrue(s.put(a.copy(key = "b", updatedAt = 20)))
        val again = BrowserCore.SessionStore(path)
        assertEquals(a, again.get("a"))
        assertEquals(listOf("b", "a"), again.list().map { it.key })
        assertEquals(1, again.prune(listOf("a"))); assertNull(again.get("b")); assertEquals(1, again.count())
        dir.deleteRecursively()
    }

    // ── the unclean start ────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `a start that finds the running marker is an unclean start, a clean stop clears it`() {
        val d = File(System.getProperty("java.io.tmpdir"), "bh-test-" + System.nanoTime())
        assertFalse(BrowserSessionHealth.startAndCheck(d))
        assertTrue("killed while showing", BrowserSessionHealth.startAndCheck(d))
        BrowserSessionHealth.markClean(d)
        assertFalse(BrowserSessionHealth.startAndCheck(d))
        d.deleteRecursively()
    }

    // ── black pictures ───────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `an all-black or transparent tab picture is recognised, a real page is not`() {
        assertTrue(TabThumbnails.blackPixels(IntArray(4096) { 0xFF000000.toInt() }))
        assertTrue(TabThumbnails.blackPixels(IntArray(4096)))
        assertFalse(TabThumbnails.blackPixels(IntArray(4096) { if (it % 3 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }))
        assertFalse(TabThumbnails.blackPixels(IntArray(4096) { 0xFF1A0033.toInt() or 0x00505050 }))
    }

    // ── the log ──────────────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `urls are logged as host and path only`() {
        assertEquals("host.example:8443/a/b", BrowserLog.url("https://user:pw@host.example:8443/a/b?token=SECRET#frag"))
        assertEquals("host.example/", BrowserLog.url("https://host.example/?a=1"))
        assertEquals("data:", BrowserLog.url("data:text/html;base64,AAAA"))
        assertEquals("-", BrowserLog.url("  "))
        assertFalse(BrowserLog.url("https://h.example/p?access_token=abc").contains("abc"))
    }

    @Test fun `an event is one line of key=value`() {
        val l = BrowserLog.line("renderer-gone", "tab" to "ab12cd34", "did_crash" to true, "why" to "out of\nmemory")
        assertEquals("ev=renderer-gone tab=ab12cd34 did_crash=true why=out_of_memory", l)
        assertFalse(l.contains('\n'))
        assertEquals("RUNNING_CRITICAL", BrowserLog.trimName(15)); assertEquals("UI_HIDDEN", BrowserLog.trimName(20))
    }

    @Test fun `log lines reach the sink and the ring`() {
        val seen = ArrayList<String>()
        val old = BrowserLog.sink
        BrowserLog.sink = { _, tag, line -> seen.add("$tag $line") }
        try {
            BrowserLog.clearRecent(); BrowserLog.i(BrowserLog.TAB, "create", "tab" to "x")
            assertEquals(listOf("CBrowser/Tab ev=create tab=x"), seen); assertEquals(seen, BrowserLog.recent())
        } finally { BrowserLog.sink = old }
    }

    // ── keep-alive policy ────────────────────────────────────────────────────────────────────────────────────────────
    @Test fun `a foreground service only while media plays`() {
        assertTrue(BrowserKeepAlive.decide(true, false, false).foregroundService)
        assertFalse(BrowserKeepAlive.decide(false, true, false).foregroundService)
        assertFalse(BrowserKeepAlive.decide(false, false, true).foregroundService)
        assertFalse(BrowserKeepAlive.decide(false, false, false).foregroundService)
    }

    // ── the Kotlin twins of the Rust core (the same cases tests.rs holds) ───────────────────────────────────────────
    private val entries = listOf(
        SearchEntry("Rust Programming Language", "https://www.rust-lang.org/", 5), SearchEntry("Kotlin docs", "https://kotlinlang.org/docs", 9),
        SearchEntry("Cloud rust notes", "https://notes.example/rust", 7), SearchEntry("Other", "https://example.com/", 1))

    @Test fun `search twins agree with the Rust cases`() {
        assertEquals(listOf(0, 2), BrowserCore.search(entries, "rust", 50, false))
        assertEquals(listOf(0, 2), BrowserCore.search(entries, "  RUST ", 50, false))
        assertEquals(listOf(0, 1, 2, 3), BrowserCore.search(entries, "", 50, false))
        assertEquals(listOf(0), BrowserCore.search(entries, "rust", 1, false))
        assertEquals(listOf(0, 2), BrowserCore.search(entries, "rust", 50, true))
        assertEquals(listOf(1), BrowserCore.search(entries, "kot docs", 50, true))
        assertTrue(BrowserCore.search(entries, "rust kotlin", 50, true).isEmpty())
    }

    @Test fun `filter twins agree with the Rust cases`() {
        val f = BrowserCore.Filter("! comment\n||tracker.example^\nads.example.org\n@@||ok.tracker.example^\n# note\ncom\n\n")
        assertTrue(f.isTracker("tracker.example")); assertTrue(f.isTracker("a.b.Tracker.Example")); assertTrue(f.isTracker("ads.example.org"))
        assertFalse(f.isTracker("example.org")); assertFalse(f.isTracker("ok.tracker.example")); assertFalse(f.isTracker("com"))
        assertTrue(f.shouldBlock("x.tracker.example", "news.example", false))
        assertFalse(f.shouldBlock("x.tracker.example", "news.example", true))
        assertFalse(f.shouldBlock("x.tracker.example", "tracker.example", false))
        assertFalse(f.shouldBlock("", "news.example", false))
    }

    @Test fun `BrowserTrackers keeps its old behaviour through the core`() {
        val t = BrowserTrackers(listOf("Hotjar.com", "bad.", "tld", "ads.net."))
        assertTrue(t.isTracker("static.hotjar.com")); assertTrue(t.isTracker("ads.net")); assertFalse(t.isTracker("tld"))
        assertTrue(t.shouldBlock("x.hotjar.com", "news.org", false)); assertFalse(t.shouldBlock("x.hotjar.com", "hotjar.com", false))
    }

    // ── add to home: the route and the words ─────────────────────────────────────────────────────────────────────────
    @Test fun `add to home tries the launcher first, then Cloud SuperApp's home over the IPC`() {
        assertEquals(BrowserAddToHome.Route.SYSTEM_PIN, BrowserAddToHome.route(true, true))
        assertEquals(BrowserAddToHome.Route.SUPERAPP_IPC, BrowserAddToHome.route(false, true))
        assertEquals(BrowserAddToHome.Route.NONE, BrowserAddToHome.route(false, false))
        assertEquals("Edited", BrowserAddToHome.cleanName(" Edited ", "Title", "https://h.example/"))
        assertEquals("Title", BrowserAddToHome.cleanName("", "Title", "https://h.example/"))
        assertEquals("h.example", BrowserAddToHome.cleanName(null, " ", "https://h.example/p"))
        assertTrue(BrowserAddToHome.say(BrowserAddToHome.Outcome.FAILED, "x", "N").startsWith("Could not add"))
        assertTrue(BrowserAddToHome.IPC_RECEIVER.startsWith(BrowserAddToHome.SUPERAPP_PKG))
    }
}

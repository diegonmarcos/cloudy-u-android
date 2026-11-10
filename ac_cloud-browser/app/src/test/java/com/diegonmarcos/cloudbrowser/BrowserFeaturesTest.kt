package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserGridRows
import com.diegonmarcos.superapp.browser.BrowserGridRow
import com.diegonmarcos.superapp.browser.BrowserSearch
import com.diegonmarcos.superapp.browser.BrowserSeed
import com.diegonmarcos.superapp.browser.BrowserSuggest
import com.diegonmarcos.superapp.browser.BrowserTab
import com.diegonmarcos.superapp.browser.BrowserTabOps
import com.diegonmarcos.superapp.browser.BrowserTabOrder
import com.diegonmarcos.superapp.browser.BrowserTabStore
import com.diegonmarcos.superapp.browser.BrowserVisit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seven features, asserted as BEHAVIOUR.
 *
 * Every assertion here was written to survive the specific false green
 * this repository keeps shipping. In particular:
 *
 *  • The default-tab tests read the SHIPPED build.json and push it
 *    through the real BrowserConfig parser. Asserting that four URLs
 *    appear somewhere in a source file would prove a string literal;
 *    this proves they arrive pinned, in order, and only once.
 *  • The engine test asserts the URL a NON-URL query resolves to.
 *    Grepping for "duckduckgo" would pass against a Qwant default, because
 *    the engine list offers duckduckgo as an alternative either way.
 *  • The pin test asserts the refusal, not the flag.
 */
class BrowserFeaturesTest {

    // ── the app's real, shipped configuration ────────────────────────

    /**
     * Gradle runs the JVM test source set with the MODULE directory as
     * its working directory, so build.json is one level up. Read rather
     * than duplicated: a copy of the four URLs in this file would keep
     * passing after someone changed the real ones.
     */
    private fun shippedConfig(): BrowserConfig {
        val f = File("../build.json")
        assertTrue("build.json not found at ${f.absolutePath}", f.exists())
        val ui = JSONObject(f.readText()).getJSONObject("ui")
        assertTrue("build.json::ui has no browser block", ui.has("browser"))
        return BrowserConfig.parse(ui.getJSONObject("browser").toString())
    }

    @Test
    fun `shipped config pins exactly the owner's seven tabs, in his order`() {
        assertEquals(
            listOf(
                "https://diegonmarcos.github.io/cloud-mobile",
                "https://diegonmarcos.github.io/linktree_icon-view",
                "https://qwant.com",
                "http://termux.app",
                "http://nix.app",
                "http://emulated.app",
                "http://cloud.app",
            ),
            shippedConfig().defaultPinnedTabs,
        )
    }

    @Test
    fun `first run pins all seven, pinned and in order`() {
        val plan = BrowserSeed.plan(
            alreadySeeded = false,
            openUrls = emptySet(),
            configUrls = shippedConfig().defaultPinnedTabs,
            now = 1_000L,
        )
        assertEquals(7, plan.size)
        assertTrue("a seeded default must be pinned", plan.all { it.pinned })
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), plan.map { it.order })
        assertEquals(shippedConfig().defaultPinnedTabs, plan.map { it.url })
        // …and they really do sort to the front of a grid that already
        // has an unpinned tab in it.
        val existing = BrowserTab("https://example.com", "e", 9_999L)
        assertEquals(
            shippedConfig().defaultPinnedTabs,
            BrowserTabOrder.sort(plan + existing).take(7).map { it.url },
        )
    }

    @Test
    fun `a closed default does not come back on the next launch`() {
        val cfg = shippedConfig().defaultPinnedTabs
        // Second launch: the flag is set. He has since closed all seven.
        val plan = BrowserSeed.plan(
            alreadySeeded = true,
            openUrls = emptySet(),
            configUrls = cfg,
            now = 2_000L,
        )
        assertTrue("defaults were resurrected after first run", plan.isEmpty())
    }

    @Test
    fun `seeding never duplicates a tab already open`() {
        val cfg = shippedConfig().defaultPinnedTabs
        val plan = BrowserSeed.plan(
            alreadySeeded = false,
            openUrls = setOf(cfg[0]),
            configUrls = cfg,
            now = 3_000L,
        )
        assertEquals(6, plan.size)
        assertFalse(plan.any { it.url == cfg[0] })
    }

    // ── pinned-tab names: a URL string or {url, title} ───────────────

    @Test
    fun `a pin parses from a plain url and from an object with a title`() {
        val cfg = BrowserConfig.parse(
            """{"default_pinned_tabs": ["https://a.example", {"url": "https://b.example", "title": "Bee"},
               {"url": "https://c.example"}, {"title": "no url"}, ""]}"""
        )
        assertEquals(listOf("https://a.example", "https://b.example", "https://c.example"), cfg.defaultPinnedTabs)
        assertEquals(mapOf("https://b.example" to "Bee"), cfg.defaultPinnedTitles)
    }

    @Test
    fun `a titled pin shows its title, not the page title`() {
        val tab = BrowserSeed.plan(false, emptySet(), listOf("http://x.example"), 1L,
            titles = mapOf("http://x.example" to "Mine")).single()
        assertEquals("Mine", tab.displayTitle)
        // The page loads and reports its own title: the name still wins.
        val loaded = BrowserTabStore.commit(listOf(tab), tab.key, "http://x.example/", "Page Title").single()
        assertEquals("Page Title", loaded.title)
        assertEquals("Mine", loaded.displayTitle)
        // …and it survives being stored and read back.
        assertEquals("Mine", BrowserTabStore.parse(BrowserTabStore.serialize(listOf(loaded))).single().displayTitle)
    }

    @Test
    fun `an untitled pin still shows the page title`() {
        val tab = BrowserSeed.plan(false, emptySet(), listOf("http://y.example"), 1L).single()
        assertEquals("", tab.customTitle)
        val loaded = BrowserTabStore.commit(listOf(tab), tab.key, "http://y.example/", "Page Title").single()
        assertEquals("Page Title", loaded.displayTitle)
    }

    @Test
    fun `the browser's four local tabs are the private names and carry their titles`() {
        val cfg = shippedConfig()
        val want = mapOf(
            "http://termux.app" to "Termux",
            "http://nix.app" to "Nix",
            "http://emulated.app" to "Local",
            "http://cloud.app" to "X-Ray Device",
        )
        assertEquals(want, cfg.defaultPinnedTitles)
        val plan = BrowserSeed.plan(false, emptySet(), cfg.defaultPinnedTabs, 1L, cfg.defaultPinnedTitles)
        assertEquals(want.values.toList(), plan.filter { it.url in want }.map { it.displayTitle })
    }

    // ── THE NEGATIVE: the library ships nobody's tabs ────────────────

    @Test
    fun `the shared library's own default pins nothing`() {
        // This is the boundary from #209. libs-browser is linked by more
        // than one app; if its default ever gains a URL, every app that
        // links it inherits somebody else's homepage on first run.
        assertTrue(
            "BrowserConfig.DEFAULT must stay empty of tabs",
            BrowserConfig.DEFAULT.defaultPinnedTabs.isEmpty(),
        )
        // An app that supplies nothing gets nothing — the path a
        // consumer takes when it does not pass a config at all.
        assertTrue(BrowserConfig.parse(null).defaultPinnedTabs.isEmpty())
        assertTrue(BrowserConfig.parse("").defaultPinnedTabs.isEmpty())
        assertTrue(BrowserConfig.parseBase64(null).defaultPinnedTabs.isEmpty())
        assertTrue(BrowserConfig.parse("{ not json").defaultPinnedTabs.isEmpty())
    }

    // ── item 7: the engine a QUERY actually reaches ──────────────────

    @Test
    fun `a non-URL entry searches DuckDuckGo`() {
        val engine = shippedConfig().defaultEngine()
        assertEquals("duckduckgo", engine.id)
        val resolved = BrowserSearch.resolve("cloud browser tabs", engine)
        assertTrue(
            "a typed query must reach DuckDuckGo, got: $resolved",
            resolved.startsWith("https://duckduckgo.com/?q="),
        )
        assertTrue(resolved.contains("cloud+browser+tabs") ||
            resolved.contains("cloud%20browser%20tabs"))
        // The negative that a grep for "duckduckgo" cannot make.
        assertFalse(resolved.contains("google"))
        assertFalse(resolved.contains("qwant"))
    }

    @Test
    fun `the alternatives are offered and switchable`() {
        val cfg = shippedConfig()
        assertEquals(listOf("qwant", "duckduckgo", "google"), cfg.engines.map { it.id })
        assertTrue(BrowserSearch.resolve("kotlin", cfg.engine("qwant"))
            .startsWith("https://www.qwant.com/?q="))
        // An unknown or dropped id falls back to the shipped default
        // rather than stranding him on a dead engine.
        assertEquals("duckduckgo", cfg.engine("bing").id)
        assertEquals("duckduckgo", cfg.engine(null).id)
    }

    @Test
    fun `a URL is navigated, not searched`() {
        val engine = shippedConfig().defaultEngine()
        assertEquals("https://qwant.com", BrowserSearch.resolve("https://qwant.com", engine))
        assertEquals("https://qwant.com", BrowserSearch.resolve("qwant.com", engine))
        // The fourth default: private host, so http and not https.
        assertEquals("http://localhost:8000", BrowserSearch.resolve("localhost:8000", engine))
        assertEquals("http://192.168.1.4/x", BrowserSearch.resolve("192.168.1.4/x", engine))
        // …and something with a space is a query even though it has a dot.
        assertTrue(BrowserSearch.resolve("what is 2.5 kg", engine)
            .startsWith("https://duckduckgo.com/?q="))
    }

    // ── item 2: the pin REFUSES the close ────────────────────────────

    @Test
    fun `a pinned tab cannot be closed by the ordinary close path`() {
        val pinned = BrowserTab("https://qwant.com", "Qwant", 1L, pinned = true)
        val loose = BrowserTab("https://example.com", "Example", 2L)
        val tabs = listOf(pinned, loose)

        assertNull("close must REFUSE a pinned tab", BrowserTabOps.close(tabs, pinned.url))

        // Unpinning is the explicit way out, and then it closes.
        val unpinned = tabs.map { if (it.url == pinned.url) it.copy(pinned = false) else it }
        val after = BrowserTabOps.close(unpinned, pinned.url)
        assertNotNull(after)
        assertEquals(listOf(loose.url), after!!.map { it.url })

        // An unpinned tab was always closable — proves the refusal above
        // is about the pin and not about close being broken outright.
        assertEquals(
            listOf(pinned.url),
            BrowserTabOps.close(tabs, loose.url)!!.map { it.url },
        )
    }

    // ── item 1: a drag that survives a restart ───────────────────────

    @Test
    fun `a drag stamps an explicit order that outlives recency`() {
        // Three tabs whose recency order is C, B, A.
        val a = BrowserTab("https://a.example", "A", ts = 1L)
        val b = BrowserTab("https://b.example", "B", ts = 2L)
        val c = BrowserTab("https://c.example", "C", ts = 3L)
        val shown = BrowserTabOrder.sort(listOf(a, b, c))
        assertEquals(listOf("C", "B", "A"), shown.map { it.title })

        // Drag the last card to the front.
        val moved = BrowserTabOrder.move(shown, 2, 0)
        assertEquals(listOf("A", "C", "B"), moved.map { it.title })

        // Every tab now carries an explicit index — this is what makes
        // it survive a process restart rather than re-sorting by ts.
        assertEquals(listOf(0, 1, 2), moved.map { it.order })

        // Re-sorting from scratch (what a relaunch does) keeps the order.
        assertEquals(listOf("A", "C", "B"), BrowserTabOrder.sort(moved).map { it.title })
        // Shuffling the stored list must not change the drawn order.
        assertEquals(
            listOf("A", "C", "B"),
            BrowserTabOrder.sort(moved.reversed()).map { it.title },
        )
    }

    @Test
    fun `a drag cannot lift an unpinned tab above a pinned one`() {
        val p = BrowserTab("https://p.example", "P", 1L, pinned = true)
        val q = BrowserTab("https://q.example", "Q", 2L)
        val shown = BrowserTabOrder.sort(listOf(p, q))
        assertEquals(listOf("P", "Q"), shown.map { it.title })

        assertFalse("pinning would mean nothing", BrowserTabOrder.canMove(shown, 1, 0))
        // …and the refusal is a no-op, not a partial move.
        assertEquals(shown, BrowserTabOrder.move(shown, 1, 0))
    }

    @Test
    fun `a drag cannot cross a group boundary`() {
        val x = BrowserTab("https://x.example", "X", 1L, group = "work")
        val y = BrowserTab("https://y.example", "Y", 2L, group = "reading")
        val shown = listOf(x, y)
        assertFalse(BrowserTabOrder.canMove(shown, 0, 1))
        assertEquals(shown, BrowserTabOrder.move(shown, 0, 1))
    }

    // ── item 3: groups are drawn, and collapse ───────────────────────

    @Test
    fun `a group draws a header and collapsing hides only its own cards`() {
        val loose = BrowserTab("https://loose.example", "Loose", 5L)
        val w1 = BrowserTab("https://w1.example", "W1", 4L, group = "work")
        val w2 = BrowserTab("https://w2.example", "W2", 3L, group = "work")
        val tabs = listOf(loose, w1, w2)

        val open = BrowserGridRows.build(tabs, emptySet())
        val header = open.filterIsInstance<BrowserGridRow.GroupHeader>().single()
        assertEquals("work", header.group)
        assertEquals(2, header.count)
        assertFalse(header.collapsed)
        assertEquals(
            listOf("Loose", "W1", "W2"),
            BrowserGridRows.visibleTabs(open).map { it.title },
        )

        val collapsed = BrowserGridRows.build(tabs, setOf("work"))
        assertTrue(collapsed.filterIsInstance<BrowserGridRow.GroupHeader>().single().collapsed)
        // The group's cards are gone; the ungrouped tab is untouched.
        assertEquals(listOf("Loose"), BrowserGridRows.visibleTabs(collapsed).map { it.title })
    }

    // ── item 6: where the dropdown's rows come from ──────────────────

    @Test
    fun `suggestions come from open tabs and local history, tabs first`() {
        val engine = shippedConfig().defaultEngine()
        val tabs = listOf(BrowserTab("https://kotlinlang.org", "Kotlin", 1L))
        val hist = listOf(BrowserVisit("https://kotlin.example/docs", "Kotlin docs", 2L))

        val out = BrowserSuggest.suggest("kotlin", tabs, hist, engine)

        assertEquals(BrowserSuggest.Source.TAB, out[0].source)
        assertEquals("https://kotlinlang.org", out[0].url)
        assertEquals(BrowserSuggest.Source.HISTORY, out[1].source)
        // The engine row is always last, so the dropdown is never a dead end.
        assertEquals(BrowserSuggest.Source.SEARCH, out.last().source)
        assertTrue(out.last().url.startsWith("https://duckduckgo.com/?q="))

        // Nothing matches locally → still exactly one row, the search.
        val none = BrowserSuggest.suggest("zzzz", tabs, hist, engine)
        assertEquals(1, none.size)
        assertEquals(BrowserSuggest.Source.SEARCH, none.single().source)

        // An empty box suggests nothing at all.
        assertTrue(BrowserSuggest.suggest("", tabs, hist, engine).isEmpty())
    }

    @Test
    fun `no suggestion source is remote`() {
        // The dropdown's rows are derived from the two lists handed in and
        // from nothing else: with both empty, the ONLY row is the local
        // engine-URL row. A remote feed would have to appear here.
        val engine = shippedConfig().defaultEngine()
        val out = BrowserSuggest.suggest("anything", emptyList(), emptyList(), engine)
        assertEquals(1, out.size)
        assertEquals(BrowserSuggest.Source.SEARCH, out.single().source)
    }
}

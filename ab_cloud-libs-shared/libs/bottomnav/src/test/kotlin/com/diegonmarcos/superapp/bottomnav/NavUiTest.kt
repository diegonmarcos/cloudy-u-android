package com.diegonmarcos.superapp.bottomnav

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The nav UI declaration (NavUi.kt): Classic / Clean, the Clean drawer's rows, the transparent first row, scroll-hide. Plain JVM, no
 * device: the chrome follows [NavUiDecl.plan] and [chromeScrollStep], so these are the rules the bars obey.
 */
class NavUiTest {
    private val d = ChromeScrollState.Default
    private val h = ChromeScrollState.Hidden
    private val s = ChromeScrollState.Shrunk

    private fun section(id: String, vararg pages: NavPage) = NavSection(id, id.uppercase(), "home", pages.toList())

    // ── Clean removes the second row and adds the drawer rows ──

    @Test fun `Clean has no second row, a hamburger, a bottom nav without labels and the three drawer rows in order`() {
        val p = NavUiDecl.plan(NavUiMode.Clean)
        assertFalse("the second row is removed", p.secondRow)
        assertTrue("the top-left hamburger", p.hamburger)
        assertFalse("icons only", p.bottomLabels)
        assertEquals(listOf(DrawerRow.PageDropdown, DrawerRow.Actions, DrawerRow.SubPageDropdown), p.drawerRows)
        assertEquals(listOf(NavQuickAction.Ai, NavQuickAction.Profile, NavQuickAction.Settings), p.drawerActions)
    }

    @Test fun `Classic keeps the second row and the labels and has no drawer`() {
        val p = NavUiDecl.plan(NavUiMode.Classic)
        assertTrue(p.secondRow)
        assertFalse(p.hamburger)
        assertTrue(p.bottomLabels)
        assertTrue(p.drawerRows.isEmpty())
        assertTrue(p.drawerActions.isEmpty())
    }

    @Test fun `the Clean drawer lists the pages, the icons and the child pages of the open page`() {
        val notes = section("pages", NavPage("all", "All", "description"), NavPage("tags", "Tags", "bookmark"), NavPage("new", "New page", "edit", action = "launch"))
        val bottom = listOf(section("home"), notes, section("search"))
        val m = cleanDrawerModel(bottom, notes, "tags")
        assertEquals("row 1: the page name", "PAGES", m.pageName)
        assertEquals(listOf("home", "pages", "search"), m.pages.map { it.id })
        assertEquals(listOf(false, true, false), m.pages.map { it.selected })
        assertEquals("row 2", listOf(NavQuickAction.Ai, NavQuickAction.Profile, NavQuickAction.Settings), m.actions)
        assertEquals("row 3: a launch tab is not a destination", listOf("all", "tags"), m.subPages.map { it.id })
        assertEquals("the face is the open child page", "Tags", m.subPageName)
        assertEquals(NavUiDecl.CLEAN_DRAWER_ROWS, m.rows)
    }

    @Test fun `a page with no child pages has no third row`() {
        val m = cleanDrawerModel(listOf(section("home"), section("mail")), section("home"), null)
        assertTrue(m.subPages.isEmpty())
        assertEquals(listOf(DrawerRow.PageDropdown, DrawerRow.Actions), m.rows)
    }

    @Test fun `nested child pages are indented by depth`() {
        val s1 = section("me", NavPage("proj", "Proj", "folder", pages = listOf(NavPage("gym", "Gym", "fitness"))))
        val m = cleanDrawerModel(listOf(s1), s1, "gym")
        assertEquals(listOf(0, 1), m.subPages.map { it.depth })
        assertEquals("Gym", m.subPageName)
    }

    // ── #1369 the app's actions in the drawer (nav_ui.actions = "drawer") ──

    private val notesLike = listOf(
        section("home"),
        section("pages", NavPage("all", "All", "description"), NavPage("new", "New page", "edit", action = "launch")),
        section("canvas", NavPage("boards", "Boards", "dashboard"), NavPage("new", "New board", "add", action = "launch")),
    )

    @Test fun `with actions in the drawer every launch page of every section is the AppActions row, after the icons`() {
        val m = cleanDrawerModel(notesLike, notesLike[1], "all", actionsInDrawer = true)
        assertEquals(listOf("pages/new", "canvas/new"), m.appActions.map { it.id })
        assertEquals(listOf("New page", "New board"), m.appActions.map { it.label })
        assertEquals(listOf(DrawerRow.PageDropdown, DrawerRow.Actions, DrawerRow.AppActions, DrawerRow.SubPageDropdown), m.rows)
        assertEquals("a launch page is still no sub-page", listOf("all"), m.subPages.map { it.id })
    }

    @Test fun `without the declaration the drawer has no AppActions row`() {
        val m = cleanDrawerModel(notesLike, notesLike[1], "all")
        assertTrue(m.appActions.isEmpty())
        assertFalse(DrawerRow.AppActions in m.rows)
    }

    @Test fun `with actions in the drawer the strips carry no launch page, otherwise they keep them`() {
        val drawer = NavUiSettings(NavUiMode.Clean, true, NavActionsPlace.Drawer)
        assertEquals(listOf("all"), NavUiDecl.stripPages(drawer, notesLike[1].pages).map { it.id })
        assertEquals(listOf("all", "new"), NavUiDecl.stripPages(NavUiSettings(NavUiMode.Clean), notesLike[1].pages).map { it.id })
        assertEquals("Classic has no Clean drawer: the strip keeps them", listOf("all", "new"),
            NavUiDecl.stripPages(NavUiSettings(NavUiMode.Classic, true, NavActionsPlace.Drawer), notesLike[1].pages).map { it.id })
    }

    @Test fun `nav_ui actions is parsed, kept over the user's mode choice, and an action id names its section and page`() {
        val parsed = NavUiDecl.parse("""{"mode": "clean", "scroll_hide": true, "actions": "drawer"}""")
        assertEquals(NavActionsPlace.Drawer, parsed.actions)
        assertEquals(NavActionsPlace.Strip, NavUiDecl.parse("""{"mode": "clean"}""").actions)
        assertTrue(resolveNavUi(parsed, MemoryNavUiStore(NavUiMode.Classic, false)).actionsInDrawer)
        assertEquals("canvas" to "new", appActionTarget(appActionId("canvas", "new")))
        assertNull(appActionTarget("broken"))
    }

    // ── #1369 Obsidian mobile's history bar (nav_ui.bottom = "history") ──

    @Test fun `nav_ui bottom history is parsed, kept over the user's choice, and drawn only in Clean`() {
        val parsed = NavUiDecl.parse("""{"mode": "clean", "bottom": "history"}""")
        assertEquals(NavBottomKind.History, parsed.bottom)
        assertTrue(parsed.historyBar)
        assertEquals(NavBottomKind.Sections, NavUiDecl.parse("""{"mode": "clean"}""").bottom)
        assertFalse(NavUiDecl.parse("""{"mode": "clean"}""").historyBar)
        assertTrue(resolveNavUi(parsed, MemoryNavUiStore(NavUiMode.Clean, true)).historyBar)
        assertFalse("Classic keeps the sections", resolveNavUi(parsed, MemoryNavUiStore(NavUiMode.Classic, true)).historyBar)
    }

    @Test fun `the history bar holds Back, Forward, New, Tabs and Menu in Obsidian's order`() {
        assertEquals(listOf("Back", "Forward", "New", "Tabs", "Menu"), HistoryBarItem.entries.map { it.label })
        assertEquals(HistoryBarItem.Tabs, HistoryBarItem.of("history_tabs"))
        assertNull(HistoryBarItem.of("home"))
    }

    // ── the first row is transparent in every mode ──

    @Test fun `the first row has no fill in any mode`() {
        for (mode in NavUiMode.entries) assertTrue("$mode", NavUiDecl.plan(mode).topRowTransparent)
        assertEquals(0f, NavTokens().topRowFill.alpha, 0f)
    }

    @Test fun `a grey first row would be seen`() {
        val grey = NavTokens(topRowFill = androidx.compose.ui.graphics.Color(0xFF303030))
        for (mode in NavUiMode.entries) assertFalse("$mode", NavUiDecl.plan(mode, grey).topRowTransparent)
    }

    @Test fun `Clean's bottom nav is smaller than a Classic cell and its icon fits`() {
        val t = NavTokens()
        assertTrue(t.cleanBarHeight < androidx.compose.ui.unit.Dp(64f))
        assertTrue(t.cleanCellHeight <= t.cleanBarHeight)
        assertTrue(t.cleanIconSize <= t.cleanCellHeight)
    }

    // ── scroll-hide: down hides ALL bars, up restores ──

    @Test fun `scroll-hide, a downward scroll or drag hides everything, from any state`() {
        for (from in listOf(d, h, s)) for (drag in listOf(true, false)) assertEquals(h, chromeScrollStep(ChromeScrollPolicy.Hide, from, 10f, drag))
    }

    @Test fun `scroll-hide, the release and the fling do not bring the bars back`() {
        assertEquals(h, chromeScrollReleaseStep(ChromeScrollPolicy.Hide, h))
        assertEquals("the settle after the release", h, chromeScrollStep(ChromeScrollPolicy.Hide, h, 40f, dragging = false))
        assertEquals("the older machine would shrink", s, chromeScrollReleaseStep(ChromeScrollPolicy.Shrink, h))
    }

    @Test fun `scroll-hide, any upward scroll restores everything`() {
        for (from in listOf(d, h, s)) for (drag in listOf(true, false)) assertEquals(d, chromeScrollStep(ChromeScrollPolicy.Hide, from, -5f, drag))
    }

    @Test fun `scroll-hide, no movement changes nothing`() {
        for (from in listOf(d, h, s)) assertEquals(from, chromeScrollStep(ChromeScrollPolicy.Hide, from, 0f, true))
    }

    @Test fun `scroll-hide off pins the bars`() {
        for (from in listOf(d, h, s)) {
            assertEquals(d, chromeScrollStep(ChromeScrollPolicy.Pinned, from, 10f, true))
            assertEquals(d, chromeScrollReleaseStep(ChromeScrollPolicy.Pinned, from))
        }
    }

    @Test fun `the connection follows its policy end to end`() {
        val c = BottomNavCollapse()
        c.policy = ChromeScrollPolicy.Hide
        c.onDragDelta(12f)
        assertEquals(h, c.state); assertTrue(c.hidden)
        c.onRelease()
        assertEquals("stays hidden after the finger lifts", h, c.state)
        c.onScrollDelta(3f)
        assertEquals(h, c.state)
        c.onScrollDelta(-3f)
        assertEquals("an upward scroll reveals", d, c.state); assertFalse(c.collapsed)
        c.onDragDelta(5f); c.restore()
        assertEquals(d, c.state)
        c.policy = ChromeScrollPolicy.Pinned
        c.onDragDelta(50f)
        assertEquals("pinned never moves", d, c.state)
    }

    @Test fun `pinning a hidden chrome brings it back at once`() {
        val c = BottomNavCollapse()
        c.policy = ChromeScrollPolicy.Hide
        c.onDragDelta(9f)
        c.policy = ChromeScrollPolicy.Pinned
        assertEquals(d, c.state)
    }

    @Test fun `a bare connection keeps the older shrink machine`() {
        assertEquals(ChromeScrollPolicy.Shrink, BottomNavCollapse().policy)
    }

    // ── the settings: the declaration's default, the user's choice over it ──

    @Test fun `scroll-hide maps to the hide policy and its absence to pinned`() {
        assertEquals(ChromeScrollPolicy.Hide, NavUiSettings(scrollHide = true).scrollPolicy())
        assertEquals(ChromeScrollPolicy.Pinned, NavUiSettings(scrollHide = false).scrollPolicy())
    }

    @Test fun `an app that declares nothing wears Classic with scroll-hide on`() {
        assertEquals(NavUiSettings(NavUiMode.Classic, true), NavUiDecl.DEFAULT)
        assertEquals(NavUiDecl.DEFAULT, NavUiDecl.parse("not json"))
        assertEquals(NavUiDecl.DEFAULT, NavUiDecl.fromBuildConfig(""))
    }

    @Test fun `Cloud Notes declaration ui nav_ui reads as Clean`() {
        val json = """{"mode": "clean", "scroll_hide": true}"""
        assertEquals(NavUiSettings(NavUiMode.Clean, true), NavUiDecl.parse(json))
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray())
        assertEquals(NavUiMode.Clean, NavUiDecl.fromBuildConfig(b64).mode)
    }

    @Test fun `an unknown mode falls back to the default rather than crash`() {
        assertEquals(NavUiMode.Classic, NavUiDecl.parse("""{"mode": "neon"}""").mode)
        assertEquals(false, NavUiDecl.parse("""{"scroll_hide": false}""").scrollHide)
        assertNull(NavUiMode.of("neon"))
        assertEquals(NavUiMode.Classic, NavUiMode.of(""))
    }

    @Test fun `the user's choice wins over the declared default and clearing it restores the default`() {
        val declared = NavUiSettings(NavUiMode.Clean, true)
        val store = MemoryNavUiStore()
        assertEquals(declared, resolveNavUi(declared, store))
        store.setMode(NavUiMode.Classic); store.setScrollHide(false)
        assertEquals(NavUiSettings(NavUiMode.Classic, false), resolveNavUi(declared, store))
        store.setMode(null); store.setScrollHide(null)
        assertEquals(declared, resolveNavUi(declared, store))
        assertEquals(declared, resolveNavUi(declared, null))
    }

    // ── per-gesture scroll-hide: the touch that hid the bars cannot bring them back, only a new one can ──

    private val hide = ChromeScrollPolicy.Hide

    @Test fun `gesture step, a same-gesture reversal does not restore`() {
        val hid = chromeGestureStep(hide, d, 30f, dragging = true, gesture = 4, latch = NO_GESTURE)
        assertEquals(ChromeGestureStep(h, 4), hid)
        assertEquals("finger still held, scrolling back up", ChromeGestureStep(h, 4), chromeGestureStep(hide, hid.state, -30f, true, 4, hid.latch))
        assertEquals("that gesture's fling, upward", ChromeGestureStep(h, 4), chromeGestureStep(hide, h, -80f, false, 4, 4))
    }

    @Test fun `gesture step, release then a new upward gesture restores`() {
        assertEquals(ChromeGestureStep(d, NO_GESTURE), chromeGestureStep(hide, h, -5f, true, gesture = 5, latch = 4))
    }

    @Test fun `gesture step, a new downward gesture keeps the bars hidden and takes the latch`() {
        assertEquals(ChromeGestureStep(h, 5), chromeGestureStep(hide, h, 12f, true, gesture = 5, latch = 4))
        assertEquals("and its own reversal cannot restore either", ChromeGestureStep(h, 5), chromeGestureStep(hide, h, -12f, true, 5, 5))
    }

    @Test fun `gesture step, other policies keep no latch`() {
        assertEquals(ChromeGestureStep(d, NO_GESTURE), chromeGestureStep(ChromeScrollPolicy.Shrink, h, -5f, true, 4, 4))
        assertEquals(ChromeGestureStep(h, NO_GESTURE), chromeGestureStep(ChromeScrollPolicy.Shrink, d, 5f, true, 4, NO_GESTURE))
        assertEquals(ChromeGestureStep(d, NO_GESTURE), chromeGestureStep(ChromeScrollPolicy.Pinned, d, 5f, true, 4, NO_GESTURE))
    }

    @Test fun `connection with a watched finger, same touch reversal stays hidden, a new touch up restores`() {
        val c = BottomNavCollapse().apply { policy = hide }
        c.onFinger(true); c.onDragDelta(40f)
        assertEquals(h, c.state)
        c.onGestureUp(); c.onScrollDelta(-40f)
        assertEquals("held finger scrolled back up", h, c.state)
        c.onFinger(false); c.onRelease(); c.onScrollDelta(-60f)
        assertEquals("the fling of the same gesture", h, c.state)
        c.onFinger(true); c.onDragDelta(25f)
        assertEquals("a new downward gesture keeps them hidden", h, c.state)
        c.onFinger(false); c.onRelease()
        c.onFinger(true); c.onGestureUp()
        assertEquals("a new upward gesture restores", d, c.state)
    }

    @Test fun `connection through nested scroll, the same drag reversed stays hidden, the next drag up restores`() {
        val c = BottomNavCollapse().apply { policy = hide }
        val finger = androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
        val fling = androidx.compose.ui.input.nestedscroll.NestedScrollSource.Fling
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, -30f), finger)
        assertEquals(h, c.state)
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, 30f), finger)
        assertEquals("same drag, back up", h, c.state)
        c.onRelease() // what onPreFling does when the finger lifts and throws
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, 30f), fling)
        assertEquals("its fling, up", h, c.state)
        c.onPreScroll(androidx.compose.ui.geometry.Offset(0f, 30f), finger)
        assertEquals("a new drag up", d, c.state)
    }

    @Test fun `a programmatic restore clears the latch`() {
        val c = BottomNavCollapse().apply { policy = hide }
        c.onFinger(true); c.onDragDelta(40f); c.restore()
        assertEquals(d, c.state)
        c.onDragDelta(10f); c.onGestureUp()
        assertEquals("the same held touch hid them again, so it still cannot restore", h, c.state)
    }
}

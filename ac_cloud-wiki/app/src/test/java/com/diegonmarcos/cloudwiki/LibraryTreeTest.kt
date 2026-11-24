package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.bottomnav.ChromeScrollState
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SideMenuPresence
import com.diegonmarcos.superapp.bottomnav.sideMenuPresence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * #1891 the wiki's own side menu and Home, against the real declaration (build.json::ui) and the real dataset: the menu ignores the page's
 * scroll and takes 75% of the width; Home is first; Filter, Expand / Collapse by level and Search (ancestors kept) work on the tree; Home's
 * Table, Graph and 3D cover every node; and nothing of the previous menu is lost (test/wiki-1891-before.json, frozen, rule 16).
 */
class LibraryTreeTest {
    private val sectionsJson: String by lazy { JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONArray("sections").toString() }
    private val declared: NavSection by lazy { NavDecl.parse(sectionsJson).section("wiki")!! }
    private val chronos: ChronosTree by lazy { Chronos.parse(sectionsJson) }
    /** What WikiShell builds: the declared section with Chronos's simulators stripped (#1831), Home first. */
    private val wiki: NavSection by lazy { LibraryTree.withHome(withChronos(declared, chronos), "Home") }
    private val menu: WikiTree by lazy { LibraryTree.build(wiki, Data.wiki) }
    private val home: WikiTree by lazy { LibraryTree.build(wiki, Data.wiki, chronos, home = false) }
    private val before: JSONObject by lazy { JSONObject(File("../test/wiki-1891-before.json").readText()) }

    private fun ids(a: org.json.JSONArray, key: String? = null): List<String> =
        (0 until a.length()).map { if (key == null) a.getString(it) else a.getJSONObject(it).getString(key) }

    // ── (1) the menu's own scroll: the page's scroll never takes it away ──────────────────────────────────────────────────────────────

    @Test fun theMenuIgnoresThePageScroll() {
        for (s in ChromeScrollState.entries) {
            assertEquals("open stays open while the page is $s", WikiMenu.Presence.Open, WikiMenu.presence(true, s))
            assertEquals("closed stays the rail while the page is $s", WikiMenu.Presence.Rail, WikiMenu.presence(false, s))
        }
        // the old menu (libs:ui-nav-bars's, which the shell drew before) followed the chrome: scrolling the page took it away
        assertEquals(SideMenuPresence.Away, sideMenuPresence(true, ChromeScrollState.Hidden))
        assertEquals(SideMenuPresence.Rail, sideMenuPresence(true, ChromeScrollState.Shrunk))
    }

    @Test fun theShellDrawsTheWikisOwnMenuWithItsOwnScroll() {
        val shell = File("src/main/java/com/diegonmarcos/cloudwiki/ui/WikiShell.kt").readText()
        val side = File("src/main/java/com/diegonmarcos/cloudwiki/ui/WikiSideMenu.kt").readText()
        assertTrue("the side-menu mode draws WikiSideLayout", "WikiSideLayout(" in shell)
        assertTrue("the menu list does not take the shared nested scroll", "userScrollEnabled = false" in side && ".ownScroll(list)" in side)
        assertTrue("the menu reads its presence from WikiMenu.presence", "WikiMenu.presence(" in side)
        assertTrue("the menu is WIDTH_FRACTION of the width", "maxWidth * WikiMenu.WIDTH_FRACTION" in side)
    }

    // ── (3) 75% of the width ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun theMenuTakesThreeQuartersOfTheWidth() {
        assertEquals(0.75f, WikiMenu.WIDTH_FRACTION)
        assertEquals(300f, WikiMenu.width(400f))
        assertEquals(810f, WikiMenu.width(1080f))
    }

    // ── (4) Home first ───────────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun homeIsTheFirstParentAtTheLevelOfTheOthers() {
        assertEquals(LibraryTree.HOME, wiki.pages.first().id)
        assertEquals(listOf("home", "modern", "arcanum", "projects"), wiki.groups.map { it.id })
        assertEquals(listOf("home", "group:modern", "group:arcanum", "group:projects"), menu.roots.map { it.id })
        assertTrue("every parent is level 0", menu.roots.all { it.level == 0 })
        assertEquals("Home opens itself", LibraryTree.HOME, menu.node("home")!!.page)
        assertEquals("idempotent", wiki, LibraryTree.withHome(wiki, "Home"))
        // the old shell's section had no Home
        assertNotEquals(LibraryTree.HOME, withChronos(declared, chronos).pages.first().id)
        assertTrue("the menu opens on Home", MenuState().let { WikiMenu.rows(menu, it).first().node.id == "home" })
    }

    // ── (2) Filter, Expand / Collapse by level, Search ───────────────────────────────────────────────────────────────────────────────

    @Test fun theFilterHidesModernOrArcanumAndKeepsHomeAndProjects() {
        val all = MenuState(expanded = WikiMenu.expandAll(menu))
        val noModern = WikiMenu.toggleFilter(all, "modern")
        val rows = WikiMenu.rows(menu, noModern)
        assertTrue(rows.none { it.node.group == "modern" })
        assertTrue(rows.any { it.node.group == "arcanum" } && rows.any { it.node.id == "home" } && rows.any { it.node.group == "projects" })
        val neither = WikiMenu.toggleFilter(noModern, "arcanum")
        assertEquals(listOf("home", "projects"), WikiMenu.rows(menu, neither).map { it.node.group }.distinct())
        assertEquals("Home cannot be filtered out", neither, WikiMenu.toggleFilter(neither, "home"))
        assertEquals("Projects cannot be filtered out", neither, WikiMenu.toggleFilter(neither, "projects"))
        assertEquals("toggling back restores", WikiMenu.rows(menu, all), WikiMenu.rows(menu, WikiMenu.toggleFilter(WikiMenu.toggleFilter(neither, "arcanum"), "modern")))
    }

    @Test fun expandAndCollapseByLevel() {
        fun shown(e: Set<String>) = WikiMenu.rows(menu, MenuState(expanded = e))
        assertEquals("collapsed: the parents only", menu.roots.map { it.id }, shown(WikiMenu.collapseAll()).map { it.node.id })
        val one = shown(WikiMenu.expandLevels(menu, 1))
        assertEquals(1, one.maxOf { it.node.level })
        assertEquals(menu.nodes.filter { it.level <= 1 }.map { it.id }, one.map { it.node.id })
        val two = shown(WikiMenu.expandLevels(menu, 2))
        assertEquals(2, two.maxOf { it.node.level })
        assertEquals(menu.nodes.filter { it.level <= 2 }.map { it.id }, two.map { it.node.id })
        assertTrue("the topics show at 2 levels", two.any { it.node.id == "math" } && two.none { it.node.id.startsWith("math.") })
        val all = WikiMenu.expandAll(menu)
        assertEquals("Expand All shows every node", menu.nodes.map { it.id }, shown(all).map { it.node.id })
        assertTrue("the dataset pages are below their topics", menu.maxLevel >= 3)
        val c1 = WikiMenu.collapseLevels(menu, all, 1)
        assertEquals(menu.maxLevel - 1, shown(c1).maxOf { it.node.level })
        val c2 = WikiMenu.collapseLevels(menu, all, 2)
        assertEquals(menu.maxLevel - 2, shown(c2).maxOf { it.node.level })
        assertEquals("collapse 2 levels of 2 levels open: the parents", menu.roots.map { it.id },
            shown(WikiMenu.collapseLevels(menu, WikiMenu.expandLevels(menu, 2), 2)).map { it.node.id })
        assertEquals(WikiMenu.expandLevels(menu, 1), WikiMenu.collapseLevels(menu, WikiMenu.expandLevels(menu, 2), 1))
        assertEquals("a toggle opens one node", setOf("group:modern"), WikiMenu.toggle(MenuState(), "group:modern").expanded)
    }

    @Test fun searchKeepsTheAncestorsAndMarksTheMatches() {
        val math = WikiMenu.rows(menu, MenuState(query = "math"))
        val ids = math.map { it.node.id }
        assertTrue(ids.containsAll(listOf("group:modern", "sciences", "math")))
        assertNotNull("Math is highlighted", math.first { it.node.id == "math" }.match)
        assertEquals(0 until 4, math.first { it.node.id == "math" }.match)
        assertEquals("an ancestor is not highlighted", null, math.first { it.node.id == "sciences" }.match)
        for (r in math) assertTrue("${r.node.id} is a match or above one", r.match != null || math.any { m -> m.match != null && r.node.id in menu.ancestors(m.node.id) })
        assertEquals("case-insensitive", ids, WikiMenu.rows(menu, MenuState(query = "MATH")).map { it.node.id })
        // a dataset page (a topic's tab) is found with its topic, hub and parent, even when everything is collapsed
        val pg = Data.wiki.topic("eng")!!.pages.first()
        val hit = WikiMenu.rows(menu, MenuState(query = pg.title)).map { it.node.id }
        assertTrue(hit.containsAll(listOf("group:modern", "sciences", "eng", pg.id)))
        assertTrue("nothing that does not match", WikiMenu.rows(menu, MenuState(query = "zzqqxx")).isEmpty())
        // the filter still applies to a search
        assertTrue(WikiMenu.rows(menu, MenuState(query = "math", hidden = setOf("modern"))).none { it.node.group == "modern" })
    }

    // ── rule 16: everything of the previous menu stays reachable ─────────────────────────────────────────────────────────────────────

    @Test fun everyRowOfThePreviousMenuIsStillReachable() {
        val shown = WikiMenu.rows(menu, MenuState(expanded = WikiMenu.expandAll(menu))).associateBy { it.node.id }
        val old = before.getJSONArray("menu")
        assertEquals(36, old.length())
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            val n = shown[o.getString("id")]?.node
            assertNotNull("old menu row ${o.getString("id")} is in the new menu", n)
            assertEquals("same name", o.getString("label"), n!!.label)
            if (!o.getString("id").startsWith(LibraryTree.GROUP)) assertEquals("${n.id} opens its page", n.id, n.page)
            // Home joined the parents at level 0; every old row keeps its level
            assertEquals("${n.id} sits where it sat", o.getInt("depth"), n.level)
        }
        for (id in ids(before.getJSONArray("pages"))) {
            val n = menu.node(id)
            assertNotNull("dataset page $id is in the menu", n)
            assertEquals(id, n!!.tab)
        }
        for (id in ids(before.getJSONArray("simulators"))) assertNotNull("simulator $id is in Home", home.node(id))
    }

    // ── (5)(6) Home: List, Table, Graph and 3D cover the whole library ───────────────────────────────────────────────────────────────

    @Test fun homeIndexesTheWholeLibrary() {
        assertEquals(listOf("group:modern", "group:arcanum", "group:projects"), home.roots.map { it.id })
        assertTrue("Home is not in its own index", home.node("home") == null)
        val menuIds = menu.nodes.map { it.id }.filter { it != "home" }
        assertTrue("every menu node is indexed", home.nodes.map { it.id }.containsAll(menuIds))
        assertEquals("plus the simulators", (menuIds + ids(before.getJSONArray("simulators"))).toSet(), home.nodes.map { it.id }.toSet())
        assertEquals("unique ids", home.nodes.size, home.nodes.map { it.id }.toSet().size)
        for (n in home.nodes) for (c in n.children) assertEquals("$c's parent", n.id, home.node(c)!!.parent)
    }

    @Test fun tableRowsAreTheTreesNodes() {
        val rows = HomeIndex.rows(home)
        assertEquals(home.nodes.map { it.id }, rows.map { it.id })
        val math = rows.first { it.id == "math" }
        assertEquals("Modern Science / Sciences", math.parentPath)
        assertEquals(2, math.level)
        assertEquals(Data.wiki.topic("math")!!.pages.size, math.children)
        val pg = Data.wiki.topic("math")!!.pages.first()
        val r = rows.first { it.id == pg.id }
        assertEquals(pg.title, r.title)
        assertEquals(pg.tags, r.tags)
        assertEquals(pg.md.length, r.chars)
        assertTrue(r.words > 5)
        assertEquals("Modern Science / Sciences / Math", r.parentPath)
        // sorting reorders, never drops
        for (c in HomeIndex.Column.entries) for (asc in listOf(true, false)) assertEquals(rows.map { it.id }.toSet(), HomeIndex.sorted(rows, c, asc).map { it.id }.toSet())
        assertEquals(rows.maxOf { it.words }, HomeIndex.sorted(rows, HomeIndex.Column.Words, false).first().words)
        assertEquals("1.4k", HomeIndex.size(1400))
    }

    @Test fun theGraphLinksEveryParentAndChild() {
        val nodes = HomeIndex.graphNodes(home)
        val edges = HomeIndex.graphEdges(home)
        assertEquals(home.nodes.map { it.id }, nodes.map { it.id })
        assertEquals("one edge per non-root node", home.nodes.size - home.roots.size, edges.size)
        for (n in home.nodes.filter { it.parent != null }) assertTrue(edges.any { it.a == n.parent && it.b == n.id })
    }

    @Test fun thePlanetaryLayoutPlacesEveryNodeOnItsParentsOrbit() {
        for (t in listOf(0.0, 7.5)) {
            val bodies = Planetary.layout(home, t)
            assertEquals(home.nodes.map { it.id }.toSet(), bodies.map { it.id }.toSet())
            assertEquals(home.nodes.size, bodies.size)
            val by = bodies.associateBy { it.id }
            for (b in bodies) {
                assertTrue(b.x.isFinite() && b.y.isFinite() && b.z.isFinite())
                val (px, py, pz) = b.parent?.let { by.getValue(it) }?.let { Triple(it.x, it.y, it.z) } ?: Triple(0.0, 0.0, 0.0)
                val d = sqrt((b.x - px) * (b.x - px) + (b.y - py) * (b.y - py) + (b.z - pz) * (b.z - pz))
                assertTrue("${b.id} is on its orbit", abs(d - b.orbit) < 1e-6)
                assertTrue("${b.id}'s orbit clears its parent", b.parent == null || b.orbit > by.getValue(b.parent!!).radius)
            }
        }
        assertEquals("deterministic", Planetary.layout(home, 3.0), Planetary.layout(home, 3.0))
        assertFalse("the orbits turn", Planetary.layout(home, 0.0) == Planetary.layout(home, 5.0))
        val cam = PlanetCamera(zoom = 1.2, cx = 500.0, cy = 500.0)
        val bodies = Planetary.layout(home, 0.0)
        val root = bodies.first { it.level == 0 }
        val (sx, sy) = cam.project(root.x, root.y, root.z)
        assertEquals("a tap on a parent's centre opens it", root.id, cam.pick(bodies, sx, sy, slop = 0.0))
        assertEquals("a tap on empty space opens nothing", null, cam.pick(bodies, -5000.0, -5000.0))
    }
}

package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * #1298 Lnktree > Proj (the registry outline as a tree) and #1299 (Profile renamed Lnktree, the default page).
 * Reads the REAL declarations: build.json, data/ui, and libs:repos' repos.outline.json with its shipped copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepoTreeTest {
    private val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui")
    private val outlineText = BuroLlcTestData.text("data/repos/repos.outline.json")
    private val outline = RepoOutline.parse(outlineText)
    private val config = RepoTreeConfig.parse(ui.getJSONObject("repo_tree"))
    private val lnktree = ui.getJSONArray("sections").objects().first { it.getString("id") == "lnktree" }

    private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
    private fun node(roots: List<RepoNode>, label: String) = roots.first { it.label == label }

    // ---- outline parse

    @Test fun `the shipped outline is byte-equal to libs repos' and parses into the registry areas`() {
        assertEquals("ac_cloud-me/data/repos is a copy of libs:repos (sync.sh --refresh-outline)",
            BuroLlcTestData.text("ab_cloud-libs-shared/libs/repos/repos.outline.json"), outlineText)
        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), outline.map { it.code })
        assertEquals(listOf("Unix", "CG", "Capital", "HardTech", "42", "Projects", "Others", "Archived"), outline.map { it.label })
        assertTrue(outline.all { it.kind == "area" })
    }

    @Test fun `an area holds projects, sub-categories, families and repo leaves with codes, urls and visibility`() {
        val unix = outline.first { it.code == "1" }
        assertEquals(listOf("Cloudy", "Cloud-Me"), unix.children.map { it.label })
        val cloudy = node(unix.children, "Cloudy")
        assertEquals("project", cloudy.kind)
        assertTrue(cloudy.children.map { it.label }.containsAll(listOf("Android", "C3")))
        val android = node(cloudy.children, "Android")
        val leaf = android.children.single().repo
        assertNotNull(leaf)
        assertEquals("cloudy-u-android", leaf!!.name)
        assertTrue(leaf.url.startsWith("https://github.com/diegonmarcos/"))
        assertEquals("${android.code}.1", leaf.code)
        val cloudMe = node(unix.children, "Cloud-Me")
        assertEquals(listOf("Pub", "Pvt"), cloudMe.children.map { it.label })
        assertTrue(cloudMe.children.all { it.kind == "subcategory" })
        assertTrue("a private registry repo is locked", outline.flatMap { it.leaves() }.any { it.private })
        assertTrue("and a public one is not", outline.flatMap { it.leaves() }.any { !it.private })
    }

    @Test fun `a wrong schema or malformed text is an empty outline, never a crash`() {
        assertTrue(RepoOutline.parse("{}").isEmpty())
        assertTrue(RepoOutline.parse("not json").isEmpty())
    }

    // ---- tree model

    @Test fun `rows show the roots and open containers only, and toggle opens and closes one level`() {
        val roots = config.area(outline, "1")!!.children
        val m = RepoTreeModel()
        assertEquals(listOf("Cloudy", "Cloud-Me"), m.rows(roots).map { it.node.label })
        m.toggle("1.1")
        val open = m.rows(roots)
        assertEquals("Cloudy", open[0].node.label); assertTrue(open[0].open)
        assertEquals(1, open[1].depth)
        assertTrue(open.any { it.node.label == "Android" })
        assertFalse("a closed family hides its repos", open.any { it.node.repo != null })
        m.toggle("1.1")
        assertEquals(2, m.rows(roots).size)
    }

    @Test fun `expand all reaches every leaf at every level and collapse all folds back to the roots`() {
        val roots = config.area(outline, "1")!!.children
        val m = RepoTreeModel()
        m.expandAll(roots)
        val leaves = m.rows(roots).mapNotNull { it.node.repo }
        assertEquals(roots.flatMap { it.leaves() }, leaves)
        assertTrue(m.rows(roots).any { it.depth == 3 && it.node.repo != null })  // Cloud-Me > Pub > Profiles > repo
        m.collapseAll(roots)
        assertEquals(roots.size, m.rows(roots).size)
    }

    @Test fun `collapse all of one area leaves another area's open nodes alone`() {
        val m = RepoTreeModel(setOf("2", "2.1"))
        m.collapseAll(config.area(outline, "1")!!.children)
        assertEquals(setOf("2", "2.1"), m.expanded)
    }

    @Test fun `the expansion state is persisted and reloaded`() {
        val ctx = RuntimeEnvironment.getApplication()
        val store = RepoTreeStore(ctx)
        val m = RepoTreeModel(store.load())
        assertTrue(m.expanded.isEmpty())
        m.toggle("1.2"); m.toggle("1.2.1"); store.save(m.expanded)
        val again = RepoTreeModel(RepoTreeStore(ctx).load())
        assertEquals(setOf("1.2", "1.2.1"), again.expanded)
        assertTrue(again.rows(config.area(outline, "1")!!.children).any { it.node.label == "Profiles" })
    }

    // ---- area filter and leaf action

    @Test fun `the area filter shows one area's projects and the full tree omits Others and Archived`() {
        assertEquals(listOf("Galaxy", "Zirkus"), config.area(outline, "2")!!.children.map { it.label })
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), config.shown(outline).map { it.code })
        assertEquals("the registry names, unrenamed", "Projects", config.area(outline, "6")!!.label)
        assertEquals("42", config.area(outline, "5")!!.label)
        assertEquals(null, config.area(outline, "8"))
    }

    @Test fun `a leaf opens its fleet app when one is built from it, else its GitHub url`() {
        val leaf = outline.flatMap { it.leaves() }.first()
        assertEquals(RepoTarget.Url(leaf.url), config.target(leaf))
        val withApp = config.copy(fleetApps = mapOf(leaf.url to "com.example.app"))
        assertEquals(RepoTarget.App("com.example.app", leaf.url), withApp.target(leaf))
    }

    // ---- guards

    @Test fun `the Proj row sits below Pers and its areas are the registry areas in registry order`() {
        val groups = lnktree.getJSONArray("groups").objects()
        assertEquals(listOf("prof", "pers", "proj"), groups.map { it.getString("id") })
        val proj = groups.last()
        assertEquals("Proj", proj.getString("label"))
        assertEquals(config.group, proj.getString("id"))
        val rowPages = strings(proj.getJSONArray("pages"))
        assertEquals("every Proj chip is a declared area and nothing else", config.areas.map { it.first }, rowPages)
        assertEquals("the Proj areas == the registry areas flagged show_in lnktree, in registry order",
            outline.filter { RepoTreeConfig.SURFACE in it.showIn }.map { it.code }, config.areas.map { it.second })
        assertEquals(listOf("1", "2", "3", "4", "5", "6"), config.areas.map { it.second })
        val baked = Sections.byId("lnktree")!!
        for ((page, code) in config.areas) {
            assertEquals("chip $page reads its area's registry name", outline.first { it.code == code }.label, baked.page(page)!!.label)
            val block = JSONArray(BuroLlcTestData.text("data/ui/lnktree/$page.json")).getJSONObject(0)
            assertEquals("fragment", block.getString("kind")); assertEquals("repo_tree", block.getString("id"))
            assertEquals(code, block.getString("area"))
        }
    }

    @Test fun `build json ui repo_tree holds no label or name override and every chip label is the registry area name`() {
        val tree = ui.getJSONObject("repo_tree")
        assertEquals("no labels/omit/names: only the structure", setOf("_doc", "outline_asset", "group", "areas"), tree.keys().asSequence().toSet())
        for (a in tree.getJSONArray("areas").objects())
            assertEquals("an area entry is page + registry code only", setOf("page", "code"), a.keys().asSequence().toSet())
        val pages = lnktree.getJSONArray("pages").objects().associateBy { it.getString("id") }
        for ((page, _) in config.areas)
            assertFalse("page $page declares no label of its own (the build reads it from the outline)", pages.getValue(page).has("label"))
        val baked = Sections.byId("lnktree")!!
        for ((page, code) in config.areas) {
            val area = outline.first { it.code == code }
            assertEquals("chip $page == registry area $code name", area.label, baked.page(page)!!.label)
        }
    }

    @Test fun `the nav walk reaches every Proj page`() {
        // cloud-android-nav-walk.py walks ui.bottom_nav sections and every page they declare (nested too);
        // Lnktree is a bar section, so each area page is a walked page.
        val walker = BuroLlcTestData.text("1_cicd/src/scripts/cloud-android-nav-walk.py")
        assertTrue(walker.contains("bottom_nav")); assertTrue(walker.contains("pages_of"))
        assertTrue("lnktree" in strings(ui.getJSONArray("bottom_nav")))
        val walked = lnktree.getJSONArray("pages").objects().map { it.getString("id") }
        assertTrue(walked.containsAll(config.areas.map { it.first }))
    }

    @Test fun `Lnktree is the default page, Profile is only the avatar page and the old route still opens Lnktree`() {
        assertEquals("lnktree", ui.getString("default_section"))
        assertEquals("Lnktree", lnktree.getString("label"))
        fun labels(o: JSONObject): List<String> = listOf(o.optString("label")) + o.optJSONArray("pages").objects().flatMap(::labels)
        // #1439 the one Profile is the avatar's section (libs:account-ui); no Lnktree page is labelled Profile/Profiles again.
        val all = ui.getJSONArray("sections").objects().filter { it.getString("id") != "profile" }.flatMap(::labels)
        assertFalse("no other section or page is labelled Profile/Profiles: $all", all.any { it == "Profile" || it == "Profiles" })
        assertEquals("profile", ui.getJSONArray("settings").getString(0))
        assertEquals("lnktree" to "leafy", LegacyRoutes.resolve("me", "leafy"))
        assertEquals("lnktree" to null, LegacyRoutes.resolve("me", null))
        assertEquals("tasks" to "backlogs", LegacyRoutes.resolve("me", "tasks"))
        assertEquals("lnktree" to "lnktree-site", LegacyRoutes.resolve("me", "lnktree"))
        // the section took the name Lnktree: none of its pages may share that id (two title-menu entries, one tag)
        assertFalse("lnktree" in lnktree.getJSONArray("pages").objects().map { it.getString("id") })
    }
}

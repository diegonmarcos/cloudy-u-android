package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import com.diegonmarcos.cloudc3.pages.ContainerFilter
import com.diegonmarcos.cloudc3.pages.ContainerProjects
import com.diegonmarcos.cloudc3.pages.ContainersChrome
import com.diegonmarcos.cloudc3.pages.ContainersNav
import com.diegonmarcos.cloudc3.pages.ContainersTags
import com.diegonmarcos.cloudc3.pages.RoutesTableModel
import com.diegonmarcos.cloudc3.pages.RoutesTableScreen
import com.diegonmarcos.cloudc3.pages.RoutesTags
import com.diegonmarcos.cloudc3.pages.TopologyFilters
import com.diegonmarcos.superapp.bottomnav.NavPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1941 the Containers page, DRIVEN: the four rows (category, project name, Topology | Table, the page's own Filters) in that order, Lib among the
 * categories, the Topology's Status / VM / Type filters, the Table's Status / Auth / VM / Project / Type filters (Light is renamed Status), and the
 * old page-wide Filters row gone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w1200dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContainersPageComposeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                ContainerFilter.reset()
                base.evaluate()
            }
        }
    }).around(compose)

    private val decl = Declarations.containerProjects
    private val declared = Declarations.containersPages

    private var top by mutableStateOf(declared.first().id)
    private var view by mutableStateOf<String?>(null)

    private fun show(filters: @androidx.compose.runtime.Composable () -> Unit = { TopologyFilters(listOf("vm-a", "vm-b"), decl.types) }) {
        view = declared.first { it.id == top }.pages.first().id
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                val category = ContainersNav.categoryOf(decl, declared, top)
                ContainersChrome(
                    categories = ContainersNav.categories(decl, declared), selectedCategory = category,
                    onCategory = { c -> ContainersNav.pagesOf(decl, declared, c).first().let { top = it.id; view = it.pages.first().id } },
                    projects = ContainersNav.pagesOf(decl, declared, category), selectedProject = top,
                    onProject = { top = it.id; view = it.pages.first().id },
                    views = declared.first { it.id == top }.pages, selectedView = view, onView = { view = it.id },
                    filters = filters,
                ) { m -> Box(m) { Text("body-of-${view}") } }
            }
        }
        compose.waitForIdle()
    }

    private fun top(tag: String): Dp = compose.onNodeWithTag(tag).getBoundsInRoot().top

    /** The four rows' tops, in the order the owner fixed; also true for the body under them. */
    private fun rowsInOrder(): Boolean {
        val t = listOf(ContainersTags.ROW_CATEGORY, ContainersTags.ROW_PROJECT, ContainersTags.ROW_VIEW, ContainersTags.ROW_FILTERS, ContainersTags.BODY).map { top(it).value }
        return t.zipWithNext().all { (a, b) -> a < b }
    }

    @Test
    fun `the four rows sit in order, category then project then Topology and Table then the Filters row`() {
        show()
        assertTrue("category < project < view < filters < body", rowsInOrder())
    }

    @Test
    fun `MUTATION - a layout with the Filters row above the view row fails the order check`() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column {
                    Box(androidx.compose.ui.Modifier.testTag(ContainersTags.ROW_CATEGORY)) { Text("c") }
                    Box(androidx.compose.ui.Modifier.testTag(ContainersTags.ROW_PROJECT)) { Text("p") }
                    Box(androidx.compose.ui.Modifier.testTag(ContainersTags.ROW_FILTERS)) { Text("f") }
                    Box(androidx.compose.ui.Modifier.testTag(ContainersTags.ROW_VIEW)) { Text("v") }
                    Box(androidx.compose.ui.Modifier.testTag(ContainersTags.BODY)) { Text("b") }
                }
            }
        }
        compose.waitForIdle()
        assertFalse("the swapped layout must be rejected", rowsInOrder())
    }

    @Test
    fun `row 1 lists All, the Cloud Me categories (no Lib), row 2 lists the projects of the category, row 3 is Topology and Table`() {
        show()
        val cats = ContainersNav.categories(decl, declared)
        assertEquals(listOf("All") + listOf("Unix", "CG", "Capital", "Hardtech", "Wiki", "Projects", "42"), cats)
        assertFalse("Lib is gone from row 1", "Lib" in cats)
        cats.forEach { assertTrue("$it is not a first-row chip", compose.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()) }
        compose.onNodeWithText("Topology").assertExists(); compose.onNodeWithText("Table").assertExists()
        // pick Unix: its three projects are row 2
        compose.onNodeWithText("Unix").performClick(); compose.waitForIdle()
        listOf("SuperApp", "Cloud-me", "Cloudy").forEach { compose.onNodeWithText(it).assertExists() }
        assertEquals("pj-unix-superapp", top)
    }

    @Test
    fun `Wiki, the former Lib, is a category with its own page and Topology and Table`() {
        show()
        compose.onNodeWithText("Wiki").performClick(); compose.waitForIdle()
        assertEquals("pj-wiki-modernsc", top)
        assertEquals("pj-wiki-modernsc_topology", view)
        compose.onNodeWithTag(ContainersTags.ROW_PROJECT).assertIsDisplayed()
        compose.onNodeWithText("Table").performClick(); compose.waitForIdle()
        assertEquals("pj-wiki-modernsc_table", view)
        compose.onNodeWithText("body-of-pj-wiki-modernsc_table").assertExists()
    }

    @Test
    fun `every project of the declaration is reachable through rows 1 and 2`() {
        decl.projects.forEach { p ->
            val cat = ContainersNav.categoryOf(decl, declared, p.id)
            assertTrue("${p.id} is not listed under $cat", ContainersNav.pagesOf(decl, declared, cat).any { it.id == p.id })
            assertTrue(cat in ContainersNav.categories(decl, declared))
        }
        assertEquals("All", ContainersNav.categoryOf(decl, declared, "all"))
    }

    @Test
    fun `no Topology page carries the Index row, and the Ops Obsv Index stays`() {
        // #1953 every project's Topology page and All draw C3StackFragment over the baked stack_topology: no Index tile row, no jump subtitle
        val topo = com.diegonmarcos.cloudc3.cloud.Stacks.stackTopology()
        assertFalse("stack_topology still carries a tile_row Index", topo.any { it.kind == "tile_row" && it.title == "Index" })
        assertFalse("stack_topology still carries the jump subtitle", topo.any { it.subtitle == "Jump to a table on this page" })
        assertTrue("the Containers dashboards are still drawn", topo.any { it.kind == "cloud_dashboard" })
        val obs = com.diegonmarcos.cloudc3.cloud.Stacks.stackObservability()
        assertEquals(listOf("Index", "More"), obs.filter { it.kind == "tile_row" }.map { it.title })
    }

    /** #1953 the Topology body of [project] as the fragment draws it: band headings (or the empty line), then each section's parts and sub-headers. */
    private fun showTopology(project: String) {
        val plan = com.diegonmarcos.cloudc3.cloud.Stacks.topology(project).first
        compose.setContent {
            com.diegonmarcos.superapp.uikit.CloudKitTheme(com.diegonmarcos.superapp.uikit.KitPalette(androidx.compose.ui.graphics.Color.Black, androidx.compose.ui.graphics.Color.DarkGray,
                androidx.compose.ui.graphics.Color.White, androidx.compose.ui.graphics.Color.LightGray, androidx.compose.ui.graphics.Color.Cyan, androidx.compose.ui.graphics.Color.Gray, androidx.compose.ui.graphics.Color.Black)) {
                Column {
                    for (b in plan.bands) {
                        com.diegonmarcos.cloudc3.pages.TopologyBandHeader(b)
                        if (b.count == 0) com.diegonmarcos.cloudc3.pages.TopologyBandEmpty(b, project)
                        for (sec in b.sections.filter { it.count > 0 }) com.diegonmarcos.cloudc3.pages.TopologySectionBody(sec) { names -> names.forEach { Text(it) } }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the Topology body has a services band and a Repos band, with Public and Private under Apps, DB and Repos on Cloud-me`() {
        showTopology("pj-unix-cloudme")
        listOf("services", "repos").forEach { compose.onNodeWithTag(ContainersTags.topoBand(it)).assertExists() }
        // every split section that holds an entity (an empty section draws no card, the design's "0 -> card not drawn") shows Public and Private
        val plan = com.diegonmarcos.cloudc3.cloud.Stacks.topology("pj-unix-cloudme").first
        val drawn = listOf("app", "db", "repo").filter { plan.section(it)!!.count > 0 }
        assertTrue("Cloud-me draws no Repos (its pub and pvt repos)", "repo" in drawn)
        drawn.forEach { s -> listOf("pub", "pvt").forEach { pt ->
            compose.onNodeWithTag(ContainersTags.topoPart(s, pt), useUnmergedTree = true).assertExists()
        } }
        compose.onAllNodesWithText("Public", substring = true).fetchSemanticsNodes().let { assertTrue("no Public part", it.size >= drawn.size) }
        compose.onAllNodesWithText("Private", substring = true).fetchSemanticsNodes().let { assertTrue("no Private part", it.size >= drawn.size) }
        assertTrue("an Index node is drawn", compose.onAllNodesWithText("Index").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithText("Jump to a table on this page").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `a Capital project shows a Repos band with its repo and the Capital hub`() {
        showTopology("pj-capital-cb")
        compose.onNodeWithTag(ContainersTags.topoBand("repos")).assertExists()
        compose.onNodeWithTag(ContainersTags.topoSection("repo"), useUnmergedTree = true).assertExists()
        val repos = decl.repos.filter { r -> r.attached.any { it.project == "pj-capital-cb" } }.map { it.name }
        assertTrue(repos.contains("Capital-___") && repos.size >= 2)
        repos.forEach { compose.onNodeWithText(it).assertExists() }
    }

    @Test
    fun `the old page-wide Filters row is gone`() {
        show()
        compose.onNodeWithTag("filters").assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("Filters").fetchSemanticsNodes().isEmpty())
        listOf("kind_all", "kind_mcp", "kind_api", "kind_apps", "kind_db", "kind_repo").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        // and it is gone from the sources: no KindBar, no page-wide kind selection
        val main = java.io.File(FleetFixtures.moduleDir(), "app/src/main/java/com/diegonmarcos/cloudc3")
        val text = main.walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse("KindBar is still defined or used", text.contains("KindBar"))
        assertFalse("the page-wide kind selection is still read", text.contains("ContainerFilter.kind"))
    }

    @Test
    fun `Topology filters are Status, VM and Type and they select`() {
        show()
        compose.onNodeWithTag(ContainersTags.TOPO_FILTERS).assertIsDisplayed()
        listOf("status", "vm", "type").forEach { compose.onNodeWithTag(RoutesTags.filterLabel(it)).assertExists() }
        assertTrue("no Auth or Project filter on the Topology", compose.onAllNodesWithText("Auth").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithText("Project").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(ContainersTags.topoLight("green")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(ContainersTags.topoVm("vm-a")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(ContainersTags.topoType("MCP")).performClick(); compose.waitForIdle()
        assertEquals(setOf("green"), ContainerFilter.lights)
        assertEquals(setOf("vm-a"), ContainerFilter.vms)
        assertEquals(setOf("MCP"), ContainerFilter.types)
        compose.onNodeWithTag(ContainersTags.topoVm("vm-a")).performClick(); compose.waitForIdle()
        assertTrue(ContainerFilter.vms.isEmpty())
    }

    @Test
    fun `the Topology type and vm filters scope the dashboards`() {
        val c = com.diegonmarcos.cloudc3.cloud.Stacks.cloudServices("pj-unix-cloudy", null, setOf("MCP"))
        val names = c.groups.flatMap { g -> g.subgroups.flatMap { s -> s.containers.map { it.name } } }
        assertTrue(names.isNotEmpty())
        names.forEach { n -> assertEquals("$n is not an MCP", "MCP", decl.typeOf(com.diegonmarcos.cloudc3.pages.ContainersSource.current().entry(n)!!)) }
        val entry = com.diegonmarcos.cloudc3.pages.ContainersSource.current().entries.first { it.host.isNotBlank() }
        val byVm = com.diegonmarcos.cloudc3.cloud.Stacks.cloudServices(null, null, emptySet(), setOf(entry.host))
        assertTrue(byVm.groups.flatMap { g -> g.subgroups.flatMap { s -> s.containers } }.isNotEmpty())
    }

    private fun row(name: String, project: String, type: String, auth: String = "mesh", vm: String = "vm-a") =
        RoutesTableModel.Row("cat2", name, "", auth, "", vm, "", group = "cat1", project = project, type = type)

    private val rows = listOf(
        row("a-mcp", "Unix_Cloudy", "MCP"), row("b-api", "Unix_Cloudy", "API", vm = "vm-b"), row("c-app", "CG_Omniverse", "App"), row("d-repo", "CG_Omniverse", "Repo", auth = "bearer"),
    )

    private fun showTable() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column {
                    Box(androidx.compose.ui.Modifier.testTag(ContainersTags.ROW_VIEW)) { Text("Topology Table") }
                    RoutesTableScreen(rows, typeOrder = decl.types)
                }
            }
        }
        compose.waitForIdle()
    }

    private fun containers(): List<String> = compose.onAllNodesWithTag(RoutesTags.cell(RoutesTableModel.Column.CONTAINER))
        .fetchSemanticsNodes().map { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty() }

    @Test
    fun `Table filters are Status, Auth, VM, Project and Type, in that order under the view row, and Light is gone`() {
        showTable()
        val tags = listOf("status", "auth", "vm", "project", "type").map { RoutesTags.filterLabel(it) }
        tags.forEach { compose.onNodeWithTag(it).assertExists() }
        val tops = tags.map { top(it).value }
        assertEquals("filter rows in the declared order", tops.sorted(), tops)
        assertTrue("the Filters row comes under the view row", top(ContainersTags.ROW_VIEW).value < tops.first())
        assertTrue("Light is renamed Status", compose.onAllNodesWithText("Light").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(RoutesTags.filterLabel("light")).assertDoesNotExist()
    }

    @Test
    fun `the Table Project filter keeps that project's rows`() {
        showTable()
        compose.onNodeWithTag(RoutesTags.projectChip("CG_Omniverse")).performClick(); compose.waitForIdle()
        assertEquals(listOf("c-app", "d-repo"), containers())
        compose.onNodeWithTag(RoutesTags.projectChip("CG_Omniverse")).performClick(); compose.waitForIdle()
        assertEquals(4, containers().size)
    }

    @Test
    fun `the Table Type filter keeps that type and the chips follow the declared order`() {
        showTable()
        compose.onNodeWithTag(RoutesTags.typeChip("MCP")).performClick(); compose.waitForIdle()
        assertEquals(listOf("a-mcp"), containers())
        compose.onNodeWithTag(RoutesTags.typeChip("MCP")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RoutesTags.typeChip("Repo")).performClick(); compose.waitForIdle()
        assertEquals(listOf("d-repo"), containers())
        assertEquals(listOf("App", "MCP", "API", "Repo"), RoutesTableModel.typeOptions(rows, decl.types))
    }

    @Test
    fun `the Table Auth and VM filters still work`() {
        showTable()
        compose.onNodeWithTag(RoutesTags.authChip("bearer")).performClick(); compose.waitForIdle()
        assertEquals(listOf("d-repo"), containers())
        compose.onNodeWithTag(RoutesTags.authChip("bearer")).performClick(); compose.waitForIdle()
        compose.onNodeWithTag(RoutesTags.vmChip("vm-b")).performClick(); compose.waitForIdle()
        assertEquals(listOf("b-api"), containers())
    }

    @Test
    fun `the Table Status filter (was Light) filters by light`() {
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { RoutesTableScreen(rows, typeOrder = decl.types) } }
        compose.waitForIdle()
        compose.onNodeWithTag(RoutesTags.lightChip("grey")).performClick(); compose.waitForIdle()
        assertEquals("every row is grey with no prober", 4, containers().size)
        compose.onNodeWithTag(RoutesTags.lightChip("grey")).performClick(); compose.onNodeWithTag(RoutesTags.lightChip("green")).performClick(); compose.waitForIdle()
        assertTrue(containers().isEmpty())
    }
}

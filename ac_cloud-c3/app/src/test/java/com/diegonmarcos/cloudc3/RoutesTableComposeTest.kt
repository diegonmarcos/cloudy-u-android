package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import com.diegonmarcos.cloudc3.pages.RoutesTableModel
import com.diegonmarcos.cloudc3.pages.RoutesTableModel.Column
import com.diegonmarcos.cloudc3.pages.RoutesTableScreen
import com.diegonmarcos.cloudc3.pages.RoutesTags
import org.junit.Assert.assertEquals
import androidx.compose.ui.semantics.getOrNull
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

/** The Containers > Table page, DRIVEN: header taps sort, chips filter, the count follows, the state is handed out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2000dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutesTableComposeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private fun r(name: String, auth: String, vm: String, pub: String = "$name.example.com") =
        RoutesTableModel.Row("app", name, pub, auth, "$name.app:80", vm, "$name.vm:80", group = "Infra Apps", project = "Unix_Cloudy", type = "App")

    private val rows = listOf(
        r("charlie", "bearer", "vm-b"), r("alpha", "woauth", "vm-a"), r("bravo", "mesh", "vm-a", pub = ""), r("delta", "mesh", "vm-c", pub = ""),
    )
    private val states = mutableListOf<RoutesTableModel.State>()

    private fun show(initial: RoutesTableModel.State = RoutesTableModel.State(), data: List<RoutesTableModel.Row> = rows) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { RoutesTableScreen(data, initial, onState = { states += it }) }
        }
        compose.waitForIdle()
    }

    private fun containers(): List<String> = compose.onAllNodesWithTag(RoutesTags.cell(Column.CONTAINER))
        .fetchSemanticsNodes().map { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        }

    private fun tap(tag: String) { compose.onNodeWithTag(tag).performClick(); compose.waitForIdle() }

    @Test
    fun `all ten data columns are headed in the declared order, Project Cat1 Cat2 Type first, no Group column`() {
        show()
        val want = listOf("Project", "Cat1", "Cat2", "Type", "Container", "Public URL", "Auth Type", "Private URL", "VM Name", "VM IP:PORT")
        Column.DATA.zip(want).forEach { (c, text) -> compose.onNodeWithTag(RoutesTags.header(c)).assertTextContains(text, substring = true) }
    }

    @Test
    fun `rows show every column and the count`() {
        show()
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("4 rows")
        assertEquals(listOf("alpha", "bravo", "charlie", "delta"), containers())
    }

    @Test
    fun `a header tap sorts that column, again flips it, and the indicator follows`() {
        show()
        tap(RoutesTags.header(Column.AUTH_TYPE))
        compose.onNodeWithTag(RoutesTags.header(Column.AUTH_TYPE)).assertTextContains("▲", substring = true)
        assertEquals("ascending by auth label: Bearer, Mesh-only x2, WOAuth", listOf("charlie", "bravo", "delta", "alpha"), containers())
        tap(RoutesTags.header(Column.AUTH_TYPE))
        compose.onNodeWithTag(RoutesTags.header(Column.AUTH_TYPE)).assertTextContains("▼", substring = true)
        assertEquals(listOf("alpha", "bravo", "delta", "charlie"), containers())
        compose.onNodeWithTag(RoutesTags.header(Column.CONTAINER)).assertTextEquals("Container")
    }

    @Test
    fun `the auth chips multi-select and the count follows`() {
        show()
        tap(RoutesTags.authChip("mesh"))
        assertEquals(listOf("bravo", "delta"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
        tap(RoutesTags.authChip("bearer"))
        assertEquals(listOf("bravo", "charlie", "delta"), containers())
        tap(RoutesTags.authChip("mesh"))
        assertEquals(listOf("charlie"), containers())
    }

    @Test
    fun `the vm chips multi-select and combine with auth`() {
        show()
        tap(RoutesTags.vmChip("vm-a"))
        assertEquals(listOf("alpha", "bravo"), containers())
        tap(RoutesTags.vmChip("vm-c"))
        assertEquals(listOf("alpha", "bravo", "delta"), containers())
        tap(RoutesTags.authChip("mesh"))
        assertEquals(listOf("bravo", "delta"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
    }

    @Test
    fun `every sort and filter change is handed out to be persisted, and a restored state is honoured`() {
        show()
        tap(RoutesTags.header(Column.VM_NAME))
        tap(RoutesTags.authChip("woauth"))
        assertEquals(2, states.size)
        assertEquals(RoutesTableModel.State(Column.VM_NAME, true, setOf("woauth")), states.last())
    }

    @Test
    fun `a restored state is honoured on the first draw`() {
        val restored = RoutesTableModel.decode(RoutesTableModel.encode(RoutesTableModel.State(Column.CONTAINER, false, setOf("mesh"))))
        show(initial = restored)
        assertEquals(listOf("delta", "bravo"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
        compose.onNodeWithTag(RoutesTags.header(Column.CONTAINER)).assertTextContains("\u25BC", substring = true)
    }

    private fun type(text: String) {
        compose.onNodeWithTag(RoutesTags.SEARCH).performTextInput(text)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
    }

    @Test
    fun `typing filters live across all columns and updates the count`() {
        show()
        type("EXAMPLE")
        assertEquals(listOf("alpha", "charlie"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("2 of 4 rows")
        compose.onNodeWithTag(RoutesTags.SEARCH).performTextClearance()
        type("bravo.app")
        assertEquals("private URL column is searched too", listOf("bravo"), containers())
    }

    @Test
    fun `search combines with the chips and is handed out for persistence`() {
        show()
        tap(RoutesTags.vmChip("vm-a"))
        type("a")
        assertEquals(listOf("alpha", "bravo"), containers())
        type("lph")
        assertEquals(listOf("alpha"), containers())
        assertTrue(states.last().query.contains("lph") && states.last().vms == setOf("vm-a"))
    }

    @Test
    fun `the clear button empties the box and restores every row`() {
        show()
        type("alpha")
        assertEquals(listOf("alpha"), containers())
        tap(RoutesTags.SEARCH_CLEAR)
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        assertEquals(4, containers().size)
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("4 rows")
        assertTrue(compose.onAllNodesWithTag(RoutesTags.SEARCH_CLEAR).fetchSemanticsNodes().isEmpty())
        assertEquals("", states.last().query)
    }

    @Test
    fun `a restored query is shown in the box and applied`() {
        show(initial = RoutesTableModel.State(query = "delta"))
        assertEquals(listOf("delta"), containers())
        compose.onNodeWithTag(RoutesTags.SEARCH).assertTextContains("delta")
    }

    private val opened = mutableListOf<RoutesTableModel.OpenTarget>()

    private fun showWithOpen() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { RoutesTableScreen(rows, onState = { states += it }, onOpen = { opened += it }) }
        }
        compose.waitForIdle()
    }

    private fun longPress(col: Column, index: Int = 0) {
        compose.onAllNodesWithTag(RoutesTags.cell(col))[index].performTouchInput { longClick() }
        compose.waitForIdle()
    }

    private fun clipboard(): String? {
        val cm = RuntimeEnvironment.getApplication().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        return cm.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `long-press on any cell opens Copy, Open and Copy and Open`() {
        showWithOpen()
        for (col in Column.DATA) {
            longPress(col)
            for (tag in listOf(RoutesTags.MENU_COPY, RoutesTags.MENU_OPEN, RoutesTags.MENU_COPY_OPEN)) compose.onNodeWithTag(tag).assertExists()
            compose.onNodeWithTag(RoutesTags.MENU_COPY).performClick()
            compose.waitForIdle()
        }
    }

    @Test
    fun `Copy puts the cell value on the clipboard`() {
        showWithOpen()
        longPress(Column.CONTAINER, 2)
        compose.onNodeWithTag(RoutesTags.MENU_COPY).performClick()
        assertEquals("charlie", clipboard())
        assertTrue("Copy alone opens nothing", opened.isEmpty())
    }

    @Test
    fun `Open on a public url opens its https address, Copy and Open does both`() {
        showWithOpen()
        longPress(Column.PUBLIC_URL, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        assertEquals(listOf<RoutesTableModel.OpenTarget>(RoutesTableModel.OpenTarget.Url("https://alpha.example.com")), opened)
        longPress(Column.PRIVATE_URL, 0)
        compose.onNodeWithTag(RoutesTags.MENU_COPY_OPEN).performClick()
        assertEquals("alpha.app:80", clipboard())
        assertEquals(RoutesTableModel.OpenTarget.Url("http://alpha.app:80"), opened.last())
    }

    @Test
    fun `Open on a container and a vm hands out their targets`() {
        showWithOpen()
        longPress(Column.CONTAINER, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        longPress(Column.VM_NAME, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        assertEquals(listOf(RoutesTableModel.OpenTarget.Container("alpha"), RoutesTableModel.OpenTarget.Vm("vm-a")), opened)
    }

    @Test
    fun `Open on a category filters the table to it, and the chip clears it`() {
        showWithOpen()
        longPress(Column.CATEGORY, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).performClick()
        compose.waitForIdle()
        assertEquals("app", states.last().category)
        compose.onNodeWithTag(RoutesTags.CATEGORY_CHIP).assertExists()
        tap(RoutesTags.CATEGORY_CHIP)
        assertEquals("", states.last().category)
    }

    @Test
    fun `values that cannot be opened disable Open and Copy and Open, and a blank cell disables Copy`() {
        showWithOpen()
        longPress(Column.AUTH_TYPE, 0)
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).assertIsNotEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_COPY_OPEN).assertIsNotEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_COPY).assertIsEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_COPY).performClick()
        compose.waitForIdle()
        // charlie is the only row with a public url... bravo/delta have none: sorted by container, bravo is index 1
        longPress(Column.PUBLIC_URL, 1)
        compose.onNodeWithTag(RoutesTags.MENU_COPY).assertIsNotEnabled()
        compose.onNodeWithTag(RoutesTags.MENU_OPEN).assertIsNotEnabled()
    }

    // ── status light, breakdown sheet, More page ─────────────────────────────────────────

    private class FakeProber(val up: Set<String>, val down: Set<String>? = null) : com.diegonmarcos.cloudc3.pages.Prober {
        val probed = mutableListOf<String>()
        override suspend fun probe(leg: com.diegonmarcos.cloudc3.pages.ProbeModel.Leg, target: String): com.diegonmarcos.cloudc3.pages.ProbeModel.LegResult {
            probed += target
            val ok = up.any { target.contains(it) }
            return com.diegonmarcos.cloudc3.pages.ProbeModel.LegResult(
                leg, if (ok) com.diegonmarcos.cloudc3.pages.ProbeModel.Status.UP else com.diegonmarcos.cloudc3.pages.ProbeModel.Status.DOWN, target,
                dns = "10.0.0.6", http = if (ok) 401 else null, latencyMs = 12, error = if (ok) "" else "ConnectException: refused", checkedAt = 1000)
        }
        override suspend fun containersDown(): Set<String>? = down
    }

    private val decls = kotlinx.serialization.json.Json.parseToJsonElement(
        """{"alpha":{"container":"alpha","image":"a/a:1","env":{"names":["A"],"secret_names":["A_PASSWORD"]},"routes":{"auth_tier":"woauth"},"volumes":["d:/data"]}}"""
    ) as kotlinx.serialization.json.JsonObject

    private fun showProbed(prober: com.diegonmarcos.cloudc3.pages.Prober?) {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RoutesTableScreen(rows, onState = { states += it }, prober = prober, declarations = decls)
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `lights turn from grey to green, yellow and red as the probes land`() {
        // alpha: public + private + direct all answer; charlie: only the public answers; bravo/delta mesh-only: nothing answers
        showProbed(FakeProber(up = setOf("alpha", "charlie.example")))
        compose.waitForIdle()
        fun desc(i: Int) = compose.onNodeWithTag(RoutesTags.light(i)).fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.joinToString()
        assertEquals("Status green", desc(0))   // alpha
        assertEquals("Status red", desc(1))     // bravo (mesh-only, nothing answers)
        assertEquals("Status yellow", desc(2))  // charlie (public up, private/direct down)
    }

    @Test
    fun `the status header sorts worst first and the light chips filter`() {
        showProbed(FakeProber(up = setOf("alpha", "charlie.example")))
        tap(RoutesTags.header(Column.STATUS))
        assertEquals(listOf("bravo", "delta", "charlie", "alpha"), containers())
        tap(RoutesTags.lightChip("green"))
        assertEquals(listOf("alpha"), containers())
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("1 of 4 rows")
    }

    @Test
    fun `tapping a light opens the breakdown with each leg, and re-test probes again`() {
        val prober = FakeProber(up = setOf("alpha.app", "alpha.example"))
        showProbed(prober)
        tap(RoutesTags.light(0))
        compose.onNodeWithTag(RoutesTags.SHEET).assertExists()
        for (leg in listOf("public", "private", "direct")) compose.onNodeWithTag(RoutesTags.leg(leg)).assertExists()
        compose.onNodeWithTag(RoutesTags.leg("public")).assertTextContains("HTTP: 401", substring = true)
        compose.onNodeWithTag(RoutesTags.leg("direct")).assertTextContains("ConnectException", substring = true)
        compose.onNodeWithTag(RoutesTags.leg("direct")).assertTextContains("Latency: 12 ms", substring = true)
        compose.onNodeWithTag(RoutesTags.leg("public")).assertTextContains("DNS: 10.0.0.6", substring = true)
        val before = prober.probed.size
        tap(RoutesTags.RETEST)
        assertTrue("re-test probed the row's legs again", prober.probed.size >= before + 3)
    }

    @Test
    fun `without a prober the lights stay grey and there is no refresh`() {
        showProbed(null)
        compose.onNodeWithTag(RoutesTags.light(0)).assertExists()
        assertTrue(compose.onAllNodesWithTag(RoutesTags.REFRESH).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `More opens the declaration page, raw json toggles, copy puts json on the clipboard, back returns`() {
        showProbed(null)
        tap(RoutesTags.more(0))
        compose.onNodeWithTag(RoutesTags.DECL_PAGE).assertExists()
        compose.onNodeWithText("Image and runtime").assertExists()
        compose.onNodeWithText("a/a:1").assertExists()
        compose.onNodeWithText("A_PASSWORD").assertExists()
        tap(RoutesTags.DECL_RAW)
        compose.onNodeWithTag("routes_decl_rawtext").assertTextContains("\"image\": \"a/a:1\"", substring = true)
        tap(RoutesTags.DECL_COPY)
        assertTrue(clipboard().orEmpty().contains("\"container\": \"alpha\""))
        tap(RoutesTags.DECL_BACK)
        compose.onNodeWithTag(RoutesTags.SCREEN).assertExists()
    }

    @Test
    fun `More on a row with no declaration says so`() {
        showProbed(null)
        tap(RoutesTags.more(1))   // bravo has none in the fixture
        compose.onNodeWithText("No declaration is carried for bravo.").assertExists()
    }

    @Test
    fun `the shipped routes render with the owner's three filters`() {
        show(data = Declarations.routes)
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextEquals("${Declarations.routes.size} rows")
        for (id in listOf("bearer", "woauth", "mesh")) tap(RoutesTags.authChip(id))
        compose.onNodeWithTag(RoutesTags.COUNT).assertTextContains("of ${Declarations.routes.size} rows", substring = true)
    }

    @Test
    fun `the list scrolls down to the last row and the header stays`() {
        val many = (1..80).map { r("svc%03d".format(it), "woauth", "vm-a") }
        show(data = many)
        // index 0 = filters, 1 = sticky header, rows follow
        compose.onNodeWithTag(RoutesTags.LIST).performScrollToIndex(many.size + 1)
        compose.waitForIdle()
        compose.onNodeWithText("svc080").assertIsDisplayed()
        compose.onNodeWithTag(RoutesTags.header(Column.CONTAINER)).assertIsDisplayed()
    }
}

package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import com.diegonmarcos.cloudcalc.sheet.Area
import androidx.compose.ui.test.performTextReplacement
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.cloudcalc.sheetui.SheetHub
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.DrawerTags
import com.diegonmarcos.cloudcalc.ui.SheetTags
import com.diegonmarcos.superapp.bottomnav.BottomNavTags
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.navbartop1.TopFirstRowTags
import java.io.File
import org.junit.Assert.assertEquals
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

/** The new shell pieces under Robolectric: the bar's order, the shared first row (title dropdown, Configs), and the Sheet page's tabs and cells. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SheetShellTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                SheetHub.reset()
                base.evaluate()
            }
        }
    }).around(compose)

    private object NoEngine : CalcApi {
        override fun info() = """{"ok":true}"""
        override fun eval(expr: String, options: String) = """{"ok":true,"result":"0","messages":[]}"""
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = """{"ok":true,"x":[],"y":[]}"""
        override fun complete(prefix: String, max: Int) = "[]"
        override fun items(kind: String, category: String, max: Int) = "[]"
        override fun ratesInfo() = """{"sources":[],"time":0}"""
        override fun fetchRates() = """{"ok":false,"error":"offline","fetched":[],"failed":[],"time":0}"""
    }

    private val state = CalcState(null)
    private fun launch() = compose.setContent { CalcTheme { CalcShell(NoEngine, state) } }

    @Test fun `the bar is Calc, Sheet, Home, Measure, Tools left to right`() {
        launch()
        val ids = listOf("calculator", "sheet", "home", "measure", "toolkit")
        assertEquals(ids, Declarations.sections.map { it.id })
        val lefts = ids.map { compose.onNodeWithTag(BottomNavTags.item(it)).getBoundsInRoot().left }
        assertEquals("left to right", lefts.sorted(), lefts)
        assertTrue(lefts.toSet().size == ids.size)
    }

    @Test fun `the first row's Configs button opens Configs, which is not on the bar, and Back goes back`() {
        launch()
        assertTrue(compose.onAllNodesWithTag(BottomNavTags.item("settings")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag(BottomNavTags.item("configs")).fetchSemanticsNodes().isEmpty())
        val before = state.tab
        openConfigsFromTopBar()
        assertEquals("configs", state.tab)
        compose.onNodeWithTag(CalcTags.tab("configs")).assertExists()
        // Configs again, while on Configs, changes nothing (it opens, it does not toggle).
        openConfigsFromTopBar()
        assertEquals("configs", state.tab)
        compose.runOnIdle { state.toggleConfigs() }
        compose.waitForIdle()
        assertEquals(before, state.tab)
    }

    private fun openConfigsFromTopBar() {
        compose.onNodeWithTag(TopFirstRowTags.CONFIGS).performClick()
        compose.waitForIdle()
    }

    @Test fun `the shell draws the shared first row once, titled with the app name, with no hamburger and no round Configs button`() {
        launch()
        assertEquals(1, compose.onAllNodesWithTag(TopFirstRowTags.ROW).fetchSemanticsNodes().size)
        compose.onNodeWithTag(TopFirstRowTags.AI).assertExists()
        compose.onNodeWithTag(TopFirstRowTags.PROFILE).assertExists()
        compose.onNodeWithTag(TopFirstRowTags.CONFIGS).assertExists()
        assertTrue("the first row has no hamburger", compose.onAllNodesWithTag("topbar_menu").fetchSemanticsNodes().isEmpty())
        // the title is the path "{App} / {Section} / {Page}": the app's name is its first segment, listed in the dropdown
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TopFirstRowTags.crumb(0)).assertTextContains(RuntimeEnvironment.getApplication().getString(R.string.app_name))
        assertTrue(compose.onAllNodesWithTag("calc_configs_button").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the title dropdown lists the open section's pages, a tap navigates and closes it`() {
        launch()
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TopFirstRowTags.DROPDOWN).assertExists()
        Declarations.nav.section(state.section)!!.pages.forEach { compose.onNodeWithTag(TopFirstRowTags.entry(it.id)).assertExists() }
        assertTrue("Configs is not in the dropdown", compose.onAllNodesWithTag(TopFirstRowTags.entry("configs")).fetchSemanticsNodes().isEmpty())
        val target = Declarations.nav.section(state.section)!!.pages.last()
        compose.onNodeWithTag(TopFirstRowTags.entry(target.id)).performClick()
        compose.waitForIdle()
        assertEquals(target.id, state.tab)
        assertTrue("the dropdown closed", compose.onAllNodesWithTag(TopFirstRowTags.DROPDOWN).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `the Sheet page fills the title dropdown with its sheets, and a tap selects one`() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        val st = SheetHub.get(RuntimeEnvironment.getApplication())
        compose.runOnIdle { st.addSheet() }
        compose.onNodeWithTag(TopFirstRowTags.ISLAND).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DrawerTags.sheet("Sheet1")).assertExists()
        compose.onNodeWithTag(DrawerTags.sheet("Sheet2")).assertExists()
        compose.onNodeWithTag(DrawerTags.NEW_SHEET).assertExists()
        // #1758 the sheets are the page's own entries; #1850 every declared section is a Parent Page (Configs via Settings / Configs)
        Declarations.nav.sections.forEach {
            val id = if (it.id == com.diegonmarcos.superapp.bottomnav.UrlUriIds.CONFIGS) com.diegonmarcos.superapp.bottomnav.UrlUriIds.CONFIGS
                     else com.diegonmarcos.superapp.bottomnav.UrlUriIds.section(it.id)
            compose.onNodeWithTag(com.diegonmarcos.superapp.navbartop1.UrlUriTags.entry(id)).assertExists()
        }
        compose.onNodeWithTag(DrawerTags.sheet("Sheet1")).performClick()
        compose.waitForIdle()
        assertEquals("Sheet1", st.sheet)
        assertTrue(compose.onAllNodesWithTag(TopFirstRowTags.DROPDOWN).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `Ask Jev is a Calc page, Home replaced the AI page, and every section is reachable from the bar`() {
        launch()
        assertTrue("the old Jev section is gone from the bar", compose.onAllNodesWithTag(BottomNavTags.item("jev")).fetchSemanticsNodes().isEmpty())
        assertTrue("the AI section is gone from the bar", compose.onAllNodesWithTag(BottomNavTags.item("ai")).fetchSemanticsNodes().isEmpty())
        assertTrue("Ask Jev is a Calc page", Declarations.tabsOf("calculator").any { it.id == "ask" })
        // Ask Jev is the last page of the Others row: a user scrolls the row to it and taps it.
        compose.onNodeWithTag(PageTabsTags.tab("ask")).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("ask", state.tab)
        compose.onNodeWithTag(BottomNavTags.item("toolkit")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("light", "recorder", "tracker", "clock"), Declarations.tabsOf("toolkit").map { it.id })
        assertEquals("light", state.tab)
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.ROOT).assertExists()
        compose.onNodeWithTag(BottomNavTags.item("home")).performClick()
        compose.waitForIdle()
        assertEquals("home", state.tab)
        compose.onNodeWithTag(CalcTags.HOME).assertExists()
        assertTrue("no AI page", compose.onAllNodesWithTag(CalcTags.AI_PAGE).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(BottomNavTags.item("calculator")).performClick()
        compose.waitForIdle()
        assertEquals("the Calc section comes back to the page last shown in it", "ask", state.tab)
    }

    @Test fun `Home draws the SuperApp launcher tile, 9 per row, an icon with its label below, every tool once`() {
        launch()
        assertEquals(9, Declarations.tileColumns)
        compose.onNodeWithTag(BottomNavTags.item("home")).performClick()
        compose.waitForIdle()
        val tools = Declarations.homeTree.flatMap { it.leaves() }.map { it.id }
        for (id in tools) {
            compose.onAllNodesWithTag("tile:$id", useUnmergedTree = true).assertCountEquals(1)
            compose.onNodeWithTag("tileicon:$id", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("tilelabel:$id", useUnmergedTree = true).assertExists()
            val icon = compose.onNodeWithTag("tileicon:$id", useUnmergedTree = true).getBoundsInRoot()
            val label = compose.onNodeWithTag("tilelabel:$id", useUnmergedTree = true).getBoundsInRoot()
            assertTrue("the label of $id is below its icon", label.top >= icon.bottom)
        }
        // #1909 Calc > Decimals > Calc: five mode tiles share one row, in distinct columns.
        val calc = Declarations.homeTree[0].leaves().filter { it.page == "calc" }.map { it.id }
        assertEquals(5, calc.size)
        val b = calc.map { compose.onNodeWithTag("tile:$it", useUnmergedTree = true).getBoundsInRoot() }
        assertEquals("the tiles share one row", 1, b.map { it.top }.toSet().size)
        assertEquals("distinct columns", 5, b.map { it.left }.toSet().size)
        // The very composable of the SuperApp's Cloud page, not the Configs card grid.
        val home = File("src/main/java/com/diegonmarcos/cloudcalc/ui/HomeScreen.kt").readText()
        assertTrue("Home draws CompactTileCore", home.contains("CompactTileCore("))
        assertTrue("Home no longer draws IndexTileGrid", !home.contains("IndexTileGrid("))
        val superTiles = File("../../aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherTiles.kt").readText()
        assertTrue("the SuperApp tile delegates to the same CompactTileCore", superTiles.contains("CompactTileCore("))
        assertTrue("one implementation: the lib holds the tile",
            File("../../ab_cloud-libs-shared/libs/tile-grid/src/main/java/com/diegonmarcos/superapp/launcher/CompactTile.kt").readText().contains("fun CompactTileCore("))
        assertEquals(9, org.json.JSONObject(File("../build.json").readText()).getJSONObject("ui").getInt("tile_columns"))
    }

    @Test fun `sheet tabs add, rename, duplicate, delete and reorder`() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.tab("Sheet1")).assertExists()
        compose.onNodeWithTag(SheetTags.ADD_TAB).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.tab("Sheet2")).assertExists()
        val st = SheetHub.get(RuntimeEnvironment.getApplication())
        compose.runOnIdle { st.rename("Sheet2", "Totals"); st.duplicate("Totals") }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.tab("Totals")).assertExists()
        compose.onNodeWithTag(SheetTags.tab("Totals (copy)")).assertExists()
        compose.runOnIdle { st.move("Totals (copy)", -2) }
        assertEquals(listOf("Totals (copy)", "Sheet1", "Totals"), st.wb.sheetNames())
        compose.runOnIdle { st.delete("Totals (copy)") }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag(SheetTags.tab("Totals (copy)")).fetchSemanticsNodes().isEmpty())
    }

    @Test fun `typing in the edit bar enters a formula and the grid shows the result`() {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.cell(0, 0)).performClick()
        compose.onNodeWithTag(SheetTags.EDIT_BAR).performTextReplacement("4")
        compose.onNodeWithTag(SheetTags.COMMIT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.cell(0, 1)).performClick()
        compose.onNodeWithTag(SheetTags.EDIT_BAR).performTextReplacement("=A1*2")
        compose.onNodeWithTag(SheetTags.COMMIT).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.cell(0, 1)).assertTextContains("8")
        // the edit bar shows the RAW formula
        compose.onNodeWithTag(SheetTags.cell(0, 1)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.EDIT_BAR).assertTextContains("=A1*2")
        // undo takes the formula away again
        compose.onNodeWithTag(SheetTags.UNDO).performClick()
        compose.waitForIdle()
        assertEquals("", SheetHub.get(RuntimeEnvironment.getApplication()).wb.raw("Sheet1", 0, 1))
    }

    private fun sheetPage(): com.diegonmarcos.cloudcalc.sheetui.SheetState {
        launch()
        compose.onNodeWithTag(BottomNavTags.item("sheet")).performClick()
        compose.waitForIdle()
        return SheetHub.get(RuntimeEnvironment.getApplication())
    }

    @Test fun `a long press on the selection opens Copy Cut Paste Clear, which act on the whole range`() {
        val st = sheetPage()
        val ctx = RuntimeEnvironment.getApplication()
        compose.runOnIdle {
            st.wb.set("Sheet1", 0, 0, "1"); st.wb.set("Sheet1", 1, 0, "=A1*2"); st.wb.set("Sheet1", 0, 1, "x")
            st.selectArea(Area(0, 0, 1, 1))
        }
        compose.onNodeWithTag(SheetTags.cell(1, 1)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.CELL_MENU).assertExists()
        assertEquals(Area(0, 0, 1, 1), st.area)                       // the selection is kept
        compose.onNodeWithTag(SheetTags.cellMenu("cut")).performClick()
        compose.waitForIdle()
        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("1\t2\nx\t", cm.primaryClip!!.getItemAt(0).text.toString().trimEnd('\n', '\r'))
        assertEquals("", st.wb.raw("Sheet1", 0, 0)); assertEquals("", st.wb.raw("Sheet1", 1, 0))   // cut clears the range
        compose.runOnIdle { st.select(3, 4); st.paste(ctx) }
        assertEquals("1", st.wb.raw("Sheet1", 3, 4)); assertEquals("=D5*2", st.wb.raw("Sheet1", 4, 4))
        assertEquals("x", st.wb.raw("Sheet1", 3, 5))
        compose.runOnIdle { st.undo() }
        assertEquals("", st.wb.raw("Sheet1", 3, 4))
    }

    @Test fun `a long press on a row header opens the row menu and inserts above every selected row`() {
        val st = sheetPage()
        compose.runOnIdle { st.wb.set("Sheet1", 0, 0, "1"); st.wb.set("Sheet1", 0, 1, "2"); st.wb.set("Sheet1", 1, 0, "=A2+1") }
        compose.onNodeWithTag(SheetTags.rowHeader(1)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.headerMenu("row")).assertExists()
        compose.onNodeWithTag(SheetTags.headerMenu("row_above")).performClick()
        compose.waitForIdle()
        assertEquals("2", st.wb.raw("Sheet1", 0, 2)); assertEquals("=A3+1", st.wb.raw("Sheet1", 1, 0))
        compose.runOnIdle { st.selectArea(Area(0, 1, com.diegonmarcos.cloudcalc.sheet.A1.MAX_COLS - 1, 2)); st.deleteRow() }
        assertEquals("=#REF!+1", st.wb.raw("Sheet1", 1, 0))
    }

    @Test fun `a long press on a column header opens the column menu`() {
        val st = sheetPage()
        compose.runOnIdle { st.wb.set("Sheet1", 0, 0, "1"); st.wb.set("Sheet1", 1, 0, "=A1") }
        compose.onNodeWithTag(SheetTags.colHeader(0)).performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithTag(SheetTags.headerMenu("col")).assertExists()
        compose.onNodeWithTag(SheetTags.headerMenu("col_left")).performClick()
        compose.waitForIdle()
        assertEquals("1", st.wb.raw("Sheet1", 1, 0)); assertEquals("=B1", st.wb.raw("Sheet1", 2, 0))
    }
}

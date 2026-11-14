package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1075 — every SuperApp page starts BELOW the shell's top row. All pages are hosted by the one content container
 * (@id/fragment_container, view_content_panes.xml), padded by the top row's height; a page that owned its own chrome or
 * drew edge to edge would be the exception, so:
 *
 *   T1 the container's top padding covers the top row (toolbar_island: shell_top_row_margin + the action-bar-size toolbar; the typed 84 dp was 6 dp short)
 *   T2 no page takes the chrome over (ShellOverride has no implementer), so every page goes through that container
 *   T3 the pages' own content (Configs, Cloud ▸ Apps, the tile grid), hosted in the REAL container, draws nothing above
 *      the top row's bottom edge
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
class PageTopInsetTest : KitPageHarness() {

    private val layouts = File(System.getProperty("user.dir"), "src/main/res/layout")

    /** The top row's bottom edge in dp from the content area's top: toolbar_island's top margin + the toolbar's height. */
    private fun topRowBottomDp(): Float {
        val xml = File(layouts, "activity_main.xml").readText()
        val island = xml.substring(xml.indexOf("@+id/toolbar_island"))
        assertTrue("the top row's offset is the declared dimen", island.contains("android:layout_marginTop=\"@dimen/shell_top_row_margin\""))
        assertTrue("the toolbar is the action-bar size", island.contains("android:layout_height=\"?attr/actionBarSize\""))
        val margin = compose.activity.resources.getDimension(R.dimen.shell_top_row_margin) / compose.activity.resources.displayMetrics.density
        val themed = ContextThemeWrapper(compose.activity, R.style.Theme_Superapp)
        val tv = android.util.TypedValue()
        val attr = themed.resources.getIdentifier("actionBarSize", "attr", themed.packageName)
        assertTrue(attr != 0 && themed.theme.resolveAttribute(attr, tv, true))
        return margin + tv.getDimension(themed.resources.displayMetrics) / themed.resources.displayMetrics.density
    }

    private fun container(): View = compose.activity.findViewById(R.id.fragment_container)

    private fun host(page: Fragment) {
        compose.runOnUiThread {
            compose.activity.setContentView(R.layout.view_content_panes)
            compose.activity.supportFragmentManager.beginTransaction().add(R.id.fragment_container, page).commitNow()
        }
        compose.waitForIdle()
    }

    @Test
    fun `T1 the content container is padded below the top row`() {
        host(TileGridFragment.newInstance("t", emptyList()))
        val padDp = container().paddingTop / compose.density.density
        assertTrue("container pads $padDp dp, the top row ends at ${topRowBottomDp()} dp", padDp >= topRowBottomDp() - 0.5f)
    }

    @Test
    fun `T2 no page owns the shell chrome, so every page sits in the padded container`() {
        val root = File(System.getProperty("user.dir"), "src/main/java")
        val implementers = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> Regex("[:,]\\s*ShellOverride\\b").containsMatchIn(f.readText()) }.map { it.name }.toList()
        // #1719 the declared exception: the Firewall page owns the BOTTOM nav only (#1737: the unified Network page is gone) (a page's own island replaces the shell's); the top row stays the shell's
        val declared = setOf("NgFirewallFragment.kt")
        assertTrue("these pages take the chrome over and could draw under the top row: ${implementers - declared}", (implementers - declared).isEmpty())
        for (f in root.walkTopDown().filter { it.name in declared })
            assertTrue("${f.name} must not hide the top row", !f.readText().contains("fun ownsToolbar(): Boolean = true"))
        val shell = File(root, "com/diegonmarcos/superapp/ShellActivity.kt").readText()
        val swaps = Regex("\\.replace\\(R\\.id\\.([a-z_]+),").findAll(shell).map { it.groupValues[1] }.toSet()
        assertTrue("pages are swapped into fragment_container only, found $swaps", "fragment_container" in swaps)
    }

    private fun assertBelowTopRow(name: String, page: Fragment) {
        host(page)
        val loc = IntArray(2).also { container().getLocationInWindow(it) }
        val limitPx = loc[1] + topRowBottomDp() * compose.density.density
        val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(false)
            .filter { it.boundsInWindow.height > 0f && it.config.getOrNull(SemanticsProperties.TestTag) != null }
        assertTrue("$name drew no content", nodes.isNotEmpty())
        val top = nodes.minOf { it.boundsInWindow.top }
        assertTrue("$name's content starts at $top px, above the top row's bottom edge at $limitPx px", top >= limitPx - 1f)
    }

    @Test
    fun `T3 Configs, Cloud Apps and the tile grid start below the top row`() {
        val configs = ConfigsPageFixture.tiles(compose.activity)
        assertBelowTopRow("Configs", ConfigsFragment.newInstance(configs))
    }

    @Test
    fun `T3b Cloud Apps starts below the top row`() = assertBelowTopRow("Cloud > Apps", GroupedTilesFragment.newInstance("cloud", search = true))

    @Test
    fun `T3c the tile grid starts below the top row`() = assertBelowTopRow("Tile grid", TileGridFragment.newInstance("Grid",
        listOf(TileGridFragment.Tile("page:x/a", "Alpha", R.drawable.ic_settings, group = "G"))))
}

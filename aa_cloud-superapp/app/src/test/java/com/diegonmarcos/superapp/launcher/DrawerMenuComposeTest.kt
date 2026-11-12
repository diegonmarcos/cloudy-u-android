package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the section rail and the Home drawer, Compose since they were NavigationViews filled in code.
 * The menus are data now, so what they list is pinned directly.
 *
 *   D1 the Home drawer lists the declared main-menu entries first, in declared order, then one TITLE per
 *      ui.home_groups group followed by its tiles
 *   D2 a section's rail starts with its upper-cased header (a Section target) and carries a disabled
 *      HEADING row for every declared group/subgroup change, exactly as Sections.headingOf says
 *   D3 DrawerList: a tap on an item reports its target; headings and titles do not react
 *   D4 the Home drawer's Profile badge shows the stored name and email above the menu
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DrawerMenuComposeTest : KitPageHarness() {

    private val ctx: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `D1 the Home drawer is the declared site map`() {
        val rows = HomeDrawerFragment.rows(ctx)
        val main = Sections.mainMenu()
        assertTrue("ui.main_menu is empty", main.isNotEmpty())
        assertEquals(main.map { it.label }, rows.take(main.size).map { it.label })
        assertTrue(rows.take(main.size).all { it.kind == DrawerRow.Kind.ITEM && it.target is DrawerTarget.Action })
        val groups = Sections.homeGroups()
        assertEquals("one TITLE per home_groups group, in order", groups.map { it.title },
            rows.filter { it.kind == DrawerRow.Kind.TITLE }.map { it.label })
        // The row after a TITLE is that group's first tile (when it has one).
        for (g in groups) {
            val at = rows.indexOfFirst { it.kind == DrawerRow.Kind.TITLE && it.label == g.title }
            g.tiles.firstOrNull()?.let { assertEquals(it.label, rows[at + 1].label) }
        }
    }

    @Test
    fun `D2 a section rail has its header and the declared headings`() {
        for (s in Sections.all().filter { it.pages.isNotEmpty() }) {
            val rows = SectionMenuFragment.rows(ctx, s.id)
            assertEquals(s.label.uppercase(), rows[0].label)
            assertEquals(DrawerTarget.Section(s.id, s.label), rows[0].target)
            // Every page is a Page target, in declared order.
            val pages = rows.mapNotNull { (it.target as? DrawerTarget.Page) }.filter { !it.pageId.contains('/') }
            assertEquals("${s.id}: pages", s.pages.map { it.id }, pages.map { it.pageId })
            // Headings: one per group change and one per subgroup change.
            var g0 = ""; var s0 = ""; var want = 0
            for (p in s.pages) {
                val (g, sg) = Sections.headingOf(s, p)
                if (g.isNotEmpty() && g != g0) { want++; g0 = g; s0 = "" }
                if (sg.isNotEmpty() && sg != s0) { want++; s0 = sg }
            }
            assertEquals("${s.id}: heading rows", want, rows.count { it.kind == DrawerRow.Kind.HEADING })
        }
    }

    @Test
    fun `D3 an item reports its target, headings do not react`() {
        val picked = mutableListOf<DrawerTarget>()
        val rows = listOf(
            DrawerRow("GROUP", DrawerRow.Kind.TITLE),
            DrawerRow("LAUNCHER", DrawerRow.Kind.HEADING),
            DrawerRow("Presets", DrawerRow.Kind.ITEM, 0, DrawerTarget.Page("config", "presets", "Presets")),
            DrawerRow("Search", DrawerRow.Kind.ITEM, 0, DrawerTarget.Action("search")),
        )
        showKit { DrawerList(rows, { picked += it }) }
        compose.onNodeWithTag(drawerRowTag(0)).performClick()
        compose.onNodeWithTag(drawerRowTag(1)).performClick()
        assertTrue(picked.isEmpty())
        compose.onNodeWithTag(drawerRowTag(2)).performClick()
        compose.onNodeWithTag(drawerRowTag(3)).performClick()
        assertEquals(listOf(DrawerTarget.Page("config", "presets", "Presets"), DrawerTarget.Action("search")), picked)
        compose.onAllNodesWithTag(drawerRowTag(4)).assertCountEquals(0)
    }

    @Test
    fun `D4 the Profile badge sits above the menu with Cloud Account's identity, or says where it lives`() {
        // #1693 the identity is Cloud Account's (read over its provider); with Cloud Account absent the badge names it.
        val profile = AccountClient.profile(ctx)
        show(HomeDrawerFragment.newInstance())
        compose.waitForIdle()
        compose.onNodeWithTag(HomeDrawerFragment.TAG_NAME).assert(hasText(profile.name.ifBlank { HomeDrawerFragment.NO_PROFILE }))
        compose.onNodeWithTag(HomeDrawerFragment.TAG_EMAIL).assertExists()
        compose.onNodeWithTag(HomeDrawerFragment.TAG_AVATAR).assertExists()
        compose.onNodeWithTag(HomeDrawerFragment.TAG_APP_BANNER).assertExists()
    }
}

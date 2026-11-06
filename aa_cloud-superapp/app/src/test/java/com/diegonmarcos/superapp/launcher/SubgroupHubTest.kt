package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1545 Configs ▸ Setup ▸ Network ▸ Watchdog, the in-app hub over the Network pages.
 *
 *   H1 nav order: Watchdog is the FIRST Network page and the Configs ▸ Network row reads Watchdog, then the rest
 *   H2 the grid is every other Network page, in order, derived from the declaration
 *   H3 routing: in-app pages are page:config/<id>; apps / sections / Cloud Store hand-offs keep their declared action
 *   H4 the page itself routes to SubgroupHubFragment (not the C3-Watchdog APK) and draws one Cloud ▸ Apps tile per entry
 *   H5 Cloud ▸ Apps ▸ Configs opens page:config/watchdog where Firewall was
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SubgroupHubTest : KitPageHarness() {

    private val config get() = Sections.byId("config")!!
    private val network get() = config.pages.filter { it.subgroup == "Network" }
    private val tiles get() = SubgroupHubFragment.tilesFor("config", "watchdog")

    @Test
    fun `H1 Watchdog leads the Network nav`() {
        assertEquals("watchdog", network.first().id)
        assertEquals("Watchdog", network.first().label)
        assertEquals("Network", network.first().hubOfSubgroup)
        assertTrue("an in-app page, not a launch", network.first().action.isBlank())
    }

    @Test
    fun `H2 the grid is every other Network page, in nav order`() {
        assertEquals(network.drop(1).map { it.id }, tiles.map { it.id })
        assertTrue("it lists Firewall and DNS", tiles.map { it.id }.containsAll(listOf("firewall", "dns")))
        assertTrue("never itself", tiles.none { it.id == "watchdog" })
        assertEquals(network.drop(1).map { it.label }, tiles.map { it.label })
    }

    @Test
    fun `H3 pages navigate in-app, apps keep their own declared action`() {
        val by = tiles.associateBy { it.id }
        assertEquals("page:config/firewall", by.getValue("firewall").target)
        assertEquals("page:config/dns", by.getValue("dns").target)
        assertEquals("page:config/wifi", by.getValue("wifi").target)
        assertEquals("action:open_webserver", by.getValue("webserver").target)
        assertEquals("action:open_apps_access", by.getValue("apps-access").target)
        assertEquals("section:wg", by.getValue("wg").target)
        for (p in network.drop(1)) assertEquals(p.id, Sections.hubTarget("config", p), by.getValue(p.id).target)
    }

    @Test
    fun `H4 the page is the hub fragment and draws a Cloud Apps tile per entry`() {
        val frag = SectionPages.pagesFor("config").first { it.id == "watchdog" }.factory()
        assertTrue("routes to the hub: ${frag.javaClass.simpleName}", frag is SubgroupHubFragment)
        show(frag)
        compose.onNodeWithTag(SubgroupHubFragment.TAG_GRID).assertExists()
        for (t in tiles) compose.onNodeWithTag(GroupedTilesFragment.tileTag(t.target)).assertExists()
    }

    @Test
    fun `H5 Cloud Apps Configs opens the Watchdog page where Firewall was`() {
        val row = Sections.all().flatMap { it.tileGroups }.single { it.title == "Configs" }.tiles.filter { !it.separator }
        assertTrue(row.any { it.label == "Watchdog" && it.target == "page:config/watchdog" })
        assertTrue("no Firewall tile left", row.none { it.label == "Firewall" })
    }
}

package com.diegonmarcos.superapp.apps

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.appstore.FleetIdentity
import com.diegonmarcos.superapp.appstore.FleetIdentityFragment
import com.diegonmarcos.superapp.appstore.FleetIdentityPage
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.ui.KitPageHarness
import java.io.File
import org.json.JSONObject
import com.diegonmarcos.superapp.uikit.KitPartTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Configs > Network > Apps Mesh > Identity (libs:appstore FleetIdentityPage):
 *
 *   I1 the baked roster is every fleet app of data/ipc-mesh.json, and the page draws a row for each
 *   I2 the shared terminal group (data/fleet-identity.json) is drawn, with cld.termux and cld.termux.nix
 *      in it; pending (own uid each) until its value is declared, then their rows carry it
 *   I3 a live uid that disagrees with the declaration is flagged; one that agrees is not
 *   I4 Identity is the Apps Mesh tab beside IPC and builds its host
 *   I6/I7 (#1096) rows sort by uid by default: a shared uid sits together under one titled group, a group of
 *   several apps is highlighted, and the sort switch (uid, name, package) reorders
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetIdentityPageTest : KitPageHarness() {

    private fun declaredPkgs(): Set<String> {
        val m = JSONObject(File("../data/ipc-mesh.json").readText()).getJSONArray("members")
        return (0 until m.length()).map { m.getJSONObject(it).getString("pkg") }.toSet()
    }

    @Test
    fun `I1 the Identity tab renders a row per fleet app`() {
        val reg = FleetIdentity.baked()
        assertEquals(declaredPkgs(), reg.members.map { it.pkg }.toSet())
        showKit { FleetIdentityPage(registry = reg, live = emptyMap()) }
        compose.waitForIdle()
        for (r in FleetIdentity.rows(reg, emptyMap())) compose.onNodeWithTag("kit:listrow:" + r.tag, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I2 the shared terminal group is shown`() {
        val reg = FleetIdentity.baked()
        val g = reg.groups.firstOrNull { it.id == "terminal" }
        assertNotNull("data/fleet-identity.json declares the terminal group", g)
        assertEquals(setOf("cld.termux", "cld.termux.nix", "com.diegonmarcos.clouddrive"), g!!.members.toSet())
        val rows = FleetIdentity.rows(reg, emptyMap()).associateBy { it.member.pkg }
        // pending until cloud-shared-store declares the value: each terminal is on its own uid today
        val want = g.sharedUserId ?: FleetIdentity.OWN
        assertEquals(want, rows.getValue("cld.termux").declared)
        assertEquals(want, rows.getValue("cld.termux.nix").declared)
        if (g.sharedUserId != null) assertEquals(listOf("cld.termux.nix", "com.diegonmarcos.clouddrive"), rows.getValue("cld.termux").sharesWith)
        assertTrue("no app outside the group is shared", rows.values.filter { it.group == null }.all { it.declared == FleetIdentity.OWN })
        // the terminals' sharedUserId=${'$'}{TERMUX_PACKAGE_NAME} is their own package: own uid, no mismatch
        if (g.sharedUserId == null) {
            val m = reg.members.first { it.pkg == "cld.termux" }
            assertNull(FleetIdentity.problem(m, null, FleetIdentity.Seen(10200, "cld.termux", null), emptyMap()))
        }
        showKit { FleetIdentityPage(registry = reg, live = emptyMap()) }
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_GROUP + "terminal", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I3 a live uid that disagrees is flagged`() {
        val g = FleetIdentity.Group("terminal", "Terminals", "cld.shared", listOf("a.one", "a.two"))
        val reg = FleetIdentity.Registry(listOf(g),
            listOf(FleetIdentity.Member("one", "a.one", "One"), FleetIdentity.Member("two", "a.two", "Two"), FleetIdentity.Member("three", "a.three", "Three")))
        val good = mapOf("a.one" to FleetIdentity.Seen(10100, "cld.shared", "ABCD1234"),
                         "a.two" to FleetIdentity.Seen(10100, "cld.shared", "ABCD1234"),
                         "a.three" to FleetIdentity.Seen(10101, null, "ABCD1234"))
        assertTrue(FleetIdentity.rows(reg, good).all { it.problem == null })
        val split = good + ("a.two" to FleetIdentity.Seen(10102, "cld.shared", null))
        assertNotNull(FleetIdentity.rows(reg, split).first { it.member.pkg == "a.one" }.problem)
        val stray = good + ("a.three" to FleetIdentity.Seen(10100, "cld.shared", null))
        assertNotNull(FleetIdentity.rows(reg, stray).first { it.member.pkg == "a.three" }.problem)
        assertNull(FleetIdentity.rows(reg, emptyMap()).first().problem)
        showKit { FleetIdentityPage(registry = reg, live = stray) }
        compose.waitForIdle()
        compose.onNodeWithTag("kit:listrow:" + FleetIdentity.TAG_ROW + "three", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I5 the page opens outside CloudKitTheme (the Store host) and an uninstalled app is a row state`() {
        val reg = FleetIdentity.baked()
        val live = FleetIdentity.probe(compose.activity.applicationContext, reg)  // most fleet apps are not installed here: no throw
        assertTrue("an uninstalled app has no live row: ${live.keys}", "cld.termux" !in live)
        compose.setContent { com.diegonmarcos.superapp.uikit.KitDenseMaterial(androidx.compose.material3.darkColorScheme()) { FleetIdentityPage() } }
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `I4 Identity is Cloud Store Access only, Configs keeps one Apps Access tile`() {
        val ids = Sections.byId("config")!!.allPages.map { it.id }   // #1737 Apps Access is a hidden page (reached from OS Perms and the Agents hub)
        assertTrue("Configs declares no identity page: $ids", "identity" !in ids && "apps-mesh" !in ids)
        assertTrue("Configs has the Apps Access tile: $ids", "apps-access" in ids)
        show(FleetIdentityFragment())
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
    }

    private val groupReg = FleetIdentity.Registry(
        listOf(FleetIdentity.Group("terminal", "Terminals", "cld.shared", listOf("a.one", "a.two"))),
        listOf(FleetIdentity.Member("one", "a.one", "One"), FleetIdentity.Member("two", "a.two", "Two"),
               FleetIdentity.Member("three", "a.three", "Three"), FleetIdentity.Member("four", "a.four", "Four")))
    private val groupLive = mapOf("a.one" to FleetIdentity.Seen(10200, "cld.shared", "AB"), "a.two" to FleetIdentity.Seen(10200, "cld.shared", "AB"),
                                  "a.three" to FleetIdentity.Seen(10100, "a.three", "AB"))

    @Test
    fun `I6 apps group by uid, a shared uid together, and the sort modes reorder`() {
        val rows = FleetIdentity.rows(groupReg, groupLive)
        val groups = FleetIdentity.byUid(rows)
        assertEquals(listOf<Int?>(10100, 10200, null), groups.map { it.uid })
        assertEquals(listOf("a.one", "a.two"), groups[1].rows.map { it.member.pkg })
        assertTrue(groups[1].shared)
        assertEquals("uid 10200 · cld.shared · 2 apps", groups[1].title)
        assertFalse(groups[0].shared)
        assertEquals("a sharedUserId naming the app's own package names no group", "uid 10100", groups[0].title)
        assertEquals("not installed", groups[2].title)
        assertFalse(groups[2].shared)
        assertEquals(listOf("Four", "One", "Three", "Two"), FleetIdentity.sorted(rows, FleetIdentity.Sort.NAME).map { it.member.label })
        assertEquals(listOf("a.four", "a.one", "a.three", "a.two"), FleetIdentity.sorted(rows, FleetIdentity.Sort.PACKAGE).map { it.member.pkg })
        assertEquals(FleetIdentity.Sort.UID, FleetIdentity.Sort.of("unknown"))
        assertEquals(FleetIdentity.Sort.values().toList(), FleetIdentity.Sort.values().map { FleetIdentity.Sort.of(it.id) })
    }

    @Test
    fun `I7 the page opens sorted by uid with group titles, highlights a shared uid, and the switch drops the groups`() {
        showKit { FleetIdentityPage(registry = groupReg, live = groupLive) }
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_UID + "10200", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FleetIdentity.TAG_UID + "10100", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FleetIdentity.TAG_UID + "none", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FleetIdentity.TAG_SHARED + "10200", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FleetIdentity.TAG_SHARED + "10100", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(KitPartTags.segment(FleetIdentity.TAG_SORT, "label"), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_UID + "10200", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("kit:listrow:" + FleetIdentity.TAG_ROW + "one", useUnmergedTree = true).assertExists()
    }
}

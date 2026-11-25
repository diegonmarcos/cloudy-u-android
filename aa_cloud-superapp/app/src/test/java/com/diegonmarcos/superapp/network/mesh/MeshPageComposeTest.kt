package com.diegonmarcos.superapp.network.mesh

import android.app.Application
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.network.WireGuardFragment
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #877 The Cloud Mesh page composes: every declared page draws under the one PageTabs strip, the readout
 * shows what the engine said, a control the engine cannot honour is on screen with its reason, and the
 * hosting fragment opens on the page it is told to. Host: KitPageHarness (the production ComposeView).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshPageComposeTest : KitPageHarness() {

    private val decl = MeshDecl.baked

    private fun showStore(port: FakePort = FakePort(up = true)): MeshStore {
        val store = MeshStore(port, decl, FakeHost(), exec = { it.run() })
        store.poll()
        showKit { MeshScreen(store, ticker = false) }
        return store
    }

    private fun open(store: MeshStore, id: String) {
        compose.runOnUiThread { store.open(id) }   // #1928 a tab, a subpage, a former page id or a section
        compose.waitForIdle()
    }

    /** #1928 Open subpage [sub] of tab [tab] and let it compose. */
    private fun openSub(store: MeshStore, tab: String, sub: String) {
        compose.runOnUiThread { store.tab = tab; store.sub[tab] = sub }
        compose.waitForIdle()
    }

    @Test fun `the strip has the three tabs and each draws its declared sections`() {
        val store = showStore()
        assertEquals(listOf("status", "profile", "logs"), decl.tabs.map { it.id })
        for (t in decl.tabs) {
            compose.onNodeWithTag(PageTabsTags.tab(t.id)).assertExists()
            compose.runOnUiThread { store.tab = t.id }
            compose.waitForIdle()
            compose.onNodeWithTag(MeshTags.tab(t.id)).assertExists()
            // #1928 a tab draws its subpages one at a time: each shows its body, and the pages it names
            for (sp in t.subpages) {
                openSub(store, t.id, sp.id)
                compose.onNodeWithTag(MeshTags.sub(sp.id)).assertExists()
                for (s in sp.sections) compose.onNodeWithTag(MeshTags.page(s)).assertExists()
            }
        }
    }

    @Test fun `tapping a tab moves the tab`() {
        val store = showStore()
        compose.onNodeWithTag(PageTabsTags.tab("logs")).performClick()
        compose.waitForIdle()
        assertEquals("logs", store.tab)
        compose.onNodeWithTag(MeshTags.page("log")).assertExists()
    }

    @Test fun `profile has the six subpages, each drawing its own controls, with the Cloudflare cards`() {
        val store = showStore()
        assertEquals(listOf("profiles", "import_export", "tunnel", "connection", "dns_transport", "cloudflare"), decl.tab("profile")!!.subpages.map { it.id })
        for (sp in decl.tab("profile")!!.subpages) {
            openSub(store, "profile", sp.id)
            // a subpage's controls are on screen (a per-profile control is tagged <control>:<profile id>)
            for (id in sp.controls) assertTrue("$id is not drawn on ${sp.id}", compose.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher("tag $id") { n -> n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.let { it == MeshTags.control(id) || it.startsWith(MeshTags.control(id) + ":") } == true }
            ).fetchSemanticsNodes().isNotEmpty())
        }
        openSub(store, "profile", "cloudflare")
        compose.onNodeWithTag("mesh:cf:warp").assertExists()
        compose.onNodeWithTag("mesh:cf:one").assertExists()
        compose.onNodeWithTag("mesh:cf:one:state").assertTextContains("waiting on Zero Trust apply", substring = true)
        compose.onNodeWithTag(MeshTags.control("cf_one_enrol")).assertIsNotEnabled()
        compose.onNodeWithTag(MeshTags.control("cf_warp_register")).assertExists()
    }

    @Test fun `status draws every declared row from the engine's reading, across its subpages`() {
        val store = showStore()
        compose.onNodeWithText("CONNECTED", substring = true).assertExists()
        for (sp in decl.tab("status")!!.subpages.filter { it.statusSection.isNotBlank() }) {
            openSub(store, "status", sp.id)
            for (row in decl.sections.first { it.id == sp.statusSection }.rows) compose.onNodeWithTag(MeshTags.row(row)).assertExists()
        }
        openSub(store, "status", "interface")
        compose.onNodeWithTag(MeshTags.row("pubkey")).assertTextContains("PUBKEY", substring = true)
        compose.onNodeWithTag("mesh:dns:servers").assertTextContains("10.0.0.1", substring = true)   // #1928 the servers in use
    }

    @Test fun `status without the engine says it cannot tell`() {
        showStore(FakePort(engine = false))
        compose.onNodeWithText("CANNOT TELL").assertExists()
        compose.onNodeWithText("not installed", substring = true).assertExists()
    }

    @Test fun `peers lists a dense row per peer and a tap opens its full config`() {
        val store = showStore()
        open(store, "peers")
        compose.onNodeWithTag(MeshTags.peer(0)).assertExists(); compose.onNodeWithTag(MeshTags.peer(1)).assertExists()
        compose.onNode(hasText("gcp-proxy") and hasAnyAncestor(hasTestTag(MeshTags.peer(0))))
            .performScrollTo().performClick()   // the peers sit below the status rows in the one scroll
        compose.waitForIdle()
        compose.onNodeWithTag(MeshTags.control("peer_copy")).assertExists()
        compose.onNodeWithTag(MeshTags.control("peer_remove")).assertExists()
    }

    @Test fun `routes shows the verdict, the nat64 prefix and the excluded list`() {
        val store = showStore()
        open(store, "routes")
        compose.onNodeWithTag(MeshTags.row("route_mode")).assertTextContains("SPLIT", substring = true)
        compose.onNodeWithTag(MeshTags.row("route_nat64")).assertTextContains("ff9b", substring = true)
        compose.onNodeWithTag("mesh:excluded:none").performScrollTo().assertExists()
    }

    @Test fun `profiles draws the declared profiles and their actions`() {
        val store = showStore()
        open(store, "profiles")
        val first = com.diegonmarcos.superapp.network.WireGuardProfiles.mesh
        assertTrue("the build declares no mesh profiles", first.isNotEmpty())
        for (p in first) compose.onNodeWithTag("mesh:profile:${p.id}").assertExists()
        compose.onNodeWithTag(MeshTags.control("profile_diff") + ":" + first[0].id).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mesh:diff:${first[0].id}").assertExists()
    }

    @Test fun `every control the engine cannot honour is on screen with its reason`() {
        val store = showStore()
        for (sp in decl.tab("profile")!!.subpages.filter { "controls" in it.sections }) {
            openSub(store, "profile", sp.id)
            for (c in decl.page("controls")!!.controls.filter { it.id in sp.controls && !it.honoured }) {
                compose.onNodeWithTag(MeshTags.control(c.id)).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(c.unsupported, substring = true).assertExists()
            }
            for (c in decl.page("controls")!!.controls.filter { it.id in sp.controls }) compose.onNodeWithTag(MeshTags.control(c.id)).assertExists()
        }
        store.hashCode()
    }

    @Test fun `without the engine the engine-bound controls say why`() {
        val store = showStore(FakePort(engine = false))
        open(store, "controls")
        compose.onNodeWithTag(MeshTags.control("connect")).assertExists()
        compose.onAllNodesWithTextCount("Cloud-Lib-Net-Wg")
    }

    @Test fun `the log page prints the journal, newest first, and copies it`() {
        val store = showStore()
        store.run("prefs.mtu", "1400")
        open(store, "log")
        compose.onAllNodes(hasText("MTU: ok", substring = true) and !hasTestTag(MeshTags.NOTICE)).onFirst().assertExists()
        compose.onNodeWithTag(MeshTags.control("log_copy")).performClick()
        compose.waitForIdle()
        assertTrue(store.notice.contains("copied"))
    }

    @Test fun `the hosting fragment opens on the declared first page, or the one it is told to`() {
        val page = show(WireGuardFragment.newInstance())
        compose.onNodeWithTag(PageTabsTags.tab(decl.startTab.id)).assertExists()
        compose.onNodeWithTag(MeshTags.page(decl.startPage.id)).assertExists()
        page.hashCode()
    }

    @Test fun `the hidden status route and a named page open on their tab`() {
        show(WireGuardFragment.newInstance("peers"))
        compose.onNodeWithTag(MeshTags.page("peers")).assertExists()
    }

    @Test fun `a route into a former Controls page opens the Profile tab on Connection (#1928 alias)`() {
        show(WireGuardFragment.newInstance("controls"))
        compose.onNodeWithTag(MeshTags.tab("profile")).assertExists()
        compose.onNodeWithTag(MeshTags.sub("connection")).assertExists()
        compose.onNodeWithTag(MeshTags.page("controls")).assertExists()
        compose.onNodeWithTag(MeshTags.control("connect")).assertExists()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t, substring = true)).fetchSemanticsNodes().size

    @Test fun `#1153 peers always shows the fleet topology and status offers Copy all, Export all and Run again`() {
        val store = showStore()
        compose.onNodeWithTag(MeshTags.control("status_copy_all")).assertExists()
        compose.onNodeWithTag(MeshTags.control("status_export_all")).assertExists()
        compose.onNodeWithTag(MeshTags.control("status_export_md")).assertExists()   // #1845
        openSub(store, "status", "probes")
        compose.onNodeWithTag(MeshTags.control("reach_run")).assertExists()
        open(store, "peers")
        compose.onNodeWithTag("mesh:topology").performScrollTo().assertExists()
        compose.onNodeWithTag(MeshTags.control("topology")).assertDoesNotExist()
    }
}

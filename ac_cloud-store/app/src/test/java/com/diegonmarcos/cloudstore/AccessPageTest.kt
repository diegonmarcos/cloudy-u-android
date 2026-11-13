package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.fleetdata.DeviceAccess
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.appstore.FleetIdentity
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import org.junit.Assert.assertEquals
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

/**
 * #1067 Access > Identity crashed the Store: FleetIdentityPage drew kit parts outside CloudKitTheme
 * (LocalKitPalette has no default). The page is opened here exactly as MainActivity hosts it - a bare
 * KitDenseMaterial + Surface, no CloudKitTheme - through AccessPage(PAGE_IDENTITY). Nothing is
 * installed in Robolectric, so every fleet row also proves "not installed" is a row state, not a throw.
 *
 * #1096 the page could not be scrolled: it was a plain Column and only the SuperApp's fragment host wrapped it in a
 * scroll, so in the Store every row below the fold was out of reach. The page now owns its scroll.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccessPageTest {
    private val compose = createAndroidComposeRule<FragmentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    /** Identity opens on Direct access (#1718); the two Linux identity tests below choose that view. */
    @org.junit.Before fun linuxIdentityView() { AccessPages.identityView = AccessPages.VIEW_UID; AccessPages.ipcView = AccessPages.VIEW_GATEWAY }
    @org.junit.After fun defaultViews() { AccessPages.identityView = AccessPages.VIEW_DIRECT; AccessPages.ipcView = AccessPages.VIEW_GATEWAY }

    /**
     * #1718 every Access page opens in the Store shell with nothing installed and no dataset served (Robolectric has no fleet
     * data provider): each says why instead of throwing. The ids that left (mesh, mcpapi, cloud) land on the page that took
     * their content, and no Cloud Perms or Apps Mesh page exists any more.
     */
    @Test
    fun `every Access page opens with no dataset served, and the ids that left land on their new page`() {
        assertEquals(PAGE_IPC, AccessPages.resolve("mesh")); assertEquals(PAGE_APIS, AccessPages.resolve("mcpapi")); assertEquals(PAGE_IDENTITY, AccessPages.resolve("cloud"))
        val declared = MainActivity.NAV.section(MainActivity.TAB_ACCESS)!!.pages.map { it.id }
        assertEquals(listOf(PAGE_MCP, PAGE_APIS, PAGE_IPC, PAGE_IDENTITY, PAGE_ANDROID), declared)
        AccessPages.identityView = AccessPages.VIEW_DIRECT
        var shown by mutableStateOf(declared.first())
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { key(shown) { AccessPage(shown) } } } }
        for (page in declared + listOf("mesh", "mcpapi", "cloud")) {
            shown = page
            compose.waitForIdle()
            val want = when (AccessPages.resolve(page)) {
                PAGE_MCP, PAGE_APIS, PAGE_IPC -> DeviceAccessTags.PROBLEM
                PAGE_IDENTITY -> DirectAccess.TAG_UID
                else -> OsReach.TAG_CARD
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag(want, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
            if (want == DirectAccess.TAG_UID) compose.onNodeWithTag(DirectAccess.TAG_SUMMARY, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `a broken dataset is a reason on every model page`() {
        val broken = DeviceAccess.parse("{")
        var shown by mutableStateOf(0)
        compose.setContent {
            KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { key(shown) {
                when (shown) { 0 -> DeviceMcpPage(broken); 1 -> DeviceApisPage(broken); 2 -> DeviceIpcPage(broken); else -> DirectAccessPage(broken) }
            } } }
        }
        for (i in 0..3) {
            shown = i
            compose.waitForIdle()
            if (i < 3) compose.onNodeWithTag(DeviceAccessTags.PROBLEM, useUnmergedTree = true).assertExists()
            else compose.onNodeWithTag(DirectAccess.TAG_UID, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun `Access Identity opens in the Store shell and draws a row per fleet app`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { AccessPage(PAGE_IDENTITY) } } }
        compose.waitForIdle()
        Thread.sleep(300) // the live probe thread: it must end without killing the process
        compose.waitForIdle()
        compose.onNodeWithTag(FleetIdentity.TAG_SUMMARY, useUnmergedTree = true).assertExists()
        val reg = FleetIdentity.baked()
        for (r in FleetIdentity.rows(reg, emptyMap())) compose.onNodeWithTag("kit:listrow:" + r.tag, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `Access Identity scrolls to its last row inside a bounded pane`() {
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.height(300.dp)) { AccessPage(PAGE_IDENTITY) } } }
        compose.waitForIdle()
        val last = FleetIdentity.sorted(FleetIdentity.rows(FleetIdentity.baked(), emptyMap()), FleetIdentity.Sort.UID).last()
        val row = compose.onNodeWithTag("kit:listrow:" + last.tag, useUnmergedTree = true)
        row.assertIsNotDisplayed()   // the page is taller than its pane, else this proves nothing
        row.performScrollTo()        // fails when no enclosing scroll container exists (the #1096 bug)
        row.assertIsDisplayed()
    }
}

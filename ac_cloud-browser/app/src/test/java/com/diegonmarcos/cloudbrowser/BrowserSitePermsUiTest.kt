package com.diegonmarcos.cloudbrowser

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.browser.BrowserConfig
import com.diegonmarcos.superapp.browser.BrowserSheet
import com.diegonmarcos.superapp.browser.BrowserPermsScreen
import com.diegonmarcos.superapp.browser.BrowserSitePermissions
import com.diegonmarcos.superapp.browser.BrowserSitePolicy
import com.diegonmarcos.superapp.browser.BrowserTabStrip
import com.diegonmarcos.superapp.browser.PermSite
import com.diegonmarcos.superapp.browser.TAB_ROW_TOOLS_TAG
import com.diegonmarcos.superapp.browser.sheet
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import org.json.JSONObject
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
import java.io.File

/**
 * #1401 Browsing Tools on the tab row, its five sections, and the Perms page DRAWN from the SHIPPED build.json;
 * the per-site state persists in the browser_site_permissions prefs. Compose under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserSitePermsUiTest {

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

    private val cfg = BrowserConfig.parse(JSONObject(File("../build.json").readText()).getJSONObject("ui").getJSONObject("browser").toString())
    private val palette = KitPalette.fromArgb(0xFF1A0033.toInt(), 0xFF2D0A55.toInt(), 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFF7C3AED.toInt(), 0x33FFFFFF, 0xFF000000.toInt())

    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun tap(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()

    @Test
    fun `the tab row ends with the tools icon and a tap opens Browsing Tools`() {
        var opened = 0
        compose.setContent { CloudKitTheme(palette) { BrowserTabStrip(emptyList(), "", "", 0, onSelect = {}, onNew = {}, onTools = { opened++ }) } }
        compose.waitForIdle()
        compose.onNodeWithTag(TAB_ROW_TOOLS_TAG, useUnmergedTree = true).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the sheet shows the five sections in order and every former action`() {
        val facts = mapOf("page" to true, "can_go_back" to true, "can_go_forward" to true, "text_tools" to true, "profile_present" to true)
        val sections = cfg.menu.listSections(facts).map { (s, rows) -> s.label to rows.map { it.sheet() } }
        assertEquals(listOf("Page Actions", "Page Tools", "Site Data", "Site Perms", "DevTools"), sections.map { it.first })
        assertEquals(listOf("site_settings"), sections.first { it.first == "Site Perms" }.second.map { it.id })
        compose.setContent { CloudKitTheme(palette) { BrowserSheet(emptyList(), sections, onPick = {}, onDismiss = {}) } }
        compose.waitForIdle()
        listOf("back", "forward", "reload", "bookmark", "download_page", "translate", "desktop", "hard_refresh", "light_refresh",
            "find", "share", "copy_url", "open_external", "zoom", "summarize_topics", "reader", "print", "add_to_home", "save_page",
            "save_site", "fill_profile", "site_settings", "shield_trackers", "shield_js", "shield_images", "clear_site_cookies",
            "clear_site_data", "site_autocomplete", "view_source", "dev_console", "dev_network", "dev_elements", "dev_storage",
            "dev_remote_debug").forEach { assertTrue("$it is reachable", has("browser:menu:$it")) }
    }

    @Test
    fun `the Perms list puts the most used first and is complete`() {
        val top = listOf("geolocation", "camera", "microphone", "notifications", "popups", "clipboard", "autoplay", "javascript",
            "third_party_cookies", "downloads", "site_storage", "protected_media")
        val ordered = BrowserSitePolicy.ordered(cfg.sitePerms).map { it.id }
        assertEquals(top, ordered.take(top.size))
        assertEquals(38, ordered.size)
        cfg.sitePerms.filterNot { it.webview }.forEach { assertTrue("${it.id} says why", it.why.startsWith("not supported in WebView")) }
    }

    @Test
    fun `a per-site state persists, beats the global default, and Reset returns to it`() {
        val app = RuntimeEnvironment.getApplication()
        val camera = cfg.sitePerms.first { it.id == "camera" }
        BrowserSitePermissions(app).setDefault("camera", BrowserSitePolicy.DENY)
        assertEquals(BrowserSitePolicy.DENY, BrowserSitePermissions(app).resolve("a.example.org", camera))
        BrowserSitePermissions(app).set("example.org", "camera", BrowserSitePolicy.ALLOW)
        // a fresh instance reads the same prefs: the state survived
        assertEquals(BrowserSitePolicy.ALLOW, BrowserSitePermissions(app).resolve("a.example.org", camera))
        assertTrue(BrowserSitePolicy.hostsWithRules(BrowserSitePermissions(app).rules()).keys == setOf("example.org"))
        BrowserSitePermissions(app).reset("example.org", "camera")
        assertEquals(BrowserSitePolicy.DENY, BrowserSitePermissions(app).resolve("a.example.org", camera))
    }

    @Test
    fun `the state maps to what WebView enforces`() {
        assertTrue(BrowserSitePolicy.autoplayNeedsGesture(BrowserSitePolicy.DENY))
        assertTrue(!BrowserSitePolicy.autoplayNeedsGesture(BrowserSitePolicy.ALLOW))
        assertEquals(listOf(0, 1, 2), listOf(BrowserSitePolicy.ALLOW, BrowserSitePolicy.DENY, BrowserSitePolicy.ASK).map { BrowserSitePolicy.mixedContentMode(it) })
        assertTrue(!BrowserSitePolicy.popupAllowed(BrowserSitePolicy.DENY, true))
        assertTrue(BrowserSitePolicy.popupAllowed(BrowserSitePolicy.ASK, true) && !BrowserSitePolicy.popupAllowed(BrowserSitePolicy.ASK, false))
        assertTrue(!BrowserSitePolicy.onUnlessBlocked(BrowserSitePolicy.DENY))
    }

    @Test
    fun `tapping a state on the Perms page stores it, an unsupported one shows its reason`() {
        val set = mutableListOf<Pair<String, String>>()
        compose.setContent { CloudKitTheme(palette) {
            BrowserPermsScreen("Perms", "example.org", cfg.sitePerms, stateOf = { it.default }, defaultOf = { it.default }, hasOwn = { false },
                onSet = { p, v -> set += p.id to v }, onReset = {}, onClose = {})
        } }
        compose.waitForIdle()
        tap("browser:perm:camera:allow")
        assertEquals(listOf("camera" to "allow"), set)
        assertTrue("an unsupported perm shows its reason", has("browser:perm:nfc:unsupported"))
        assertTrue("and offers no state", !has("browser:perm:nfc:allow"))
    }

    @Test
    fun `Sites Permissions lists the sites with overrides, each with Edit and Reset`() {
        compose.setContent { CloudKitTheme(palette) {
            BrowserPermsScreen("Sites Permissions", null, cfg.sitePerms, stateOf = { it.default }, defaultOf = { it.default }, hasOwn = { false },
                onSet = { _, _ -> }, onReset = {}, onClose = {}, sites = listOf(PermSite("example.org", "camera allow")))
        } }
        compose.waitForIdle()
        assertTrue(has("browser:perms:site:example.org:edit") && has("browser:perms:site:example.org:reset"))
    }
}

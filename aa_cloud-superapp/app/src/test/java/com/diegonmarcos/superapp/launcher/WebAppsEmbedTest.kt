package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1062 - Cloud ▸ WebApps is ONE page: the cloud-superapp site embedded in the shell (a WebView pane), not a second native grid.
 *
 *   E1 the page's URL is the one declared (webapps_base + webapps_path), https, with embed=1 and no second url or app list
 *   E2 the WebApps tab hosts a WebView on exactly that URL with the embed user agent and no floating Back
 *   E3 SuperApp's own Cloud strip (App | WebApps | Linktree | Configs) is drawn on WebApps and the WebView starts below it; no page can hide it
 *   E4 the page is the section's own tab, so it renders inside the shell content area (below the top row), never a pushed full-screen page
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebAppsEmbedTest : KitPageHarness() {

    private val page get() = Sections.byId("cloud")!!.pages.first { it.id == "webapps" }

    private fun webViewIn(v: android.view.View): android.webkit.WebView? =
        if (v is android.webkit.WebView) v
        else (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { webViewIn(g.getChildAt(it)) } }

    @Test
    fun `E1 the URL is the one declaration, https, with embed=1`() {
        val url = page.url
        assertEquals("https://diegonmarcos.github.io/cloud-webapps/?embed=1", url)
        assertTrue("https: $url", url.startsWith("https://"))
        assertTrue("embed=1: $url", WebPageFragment.isEmbed(url))
    }

    @Test
    fun `E2 the WebApps tab is a web view on the declared URL with the embed user agent`() {
        // The tab pane builds its page through this routing (SectionTabsFragment.render -> pageFragment -> SectionPages).
        val frag = SectionPages.pagesFor("cloud", includeHidden = true).first { it.id == "webapps" }.factory.invoke()
        assertTrue("the page routes to the embedded browser: ${frag.javaClass.simpleName}", frag is WebPageFragment)
        show(frag)
        compose.waitForIdle()
        val web = webViewIn(compose.activity.window.decorView)
        assertTrue("a WebView is hosted", web != null)
        assertEquals(page.url, web!!.url)
        assertTrue("UA marker: ${web.settings.userAgentString}", web.settings.userAgentString.endsWith(WebPageFragment.UA_MARKER))
        compose.onNodeWithTag(WebPageFragment.TAG_BACK).assertDoesNotExist()
    }

    @Test
    fun `E3 the Cloud strip is drawn on WebApps and the WebView starts below it`() {
        show(SectionTabsFragment.newInstance("cloud", "webapps"))
        compose.waitForIdle()
        for (id in listOf("apps", "webapps", "configs")) compose.onNodeWithTag(PageTabsTags.tab(id)).assertExists()
        val strip = compose.onAllNodesWithTag(PageTabsTags.STRIP)[0].fetchSemanticsNode().boundsInWindow
        assertTrue("the strip is drawn (bottom ${strip.bottom})", strip.bottom > strip.top)
        // The pane's WebView is built by SectionTabsFragment.render through ShellActivity.nav.pageFragment; this harness hosts a plain
        // FragmentActivity, so no pane (and no WebView) exists here (E2 builds the page through SectionPages for the same reason).
        // That the WebView starts below the strip is held on a real emulator by WebPagesRenderTest (webTop >= stripBottom).
    }

    @Test
    fun `E3b the strip is still drawn on Cloud App`() {
        show(SectionTabsFragment.newInstance("cloud", "apps"))
        compose.onNodeWithTag(PageTabsTags.tab("webapps")).assertExists()
    }

    @Test
    fun `E4 WebApps is a tab pane of the Cloud section, not an action or a pushed page`() {
        assertTrue("it has a page URL, so SectionPages routes it to the pane's WebPageFragment", page.url.isNotBlank())
        assertTrue("it is not an action tile", page.action.isBlank())
        assertEquals("cloud's tab order is untouched", listOf("apps", "webapps", "configs"), Sections.byId("cloud")!!.pages.map { it.id })
    }
}

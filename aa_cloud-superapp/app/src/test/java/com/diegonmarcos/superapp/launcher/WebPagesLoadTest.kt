package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.ui.test.onNodeWithTag
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cloud > WebApps and Cloud > Linktree are live web pages in the embedded browser (WebPageFragment), never a bundled copy.
 *   W1 both declared pages route to a WebPageFragment on their declared https url
 *   W2 each page's WebView has a real size and loads exactly that url, over https, from the network (no asset or file)
 *   W3 only the embed page tells the site it is embedded (query + user-agent marker); the linktree site, which has no embed mode, is left alone
 *   W4 re-entering the page revalidates its document (LOAD_DEFAULT, never LOAD_NO_CACHE, so icons come from the cache), and a
 *      resume right behind the first load does not load again
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebPagesLoadTest : KitPageHarness() {

    private fun declared(id: String): String =
        Sections.byId("cloud")!!.allPages.first { it.id == id }.url.also { assertTrue("$id declares no url", it.isNotBlank()) }

    private fun pageFor(id: String): Fragment =
        SectionPages.pagesFor("cloud", includeHidden = true).first { it.id == id }.factory.invoke()

    private fun webViewOf(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) webViewOf(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun shown(id: String): Pair<WebPageFragment, WebView> {
        val page = pageFor(id)
        assertTrue("page:cloud/$id is not the embedded browser: ${page.javaClass.simpleName}", page is WebPageFragment)
        show(page)
        compose.waitForIdle()
        val web = webViewOf(compose.activity.window.decorView)
        assertNotNull("no WebView under page:cloud/$id", web)
        return page as WebPageFragment to web!!
    }

    @Test fun `W1 and W2 each page loads its declared https url, live, in a WebView with a size`() {
        for (id in listOf("webapps")) {   // #1930 the Linktree pane left the strip
            val url = declared(id)
            assertTrue("$id is not https: $url", url.startsWith("https://"))
            val (_, web) = shown(id)
            assertEquals("$id loaded something other than its declared url", url, shadowOf(web).lastLoadedUrl)
            // The pane the WebView is given (Robolectric's WebView has no renderer, so its own measured size is meaningless).
            val pane = compose.onNodeWithTag(WebPageFragment.TAG_WEB, useUnmergedTree = true).fetchSemanticsNode().size
            assertTrue("$id's WebView pane is ${pane.width}x${pane.height}", pane.width > 0 && pane.height > 0)
            compose.runOnUiThread { compose.activity.supportFragmentManager.beginTransaction().remove(
                compose.activity.supportFragmentManager.fragments.last()).commitNow() }
        }
    }

    @Test fun `W3 the embed page is told it is embedded and linktree is not`() {
        val (_, apps) = shown("webapps")
        assertTrue("embed page lost its user-agent marker", apps.settings.userAgentString.endsWith(WebPageFragment.UA_MARKER))
        assertTrue(declared("webapps").contains("embed=1"))
        // #1930 the Linktree pane left the strip: its old id is a page alias of Apps (test-cloud-setup-tab-1930.sh)
        assertTrue("the removed lnktree page is no longer declared", Sections.byId("cloud")!!.pages.none { it.id == "lnktree" })
    }

    @Test fun `W4 re-entering revalidates the document only, never bypasses the cache for subresources`() {
        for (id in listOf("webapps")) {   // #1930 the Linktree pane left the strip
            val (page, web) = shown(id)
            // LOAD_NO_CACHE refetched every icon on every entry and tripped the edge rate limit (broken "?" icons, 2026-10-17).
            assertEquals("the first show must keep the HTTP cache for subresources", WebSettings.LOAD_DEFAULT, web.settings.cacheMode)
            assertEquals("the first show must revalidate its document", "load", page.lastEntry)
            assertEquals("no-cache", WebPageFragment.REVALIDATE_DOCUMENT["Cache-Control"])
            // Pretend some time passed, then leave and come back.
            compose.runOnUiThread {
                page.lastEntry = ""
                page.lastLoadAt = -2 * WebPageFragment.REENTRY_DEBOUNCE_MS
                val fm = compose.activity.supportFragmentManager
                fm.beginTransaction().setMaxLifecycle(page, Lifecycle.State.STARTED).commitNow()
                fm.beginTransaction().setMaxLifecycle(page, Lifecycle.State.RESUMED).commitNow()
            }
            compose.waitForIdle()
            assertTrue("$id was not reloaded on re-entry", page.lastEntry.isNotEmpty())
            assertEquals("$id re-entry bypassed the HTTP cache", WebSettings.LOAD_DEFAULT, web.settings.cacheMode)
            assertEquals(declared(id), shadowOf(web).lastLoadedUrl)
            // A resume straight behind a load is the same entry: it must not load again.
            compose.runOnUiThread {
                page.lastEntry = ""
                val fm = compose.activity.supportFragmentManager
                fm.beginTransaction().setMaxLifecycle(page, Lifecycle.State.STARTED).commitNow()
                fm.beginTransaction().setMaxLifecycle(page, Lifecycle.State.RESUMED).commitNow()
            }
            assertEquals("a resume right behind a load loaded twice", "", page.lastEntry)
            compose.runOnUiThread { compose.activity.supportFragmentManager.beginTransaction().remove(page).commitNow() }
        }
    }

    @Test fun `W5 a stalled first load recovers on resume, a reader navigation is not overridden`() {
        val (page, _) = shown("webapps")
        fun reenter() = compose.runOnUiThread {
            page.lastEntry = ""
            page.lastLoadAt = -2 * WebPageFragment.REENTRY_DEBOUNCE_MS
            val fm = compose.activity.supportFragmentManager
            fm.beginTransaction().setMaxLifecycle(page, Lifecycle.State.STARTED).commitNow()
            fm.beginTransaction().setMaxLifecycle(page, Lifecycle.State.RESUMED).commitNow()
        }
        // The first load never finished (no navigation by the reader): coming back must load the declared page again.
        assertTrue("a first load with no reader navigation is flagged as one", !page.userNavigated)
        reenter(); compose.waitForIdle()
        assertTrue("a stalled first load was not reloaded on resume", page.lastEntry.isNotEmpty())
        // The reader tapped a link and the page paused before it committed: resume must leave it alone.
        compose.runOnUiThread { page.userNavigated = true }
        reenter(); compose.waitForIdle()
        assertEquals("resume overrode the reader's navigation", "", page.lastEntry)
        compose.runOnUiThread { compose.activity.supportFragmentManager.beginTransaction().remove(page).commitNow() }
    }
}

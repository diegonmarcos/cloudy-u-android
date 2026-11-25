package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Back on an embedded WebView page follows the page's own history (Cloud > WebApps, Linktree, any WebView page), once, through
 * the shell's BackHandler contract that both the top-right Back arrow (ShellActivity.goBack) and the system Back
 * (ShellActivity.onBackPressed) consult before they pop the shell.
 *   B1 every WebView page registers as a BackHandler (the framework hook, no per-page code in the shell)
 *   B2 with history depth N, N Back presses each step one page (N -> N-1 ... -> 1) and are consumed
 *   B3 at the start page Back is NOT consumed, so the shell's normal navigation runs
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebPageBackChainTest : KitPageHarness() {

    private fun webViewOf(root: View): WebView? {
        if (root is WebView) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) webViewOf(root.getChildAt(i))?.let { return it }
        return null
    }

    private fun shown(id: String): Pair<WebPageFragment, WebView> {
        val page = SectionPages.pagesFor("cloud", includeHidden = true).first { it.id == id }.factory.invoke()
        assertTrue("page:cloud/$id is not the embedded browser", page is WebPageFragment)
        show(page)
        compose.waitForIdle()
        val web = webViewOf(compose.activity.window.decorView)
        assertNotNull("no WebView under page:cloud/$id", web)
        return page as WebPageFragment to web!!
    }

    private fun declaredOrigin(url: String) = url.substringBefore("?").trimEnd('/')

    @Test fun `B1 to B3 back walks the history one page at a time, then falls through to the shell`() {
        for (id in listOf("webapps")) {   // #1930 the Linktree pane left the strip
            val (page, web) = shown(id)
            assertTrue("$id is not a BackHandler", (page as Any) is BackHandler)
            val origin = declaredOrigin(shadowOf(web).lastLoadedUrl!!)
            val depth = 4
            // Robolectric's WebView records a load but adds NO history entry until pushEntryToHistory (a real WebView commits the
            // navigation later): push the start page, then each app page, so canGoBack/goBack/copyBackForwardList see `depth` entries.
            compose.runOnUiThread {
                shadowOf(web).pushEntryToHistory(shadowOf(web).lastLoadedUrl!!)
                for (i in 1 until depth) { web.loadUrl("$origin/app$i/"); shadowOf(web).pushEntryToHistory("$origin/app$i/") }
            }
            assertEquals("$id history depth", depth, web.copyBackForwardList().size)
            // depth pages = depth - 1 steps back; the page is at its start after the last one.
            for (step in 1 until depth) {
                var handled = false
                compose.runOnUiThread { handled = page.tryHandleBack() }
                assertTrue("$id: Back with ${depth - step + 1} pages in history must step back", handled)
                assertEquals("$id: can go back after $step of ${depth - 1} Backs", step < depth - 1, web.canGoBack())
            }
            var handled = true
            compose.runOnUiThread { handled = page.tryHandleBack() }
            assertFalse("$id: Back at the start page must fall through to the shell", handled)
            compose.runOnUiThread { compose.activity.supportFragmentManager.beginTransaction().remove(page).commitNow() }
        }
    }
}

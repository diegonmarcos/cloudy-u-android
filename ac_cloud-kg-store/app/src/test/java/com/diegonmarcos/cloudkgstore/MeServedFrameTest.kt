package com.diegonmarcos.cloudkgstore

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudkgstore.ui.MeServedFrame
import com.diegonmarcos.cloudlib.localserve.ServedPageTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.abs

/**
 * #1669 the Me page's served web view takes the WHOLE content area between the top bar and the bottom nav on a phone-size window
 * (412 x 915 dp): MATCH_PARENT layout params (never AndroidView's default WRAP_CONTENT, which made Chromium lay the page out at a
 * strip of the area), the frame's full width and height in pixels, the page's own viewport at scale 1, and a themed loading state
 * instead of an empty (black) area while the page loads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class MeServedFrameTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var page: File

    @Before fun setUp() {
        page = File(tmp.newFolder("clone"), "index.html")
        page.writeText("<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head><body style=\"margin:0;height:100%\">Library</body></html>")
    }

    private fun shell(stale: String?) = compose.setContent {
        MaterialTheme {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(TOP.dp))                                 // the fleet top bar
                Box(Modifier.weight(1f).fillMaxWidth().testTag(CONTENT)) { MeServedFrame(page, stale) }
                Box(Modifier.fillMaxWidth().height(BOTTOM.dp))                              // the bottom nav
            }
        }
    }

    private fun webView(): WebView {
        fun find(v: View): WebView? = v as? WebView ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
        val wv = find(compose.activity.window.decorView)
        assertNotNull("no WebView in the window", wv)
        return wv!!
    }

    @Test fun theWebViewFillsTheWholeContentArea() {
        shell(stale = null)
        compose.waitForIdle()
        val content = compose.onNodeWithTag(CONTENT).getBoundsInRoot()
        val web = compose.onNodeWithTag(ServedPageTags.WEB, useUnmergedTree = true).getBoundsInRoot()
        assertEquals("width", content.width.value, web.width.value, 0.5f)
        assertEquals("height", content.height.value, web.height.value, 0.5f)
        assertEquals("top", content.top.value, web.top.value, 0.5f)
        assertEquals("phone width", 412f, web.width.value, 1f)
        assertTrue("content area is the window minus the two bars (got ${web.height})", web.height.value > 915f - TOP - BOTTOM - 60f)

        val wv = webView()
        assertEquals("MATCH_PARENT width", ViewGroup.LayoutParams.MATCH_PARENT, wv.layoutParams.width)
        assertEquals("MATCH_PARENT height", ViewGroup.LayoutParams.MATCH_PARENT, wv.layoutParams.height)
        val density = compose.activity.resources.displayMetrics.density
        assertTrue("view height ${wv.height}px vs ${content.height.value * density}px", abs(wv.height - content.height.value * density) <= 2f)
        assertTrue("view width ${wv.width}px vs ${content.width.value * density}px", abs(wv.width - content.width.value * density) <= 2f)
    }

    @Test fun theViewportIsThePagesOwnAtScaleOne() {
        shell(stale = null)
        compose.waitForIdle()
        val s = webView().settings
        assertTrue("useWideViewPort honours the page's meta viewport", s.useWideViewPort)
        assertTrue("loadWithOverviewMode", s.loadWithOverviewMode)
        assertEquals("no text scaling", 100, s.textZoom)
        assertTrue("javascript (the page's own scroller and search)", s.javaScriptEnabled)
    }

    @Test fun whileLoadingTheFrameShowsAThemedStateNotBlack() {
        shell(stale = null)
        compose.waitForIdle()
        compose.onNodeWithTag(ServedPageTags.LOADING, useUnmergedTree = true).assertExists()
    }

    @Test fun aStaleNoteStillLeavesThePageTheRestOfTheArea() {
        shell(stale = "network down")
        compose.waitForIdle()
        val content = compose.onNodeWithTag(CONTENT).getBoundsInRoot()
        val web = compose.onNodeWithTag(ServedPageTags.WEB, useUnmergedTree = true).getBoundsInRoot()
        assertEquals("bottom", content.bottom.value, web.bottom.value, 0.5f)
        assertEquals("width", content.width.value, web.width.value, 0.5f)
        assertTrue("the note takes a line, the page the rest", web.height.value > content.height.value - 80f)
    }

    private companion object {
        const val CONTENT = "content_area"
        const val TOP = 56
        const val BOTTOM = 80
    }
}

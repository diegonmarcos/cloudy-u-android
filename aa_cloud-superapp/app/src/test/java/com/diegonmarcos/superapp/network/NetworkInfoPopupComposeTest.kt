package com.diegonmarcos.superapp.network

import android.app.Application
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — the status-strip network bubble in Compose (was a PopupWindow of TextViews with a ScrollView).
 *
 *   N1 it draws every section NetworkSections.layout declares, in order
 *   N2 a header's "Settings ›" link and a light dismiss the bubble
 *   N3 it is capped below the screen height and scrolls
 *   N4 every row the network table declares is drawn (#1523), and the Speed test action is there
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkInfoPopupComposeTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `N1 every declared section is drawn`() {
        showKit { NetworkInfoPopup.NetworkBubble(ctx, null) {} }
        for (key in NetworkSections.ORDER) compose.onNodeWithTag("netpop:section:$key").assertExists()
        compose.onNodeWithText("Network").assertExists()
    }

    @Test
    fun `N2 a light dismisses the bubble`() {
        var dismissed = 0
        showKit { NetworkInfoPopup.NetworkBubble(ctx, null) { dismissed++ } }
        compose.onNodeWithTag("netpop:light:WiFi").performScrollTo().performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun `N3 the bubble never outgrows the screen`() {
        showKit { NetworkInfoPopup.NetworkBubble(ctx, NetworkInfoPopup.GPS) {} }
        val h = compose.onNodeWithTag("netpop:bubble").fetchSemanticsNode().size.height
        assertTrue("bubble is $h px of ${ctx.resources.displayMetrics.heightPixels}", h <= ctx.resources.displayMetrics.heightPixels)
    }

    @Test
    fun `N4 every declared table row is drawn`() {
        showKit { NetworkInfoPopup.NetworkBubble(ctx, null) {} }
        val decl = com.diegonmarcos.superapp.launcher.StatusBarDecl.declared.networkTable!!
        for (g in decl.groups) {
            compose.onNodeWithTag("netpop:group:${g.id}").assertExists()
            for (r in g.rows) compose.onNodeWithTag("netpop:row:${r.id}").assertExists()
        }
        compose.onNodeWithTag("netpop:link:Speed test").assertExists()
    }
}

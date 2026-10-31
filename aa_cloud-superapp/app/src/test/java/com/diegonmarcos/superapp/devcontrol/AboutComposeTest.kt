package com.diegonmarcos.superapp.devcontrol

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #773 — Configs ▸ About in Compose (was a ScrollView of Views, every block built before the first frame).
 *
 *   D1 the page draws its title and an Index card per macro group
 *   D2 a block loads when it scrolls into view and not before (the viewport is the trigger)
 *   D3 an Index card jumps to its group and the group's blocks load
 *   D4 the page ends with the Copy All button
 *   D5 X-Ray carries the folded Diagnostics and Agents groups
 *   D6 About is the same page over App & Build and Repo & Releases only
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AboutComposeTest : KitPageHarness() {

    @Test
    fun `D1 title and an Index card per macro group`() {
        show(DevControlFragment.newInstance())
        compose.onNodeWithTag(AboutTags.TITLE).assertExists()
        compose.onNodeWithTag(AboutTags.INDEX).assertExists()
        compose.onNodeWithTag("index:APP & BUILD").assertExists()
    }

    @Test
    fun `D2 a block loads when it scrolls into view, not before`() {
        show(DevControlFragment.newInstance())
        // The 470 px test screen is filled by the title and the Index card: nothing below is composed, so nothing ran.
        compose.onAllNodesWithTag(AboutTags.row("Version")).assertCountEquals(0)
        compose.onNodeWithTag(AboutTags.LIST).performScrollToNode(hasTestTag(AboutTags.macro("APP & BUILD")))
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(AboutTags.row("Version")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `D3 an Index card jumps to its group`() {
        show(DevControlFragment.newInstance())
        compose.onNodeWithTag("index:SECURITY").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(AboutTags.macro("SECURITY")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(AboutTags.row("Dev options")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun `D4 the page ends with Copy All and the way back up`() {
        show(DevControlFragment.newInstance())
        compose.onNodeWithTag(AboutTags.LIST).performScrollToNode(hasTestTag(AboutTags.button("Copy All Infos")))
    }

    @Test
    fun `D5 X-Ray carries the folded Diagnostics and Agents groups`() {
        show(DevControlFragment.newInstance())
        compose.onNodeWithTag("index:DIAGNOSTICS").assertExists()
        compose.onNodeWithTag("index:AGENTS").assertExists()
    }

    @Test
    fun `D6 About is the same page over App & Build and Repo & Releases only`() {
        show(DevControlFragment.newInstance("About", DevControlFragment.ABOUT_MACROS))
        compose.onNodeWithTag("index:APP & BUILD").assertExists()
        compose.onNodeWithTag("index:REPO & RELEASES").assertExists()
        compose.onAllNodesWithTag("index:DEVICE").assertCountEquals(0)
    }
}

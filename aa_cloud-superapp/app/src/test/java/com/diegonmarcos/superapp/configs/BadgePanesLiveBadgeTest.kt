package com.diegonmarcos.superapp.configs

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1603b — the SETTINGS pane ([BadgePanes.configs]) is ONE clean list of the declared badges: a turn-all master, then a
 * row per declared badge with its `enabled` and `persistent` switches. No group boxes, no Apps Push / Apps Keep Alive
 * tables, no live-badge boxes — those moved out (the viewer is read-only, the control is this list). Driven against the
 * SHIPPED declaration ([BadgeServices.declared]); expectations are recomputed from it, never a second hardcoded copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadgePanesLiveBadgeTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @org.junit.Before
    fun logToStdout() { org.robolectric.shadows.ShadowLog.stream = System.out }

    private fun render() {
        showKit { Box(Modifier.verticalScroll(rememberScrollState())) { BadgePanes.configs {} } }
        compose.waitForIdle()
    }

    private fun composeTexts(): List<String> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            .flatMap { n -> n.config[SemanticsProperties.Text].map { it.text } }

    private fun badges() = BadgeDeclaration.badges(BadgeServices.declared)

    private fun hasTag(tag: String) =
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the pane is a flat declared-badge list - turn-all plus enabled and persistent per badge`() {
        render()
        val texts = composeTexts()
        assertTrue("no turn-all master", hasTag("switch:notify-all"))
        var examined = 0
        for (b in badges()) {
            examined++
            assertTrue("${b.id}: no enabled switch", hasTag("switch:enabled:${b.id}"))
            assertTrue("${b.id}: no persistent switch", hasTag("switch:persistent:${b.id}"))
            assertTrue("${b.id}: label '${b.label}' missing from the pane", b.label in texts)
        }
        assertTrue("examined no badges", examined > 0)
        // The app-section categories belong to the VIEWER only; the settings list must not draw them.
        for (gone in listOf("Cloud Publisher", "Phone Publisher", "Apps Keep Alive Badges"))
            assertFalse("$gone must not be a settings section — it is viewer-only", gone in texts)
    }

    @Test
    fun `turn-all off disables every declared badge`() {
        render()
        // Default is enabled, so the master starts ON; one tap turns the whole list off.
        compose.onAllNodesWithTag("switch:notify-all")[0].performScrollTo().performClick()
        compose.waitForIdle()
        val stillOn = badges().filter { BadgeCustomization.bool(ctx, it, BadgeCustomization.KEY_ENABLED) }
        assertTrue("turn-all off left these enabled: ${stillOn.map { it.id }}", stillOn.isEmpty())
    }

    @Test
    fun `the persistent switch follows Keep it pinned`() {
        val b = badges().first { it.persistent }
        assertTrue("${b.id} is declared persistent, so it pins by default", BadgeServices.pinned(ctx, b.id))
        BadgeCustomization.set(ctx, b, BadgeCustomization.KEY_PERSISTENT, false)
        assertFalse("${b.id}: switched off, still pinned", BadgeServices.pinned(ctx, b.id))
    }
}

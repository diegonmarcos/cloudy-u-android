package com.diegonmarcos.superapp.launcher

import android.app.Application
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #1068 guards on the Notify page (communication / my-rss), drawn at A37 size:
 *   1. every badge build.json declares renders as a card, in declared order, none invented and none missing;
 *   2. the page's configuration (Filters, badge switches) sits below ALL cards, the notifications, the Archive and the
 *      page actions, inside ONE folded section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotifyPageGuardTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val realPoller = AggregatorModel.ntfyPoller

    @Before
    fun seed() {
        AggregatorModel.ntfyPoller = { topics -> topics.associateWith { NtfyResult(true, "", emptyList()) } }
        NotifyFixtures.install()
        NotificationStore.clear(ctx)
        NotificationStore.push(ctx, "Chat", "Dev channel", "CI is green on main again", NotificationStore.Sev.INFO)
        NotificationStore.push(ctx, "Fleet", "gcp-proxy disk at 91 %", "Root volume is filling up", NotificationStore.Sev.WARN)
    }

    @After
    fun restore() {
        AggregatorModel.ntfyPoller = realPoller
        NotifyFixtures.uninstall()
        NotificationStore.clear(ctx)
    }

    private fun top(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
    private fun bottom(tag: String): Dp = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().bottom

    @Test
    fun `every declared badge renders as a card, in the declared order`() {
        val declared = BadgeDeclaration.badges(BadgeServices.declared).map { it.id }
        assertTrue("build.json declares no badge", declared.isNotEmpty())
        assertEquals("the page must draw exactly the declared badges", declared.toSet(), orderedBadges(ctx).map { it.id }.toSet())
        // #1672 the full cards are the Persistent Badges sub-page
        show(AggregatorStackFragment.newInstance("communication", "", "notify-badges"))
        compose.waitForIdle()
        for (id in declared) compose.onNodeWithTag(BadgeCardTags.card(id)).assertExists()
        // the named ones the owner listed are among them
        for (id in listOf("network_mesh", "network_data", "battery_status", "weather_today", "markets_prices")) {
            assertTrue("$id is not declared", id in declared)
        }
        // order = the order orderedBadges (groups in the owner's order, members in order) gives
        val order = orderedBadges(ctx).map { it.id }
        val tops = order.map { top(BadgeCardTags.card(it)).value }
        assertEquals("cards are not drawn in declared order", tops.sorted(), tops)
    }

    /** #1672: the configuration left the notification pages - it is the Configs sub-page, open on one level. */
    @Test
    fun `the configs are their own sub-page, not on the notifications`() {
        show(AggregatorStackFragment.newInstance("communication", "", "my-rss"))
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.CONFIG_SECTION).assertDoesNotExist()
        assertEquals(0, compose.onAllNodesWithTag(AggTags.FILTERS_TOGGLE).fetchSemanticsNodes().size)
        show(AggregatorStackFragment.newInstance("communication", "", "notify-configs"))
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.CONFIG_BODY).assertExists()
        assertTrue(top(AggTags.CONFIG_BODY).value >= bottom(AggTags.CONFIG_HEADER).value)
        if (Sections.stackFiltersFor(Sections.byId("communication")!!, "notify-configs").isNotEmpty()) {
            compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).assertExists()
            assertTrue("the Filters must be inside Configs",
                top(AggTags.FILTERS_TOGGLE).value >= top(AggTags.CONFIG_BODY).value)
        }
    }
}

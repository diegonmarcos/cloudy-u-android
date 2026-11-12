package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1672 the Notify page (communication) at A37 size, as the owner's updated spec has it - top-nav sub-pages
 * All | Persistent Badges | Cloudy Apps | Phone Apps | Configs:
 *   1. the sub-pages are declared in that order; Persistent Badges draws Media, Me Infos, Device Infos, Actions;
 *   2. every badge card collapses to one line and expands again, and the choice is remembered;
 *   3. All is one Android-style list of every notification (both sides), one box per app, with the badges on top;
 *      Cloudy Apps / Phone Apps draw the posted notifications of their side By Categories and By Time;
 *   4. archiving a box moves it to Archived (on All and the app pages), same layouts, Restore brings it back;
 *   5. the C3 Ntfy table and the Channels block are gone;
 *   6. Configs is one level and opens the configs directly;
 *   7. no notification access never crashes and offers Configs > Perms;
 *   8. parity: every item of the before-list (parity/notify-page-parity.json) is still drawn, except the two removals.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotifyPage1672Test : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val realPoller = AggregatorModel.ntfyPoller
    private var polled = 0

    private val cloudyPkg = "com.diegonmarcos.cloudmail"
    private val phonePkg = "com.whatsapp"

    @Before
    fun seed() {
        AggregatorModel.ntfyPoller = { topics -> polled++; topics.associateWith { NtfyResult(true, "", emptyList()) } }
        NotifyFixtures.install()
        NotificationStore.clear(ctx)
        NotificationStore.push(ctx, "Backups", "Nightly backup finished", "oci-vm1 · 4.2 GB", NotificationStore.Sev.INFO)
        PhoneNotificationStore.reset()
        PhoneNotificationStore.clear(ctx)
        val now = System.currentTimeMillis()
        PhoneNotificationStore.push(ctx, PhoneNotificationStore.Entry("w1", now - 60_000, phonePkg, "WhatsApp", "Mamá", "¿Venís el domingo?"))
        PhoneNotificationStore.push(ctx, PhoneNotificationStore.Entry("w2", now - 30_000, phonePkg, "WhatsApp", "Pablo", "Llego tarde"))
        PhoneNotificationStore.push(ctx, PhoneNotificationStore.Entry("c1", now - 10_000, cloudyPkg, "Cloud Mail", "Invoice", "Hetzner #2291"))
    }

    @After
    fun restore() {
        AggregatorModel.ntfyPoller = realPoller
        NotifyFixtures.uninstall()
        NotificationStore.clear(ctx)
        PhoneNotificationStore.clear(ctx)
        PhoneNotificationStore.reset()
        grant(false)
    }

    private fun grant(on: Boolean) {
        Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners",
            if (on) "${ctx.packageName}/com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService" else "")
    }

    private fun top(tag: String): Float = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot().top.value
    private fun within(tag: String, parent: String) = hasTestTag(tag) and hasAnyAncestor(hasTestTag(parent))
    private fun count(m: SemanticsMatcher) = compose.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().size
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun page(id: String = "my-rss") {
        show(AggregatorStackFragment.newInstance("communication", "", id))
        compose.waitForIdle()
    }

    @Test
    fun `1 the sub-pages and the badge sections are in the declared order`() {
        val sec = Sections.byId("communication")!!
        assertEquals(listOf("All", "Persistent Badges", "Cloudy Apps", "Phone Apps", "Configs"), sec.pages.map { it.label })
        page("notify-badges")
        val order = listOf("media", "live", "network", "actions").map { BadgeCardTags.section(it) }
        val tops = order.map { top(it) }
        assertEquals("badge sections out of order: ${order.zip(tops)}", tops.sorted(), tops)
        grant(true)
        for (p in listOf("my-rss", "notify-cloudy", "notify-phone")) page(p)
        assertEquals("no Notify page polls ntfy any more", 0, polled)
    }

    @Test
    fun `2 every badge card collapses and expands, and remembers it`() {
        page("notify-badges")
        val ids = BadgeDeclaration.badges(BadgeServices.declared).map { it.id }
        assertTrue(ids.isNotEmpty())
        for (id in ids) {
            compose.onNodeWithTag(BadgeCardTags.expanded(id), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(BadgeCardTags.toggle(id), useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(BadgeCardTags.collapsed(id), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(BadgeCardTags.expanded(id), useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag(BadgeCardTags.card(id), useUnmergedTree = true).assertExists()
            assertTrue("$id collapse not remembered", BadgeCollapse.isCollapsed(ctx, id))
        }
        page("notify-badges")
        for (id in ids) compose.onNodeWithTag(BadgeCardTags.collapsed(id), useUnmergedTree = true).assertExists()
        for (id in ids) {
            compose.onNodeWithTag(BadgeCardTags.toggle(id), useUnmergedTree = true).performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(BadgeCardTags.expanded(id), useUnmergedTree = true).assertExists()
            assertFalse(BadgeCollapse.isCollapsed(ctx, id))
        }
    }

    @Test
    fun `3 All is the Android list, the app pages show their side by categories and by time`() {
        grant(true)
        page("my-rss")
        val all = AggTags.feed(AggregatorModel.SPLIT_ALL)
        compose.onNodeWithTag(BadgeCardTags.SUMMARY, useUnmergedTree = true).assertExists()
        assertTrue("the badge summary is not on top", top(BadgeCardTags.SUMMARY) < top(all))
        for (k in listOf(cloudyPkg, phonePkg, "Backups")) assertEquals(k, 1, count(within(AggTags.group(k), all)))
        // newest app first, like Android: Cloud Mail (10 s) above WhatsApp (30 s)
        assertTrue(top(AggTags.group(cloudyPkg)) < top(AggTags.group(phonePkg)))

        page("notify-phone")
        val phone = AggTags.feed("phone")
        assertEquals("a fleet app leaked into Phone Apps", 0, count(within(AggTags.timelineRow("phone:c1"), phone)))
        // By Categories (default): a category header, its notifications by time
        assertTrue(compose.onAllNodes(SemanticsMatcher("category") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith("agg:category:phone:") == true
        }, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(top(AggTags.timelineRow("phone:w2")) < top(AggTags.timelineRow("phone:w1")))
        compose.onNodeWithTag(AggTags.groupMode("phone", AggregatorModel.GROUP_TIME), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.timeline("phone"), useUnmergedTree = true).assertExists()

        page("notify-cloudy")
        val cloud = AggTags.feed("cloudy")
        assertEquals(1, count(within(AggTags.timelineRow("phone:c1"), cloud)))
        assertEquals("another app leaked into Cloudy Apps", 0, count(within(AggTags.timelineRow("phone:w1"), cloud)))
        assertTrue(exists(AggTags.category("cloudy", AggregatorModel.INAPP_CATEGORY)))

        // remembered, and a post the listener records reaches the open page through the callback, no poll
        page("notify-phone")
        compose.onNodeWithTag(AggTags.timeline("phone"), useUnmergedTree = true).assertExists()
        PhoneNotificationStore.push(ctx, PhoneNotificationStore.Entry("w3", System.currentTimeMillis(), phonePkg, "WhatsApp", "Lucía", "Foto"))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.timelineRow("phone:w3"), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `4 archive moves a box to Archived, same layouts, and Restore brings it back`() {
        grant(true); page("my-rss")
        val all = AggTags.feed(AggregatorModel.SPLIT_ALL)
        compose.onNodeWithTag(NotifActions.tag(NotifActions.ARCHIVE, phonePkg), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, count(within(AggTags.group(phonePkg), all)))
        compose.onNodeWithTag(AggTags.ARCHIVE_HEADER).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, count(within(AggTags.group(phonePkg), AggTags.ARCHIVE_BODY)))
        // the same archive is reachable on the Phone Apps page (one shared state)
        page("notify-phone")
        assertEquals(0, count(within(AggTags.timelineRow("phone:w1"), AggTags.feed("phone"))))
        compose.onNodeWithTag(AggTags.ARCHIVE_HEADER).assertExists()
        page("my-rss")
        compose.onNodeWithTag(AggTags.groupMode(AggregatorModel.FEED_ARCHIVED, AggregatorModel.GROUP_TIME), useUnmergedTree = true)
            .performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(AggTags.timeline(AggregatorModel.FEED_ARCHIVED), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AggTags.timelineRestore("phone:w1"), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, count(within(AggTags.group(phonePkg), all)))
    }

    @Test
    fun `5 the C3 Ntfy table and the Channels block are gone, on every Notify page`() {
        grant(true)
        val sec = Sections.byId("communication")!!
        for (p in sec.allPages) {
            val panels = Sections.aggregatorStackFor(sec, p.id)
            assertTrue("${p.id} still declares the C3 Ntfy table", panels.none { it.stream == "channels" || it.title == "C3 Ntfy" })
            assertTrue("${p.id} still polls channels", panels.none { it.kind == "notification_center" && it.split.isBlank() })
            assertTrue("${p.id} Source still offers C3 Ntfy",
                Sections.stackFiltersFor(sec, p.id).flatMap { it.options }.none { it.id == "c3ntfy" })
            page(p.id)
            assertEquals(0, compose.onAllNodesWithText("C3 Ntfy").fetchSemanticsNodes().size)
            assertEquals(0, compose.onAllNodesWithText("CHANNELS").fetchSemanticsNodes().size)
        }
    }

    @Test
    fun `6 Configs is one level and opens the configs directly`() {
        page("my-rss")
        compose.onNodeWithTag(AggTags.CONFIG_SECTION).assertDoesNotExist()
        page("notify-configs")
        // open at once: no header to tap, no second fold
        compose.onNodeWithTag(AggTags.CONFIG_BODY).assertExists()
        assertEquals(0, compose.onAllNodesWithText("Settings").fetchSemanticsNodes().size)
        compose.onNodeWithTag(AggTags.FILTERS_TOGGLE).assertExists()
        val panels = Sections.aggregatorStackFor(Sections.byId("communication")!!, "notify-configs")
        val i = panels.indexOfFirst { it.kind == "notification_badge_configs" }
        assertTrue(i >= 0)
        compose.onNodeWithTag(AggTags.panelHeader(i)).assertDoesNotExist()
        for (b in BadgeDeclaration.badges(BadgeServices.declared))
            assertTrue("badge-setting:${b.id} not drawn in Configs", exists("badge-setting:${b.id}"))
        compose.onNodeWithTag(AggTags.CONFIG_APPS, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AggTags.CONFIG_ACCESS, useUnmergedTree = true).assertExists()
        // a filter picked on Configs is the filter of the feeds (one shared state)
        assertEquals(Sections.stackFiltersFor(Sections.byId("communication")!!, "my-rss").map { it.id },
            Sections.stackFiltersFor(Sections.byId("communication")!!, "notify-phone").map { it.id })
    }

    @Test
    fun `7 no notification access never crashes and offers Configs Perms`() {
        grant(false)
        for (p in listOf("my-rss", "notify-cloudy", "notify-phone")) page(p)
        val phone = AggTags.feed("phone")
        assertTrue(count(within(AggTags.OPEN_PERMS, phone)) == 1)
        compose.onAllNodes(within(AggTags.OPEN_PERMS, phone), useUnmergedTree = true)[0].performScrollTo().performClick()
        compose.waitForIdle()
        page("my-rss")
        // the in-app feed still shows, it needs no grant
        assertEquals(1, count(within(AggTags.group("Backups"), AggTags.feed(AggregatorModel.SPLIT_ALL))))
        assertEquals("page:config/perms", AggregatorModel.PERMS_TARGET)
    }

    @Test
    fun `8 parity - every item drawn before is drawn after, except the two named removals`() {
        val items = JSONObject(File("src/test/resources/parity/notify-page-parity.json").readText()).getJSONArray("items")
        val all = (0 until items.length()).map { items.getJSONObject(it) }
        assertEquals("only the C3 Ntfy table and the Channels block may go", setOf("feed.c3ntfy", "feed.channels"),
            all.filter { it.getString("status") == "removed" }.map { it.getString("id") }.toSet())
        grant(false)
        // Archived is drawn only while it holds something: file one box away so it is on the page.
        StackFilters.select(ctx, "my-rss", AggregatorModel.ARCHIVED_PREFIX + "Backups", "yes")
        val badges = BadgeDeclaration.badges(BadgeServices.declared).map { it.id }
        val missing = mutableListOf<String>()
        for ((pageId, its) in all.filter { it.getString("status") == "kept" }.groupBy { it.getString("page") }) {
            page(pageId)
            if (exists(AggTags.ARCHIVE_HEADER)) { compose.onNodeWithTag(AggTags.ARCHIVE_HEADER).performScrollTo().performClick(); compose.waitForIdle() }
            for (it in its) {
                val tag = it.optString("tag"); val text = it.optString("text")
                val ok = when {
                    tag.endsWith(":*") -> badges.all { b -> exists(tag.removeSuffix("*") + b) }
                    tag.isNotBlank() -> exists(tag)
                    else -> compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                if (!ok) missing += it.getString("id")
            }
        }
        for (p in Sections.byId("communication")!!.allPages) {
            page(p.id)
            for (r in all.filter { it.getString("status") == "removed" })
                assertEquals("${r.getString("id")} is back on ${p.id}", 0,
                    compose.onAllNodesWithText(r.getString("absent_text"), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
        assertTrue("parity: missing after #1672: $missing", missing.isEmpty())
    }

    @Test
    fun `9 model - timeline is newest first, categories are A-Z`() {
        val g1 = NotifGroup("a", "A", "", listOf(NotifRow(1, "x", ""), NotifRow(5, "y", "")), launchPackage = "a")
        val g2 = NotifGroup("b", "B", "", listOf(NotifRow(3, "z", "")), launchPackage = "b")
        assertEquals(listOf(5L, 3L, 1L), AggregatorModel.timeline(listOf(g1, g2)).map { it.second.ts })
        val cats = AggregatorModel.byCategory(listOf(g1, g2)) { if (it.key == "a") "Zeta" else "alpha" }
        assertEquals(listOf("alpha", "Zeta"), cats.map { it.first })
    }
}

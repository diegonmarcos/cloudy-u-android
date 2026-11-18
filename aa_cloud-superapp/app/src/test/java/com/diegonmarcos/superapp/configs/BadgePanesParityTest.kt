package com.diegonmarcos.superapp.configs

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.launcher.badgeSections
import com.diegonmarcos.superapp.notificationcenter.BadgeCustomization
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeServices
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1794 Rule 16 field parity for the reorganised Configs badge pane: every field the pane drew BEFORE the reorg is still
 * drawn, except the ones the owner ordered removed (only the per-badge Launch button, because "Show this badge" now
 * launches the badge). [PREVIOUS] is this test's own snapshot of the pre-#1794 pane, not a copy read from the code under
 * test, so a field dropped from the pane AND from [BadgePanes.PREVIOUS_FIELDS] still fails here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadgePanesParityTest : KitPageHarness() {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /** The pre-#1794 pane (BadgePanes.kt at 23b00a79): caption, Launch all, Turn all; per badge: its live view or its
     *  name, the state label + reason line, Open, Launch, the enabled and the persistent switches. */
    private val PREVIOUS = listOf(
        "pane:caption", "pane:launch-all", "pane:turn-all",
        "row:title", "row:live-view", "row:state", "row:reason", "row:open", "row:launch", "row:enabled", "row:persistent",
    )

    private fun badges() = BadgeDeclaration.rankOrder(BadgeServices.declared, BadgeDeclaration.badges(BadgeServices.declared))

    @Before fun clean() {
        for (p in listOf("notify_groups", "badge_customization"))
            ctx.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Post a card on [b]'s declared channel, with a tap, so the row has a live view and an Open button to draw. */
    private fun post(b: BadgeDeclaration.Badge) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(b.channel, b.channel, NotificationManager.IMPORTANCE_LOW))
        val tap = PendingIntent.getActivity(ctx, 0, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(0x1794, NotificationCompat.Builder(ctx, b.channel).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(b.label).setContentText("parity").setContentIntent(tap).build())
    }

    private fun render() {
        showKit { Box(Modifier.verticalScroll(rememberScrollState())) { BadgePanes.configs {} } }
        compose.waitForIdle()
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    /** Unclipped: a row below the test window still has its real place (boundsInRoot clips it to an empty rect). */
    private fun bounds(tag: String): DpRect = compose.onAllNodesWithTag(tag, useUnmergedTree = true)[0].getUnclippedBoundsInRoot()

    @Test fun `the declared parity list is the pre-reorg pane, and only owner-ordered fields left it`() {
        assertEquals("BadgePanes.PREVIOUS_FIELDS must list every pre-#1794 field", PREVIOUS, BadgePanes.PREVIOUS_FIELDS)
        assertEquals("only the per-badge Launch button is owner-removed", setOf("row:launch"), BadgePanes.OWNER_REMOVED.keys)
        assertTrue(BadgePanes.OWNER_REMOVED.getValue("row:launch").contains("owner-ordered"))
    }

    @Test fun `every pre-reorg field is still drawn, for every declared badge`() {
        val posted = badges().first { it.channel.isNotBlank() }
        post(posted)
        render()
        val found = mutableMapOf<String, Boolean>()
        found["pane:caption"] = has("pane-field:caption")
        found["pane:launch-all"] = has("pane-button:" + ctx.getString(R.string.push_launch_all))
        found["pane:turn-all"] = has("switch:notify-all")
        for (b in badges()) {
            val miss = mutableListOf<String>()
            if (!has("badge-title:${b.id}")) miss += "row:title"
            if (!has("badge-state:${b.id}")) miss += "row:state"
            if (!has("badge-reason:${b.id}")) miss += "row:reason"
            if (b.customization.any { it.key == BadgeCustomization.KEY_ENABLED } && !has("switch:enabled:${b.id}")) miss += "row:enabled"
            if (b.customization.any { it.key == BadgeCustomization.KEY_PERSISTENT } && !has("switch:persistent:${b.id}")) miss += "row:persistent"
            assertTrue("${b.id} lost: $miss", miss.isEmpty())
        }
        listOf("row:title", "row:state", "row:reason", "row:enabled", "row:persistent").forEach { found[it] = true }
        // A posted badge keeps its live view (the shade's own template) and Open.
        found["row:live-view"] = has("badge-view:${posted.id}")
        found["row:open"] = has("badge-open:${posted.id}")
        // Owner-ordered: no per-badge Launch button any more.
        assertTrue("the per-badge Launch button is back", !has("pane-button:" + ctx.getString(R.string.push_launch)))
        val lost = PREVIOUS.filter { found[it] != true && it !in BadgePanes.OWNER_REMOVED }
        assertTrue("fields lost by the reorg: $lost", lost.isEmpty())
    }

    @Test fun `the state sits on the right of the title, on the same row`() {
        render()
        for (b in badges()) {
            val t = bounds("badge-title:${b.id}"); val s = bounds("badge-state:${b.id}")
            assertTrue("${b.id}: state not on the title's row ($t / $s)", s.top < t.bottom && t.top < s.bottom)
            assertTrue("${b.id}: state not right of the title", s.left >= t.right - 1.dp)
        }
    }

    @Test fun `the badges are grouped under the declared Notify categories, in page order`() {
        render()
        val sections = badgeSections(ctx)
        assertTrue(sections.count { it.title.isNotBlank() } >= 2)
        var lastTop = (-1).dp
        for (s in sections) {
            if (s.title.isNotBlank()) {
                assertTrue("no header for ${s.id}", has("badge-setting-section:${s.id}"))
                assertTrue("${s.title} missing", compose.onAllNodesWithText(s.title).fetchSemanticsNodes().isNotEmpty())
                val h = bounds("badge-setting-section:${s.id}").top
                assertTrue("${s.id} out of page order", h > lastTop); lastTop = h
            }
            for (b in s.badges) {
                val top = bounds("badge-setting:${b.id}").top
                assertTrue("${b.id} is not under its category ${s.id}", top > lastTop); lastTop = top
            }
        }
    }

    @Test fun `the persistent switch reads Make it Persistent`() {
        render()
        val labels = badges().mapNotNull { b -> b.customization.firstOrNull { it.key == BadgeCustomization.KEY_PERSISTENT }?.label }.toSet()
        assertEquals(setOf("Make it Persistent"), labels)
        assertTrue(compose.onAllNodesWithText("Make it Persistent").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Keep it pinned (ongoing)").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `Show this badge switched on launches the badge (what Launch did)`() {
        val b = badges().first { it.requires.isEmpty() && it.service.isNotBlank() }
        BadgeCustomization.setEnabled(ctx, b, false)
        render()
        val app = shadowOf(ctx as Application)
        while (app.nextStartedService != null) Unit
        compose.onAllNodesWithTag("switch:enabled:${b.id}")[0].performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(BadgePanes.shown(ctx, b))
        val started = generateSequence { app.nextStartedService }.map { it.component?.className }.toList()
        assertTrue("${b.id}: switched on, but ${b.service} was not started ($started)", b.service in started)
    }
}

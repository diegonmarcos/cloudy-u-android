package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Mesh, Data and Battery badges: all declared, one shade group "Resources", each with its own switches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NetworkGroupTest {

    private val ctx get() = RuntimeEnvironment.getApplication()
    private fun badge(id: String) = BadgeServices.declared.firstOrNull { it.id == id }

    @Test fun `both badges are declared persistent with their own channel and service`() {
        for ((id, svc, ch) in listOf(
            Triple(NetworkBadgeService.BADGE_ID, NetworkBadgeService::class.java.name, NetworkBadgeService.CHANNEL_ID),
            Triple(DataBadgeService.BADGE_ID, DataBadgeService::class.java.name, DataBadgeService.CHANNEL_ID),
        )) {
            val b = badge(id)
            assertTrue("$id missing from the shipped declaration", b != null)
            b!!
            assertTrue(b.isBadge && b.persistent && b.enabled)
            assertEquals(svc, b.service)
            assertEquals(ch, b.channel)
            assertTrue(b.requires.contains("notifications"))
            assertTrue(BadgeDeclaration.restartServices(BadgeServices.declared).contains(svc))
        }
        assertEquals("Mesh", badge(NetworkBadgeService.BADGE_ID)!!.label)
        assertEquals("Data", badge(DataBadgeService.BADGE_ID)!!.label)
    }

    @Test fun `they share the shade group Resources and nothing else is in it`() {
        val g = NotifyGroups.groupOf(NetworkBadgeService.BADGE_ID)!!
        assertEquals("network", g.id)
        assertEquals("Device Infos", g.label)
        assertEquals(g, NotifyGroups.groupOf(DataBadgeService.BADGE_ID))
        assertEquals(g, NotifyGroups.groupOf(BatteryBadgeService.BADGE_ID))
        assertEquals(listOf(NetworkBadgeService.BADGE_ID, DataBadgeService.BADGE_ID, BatteryBadgeService.BADGE_ID, AiBadgeService.BADGE_ID), g.members)
    }

    @Test fun `attach puts both under one group key and posts one summary`() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val keys = listOf(NetworkBadgeService.BADGE_ID, DataBadgeService.BADGE_ID, BatteryBadgeService.BADGE_ID).map { id ->
            NotifyGroups.attach(ctx, NotificationCompat.Builder(ctx, "t").setSmallIcon(android.R.drawable.sym_def_app_icon), id)
                .build().group
        }
        assertEquals(1, keys.toSet().size)
        assertEquals(NotifyGroups.key(NotifyGroups.groupOf(NetworkBadgeService.BADGE_ID)!!), keys[0])
        val summaries = nm.activeNotifications.filter {
            it.notification.group == keys[0] && it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0
        }
        assertEquals("exactly one summary for the three", 1, summaries.size)
    }

    @Test fun `each badge has its own on-off switch`() {
        org.robolectric.Shadows.shadowOf(ctx).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val mesh = badge(NetworkBadgeService.BADGE_ID)!!
        val data = badge(DataBadgeService.BADGE_ID)!!
        BadgeCustomization.set(ctx, data, BadgeCustomization.KEY_ENABLED, false)
        assertFalse(BadgeCustomization.isEnabled(ctx, data))
        assertTrue("turning Data off leaves Mesh on", BadgeCustomization.isEnabled(ctx, mesh))
        assertTrue(BadgeServices.wanted(ctx, mesh.service))
        assertFalse(BadgeServices.wanted(ctx, data.service))
    }

    @Test fun `battery keeps its own channel and switches inside the group`() {
        val bat = badge(BatteryBadgeService.BADGE_ID)!!
        assertEquals(BatteryBadgeService.CHANNEL_ID, bat.channel)
        assertTrue(bat.channel != badge(NetworkBadgeService.BADGE_ID)!!.channel && bat.channel != badge(DataBadgeService.BADGE_ID)!!.channel)
        BadgeCustomization.set(ctx, bat, BadgeCustomization.KEY_ENABLED, false)
        assertFalse(BadgeCustomization.isEnabled(ctx, bat))
        assertTrue(BadgeCustomization.isEnabled(ctx, badge(NetworkBadgeService.BADGE_ID)!!))
        assertTrue(BadgeCustomization.isEnabled(ctx, badge(DataBadgeService.BADGE_ID)!!))
    }

    @Test fun `the pin switch is per badge too`() {
        val mesh = badge(NetworkBadgeService.BADGE_ID)!!
        val data = badge(DataBadgeService.BADGE_ID)!!
        BadgeCustomization.set(ctx, mesh, BadgeCustomization.KEY_PERSISTENT, false)
        assertFalse(BadgeCustomization.isPersistent(ctx, mesh))
        assertTrue(BadgeCustomization.isPersistent(ctx, data))
    }
}

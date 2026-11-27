package com.diegonmarcos.cloudnav.maps

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.diegonmarcos.superapp.core.BackgroundApps
import com.diegonmarcos.superapp.core.BackgroundCards
import com.diegonmarcos.superapp.core.FleetNotify
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1944 Cloud Nav's "running in the background" card: the tracker service's ongoing notification, with the navigation info the
 * Navigation tab publishes (destination, next instruction, distance, ETA), in the fleet's one background group, with a group
 * summary that goes when the service stops.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NavBackgroundCardTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val info = NavInfo("Central Station", "Head to Central Station", "Car · follow the route", "3.2 km", "14:05", "Car")

    @After fun reset() { NavCardState.onChange = null; NavCardState.clear() }

    @Test fun `navigation lines are destination, instruction and distance with ETA`() {
        assertEquals(listOf("To Central Station · Car", "Head to Central Station · Car · follow the route", "3.2 km · ETA 14:05"), NavCardState.lines(info))
    }

    @Test fun `a blank destination or no info gives no lines, and unknown distance or ETA is left out`() {
        assertTrue(NavCardState.lines(null).isEmpty())
        assertTrue(NavCardState.lines(NavInfo("")).isEmpty())
        assertEquals(listOf("To Home"), NavCardState.lines(NavInfo("Home", distance = "—", eta = "—")))
    }

    @Test fun `the card is ongoing, not clearable, in the shared group, and carries the nav info`() {
        val n = NavCard.notification(app, "Points: 12 · acc: 8m", info)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(FleetNotify.GROUP_RUNNING, n.group)
        assertEquals("To Central Station · Car", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(big.contains("3.2 km · ETA 14:05"))
        assertTrue("the tracking line stays", big.contains("Points: 12"))
    }

    @Test fun `with no navigation the card is the plain tracker card, still grouped`() {
        val n = NavCard.notification(app, "Tracking", null)
        assertEquals("Cloud Maps tracker", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Tracking", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(FleetNotify.GROUP_RUNNING, n.group)
    }

    @Test fun `the summary is the groups ongoing summary, posted and removed`() {
        val s = NavCard.summary(app)
        assertEquals(FleetNotify.GROUP_RUNNING, s.group)
        assertTrue(s.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(s.flags and Notification.FLAG_ONGOING_EVENT != 0)
        NavCard.postSummary(app)
        assertNotNull(shadowOf(nm).getNotification(FleetNotify.SUMMARY_ID))
        NavCard.cancelSummary(app)
        assertNull(shadowOf(nm).getNotification(FleetNotify.SUMMARY_ID))
    }

    @Test fun `a published change refreshes the card, throttled, and clearing always refreshes`() {
        var n = 0
        NavCardState.onChange = { n++ }
        NavCardState.publish(info, now = 100_000L)
        assertEquals(1, n)
        NavCardState.publish(info.copy(distance = "3.1 km"), now = 101_000L)
        assertEquals("inside the throttle window: no refresh", 1, n)
        NavCardState.publish(info.copy(distance = "3.0 km"), now = 106_000L)
        assertEquals(2, n)
        NavCardState.clear()
        assertEquals(3, n)
        assertNull(NavCardState.info)
        NavCardState.clear()
        assertEquals("nothing changed: no refresh", 3, n)
    }

    @Test fun `the service wires the card, the summary and the removal, and the tab publishes and clears`() {
        val dirs = listOf("../../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps", "../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps")
        val svc = dirs.map { File(it, "LocationTrackerService.kt") }.first { it.exists() }.readText()
        assertTrue(svc.contains("NavCard.notification("))
        assertTrue(svc.contains("NavCard.postSummary("))
        assertTrue(svc.substringAfter("override fun onDestroy()").contains("NavCard.cancelSummary("))
        assertFalse("the old own group is gone", svc.contains("nc_maps"))
        val tab = File("src/main/java/com/diegonmarcos/cloudnav/routes/NavigationFragment.kt").readText()
        assertTrue(tab.contains("NavCardState.publish("))
        assertTrue(tab.substringAfter("override fun onDestroy()").contains("NavCardState.clear()"))
    }

    // ── #1965 the "Background Apps" card: route, next turn, distance, ETA, tracking; the silent FGS channel; nothing lost ──

    @Test fun `the Background Apps card carries every declared Nav field and button`() {
        val card = BackgroundApps.fromNotification(NavCard.notification(app, "Points: 12 · acc: 8m", info), 0xA1)!!
        assertEquals(BackgroundCards.NAV, card.kind)
        assertEquals(emptyList<String>(), BackgroundCards.missingFields(card))
        assertEquals(emptyList<String>(), BackgroundCards.missingButtons(card))
        val v = card.fields.associate { it.key to it.value }
        assertEquals("Central Station · Car", v[BackgroundCards.Nav.ROUTE])
        assertEquals("Head to Central Station · Car · follow the route", v[BackgroundCards.Nav.NEXT_TURN])
        assertEquals("3.2 km", v[BackgroundCards.Nav.DISTANCE])
        assertEquals("14:05", v[BackgroundCards.Nav.ETA])
        assertEquals("Points: 12 · acc: 8m", v[BackgroundCards.Nav.TRACKING])
    }

    @Test fun `with no route the card still says the tracking state`() {
        val card = BackgroundApps.fromNotification(NavCard.notification(app, "Tracking", null), 0xA1)!!
        assertEquals(listOf(BackgroundCards.Nav.TRACKING), card.keys())
    }

    @Test fun `the nav FGS card is on the silent MIN Background service channel`() {
        val n = NavCard.notification(app, "Tracking", info)
        assertEquals(FleetNotify.CHANNEL_BACKGROUND_SERVICE, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_MIN, nm.getNotificationChannel(n.channelId).importance)
    }

    @Test fun `rule 16 every nav line of the app card is in the Background Apps card`() {
        val n = NavCard.notification(app, "Points: 12 · acc: 8m", info)
        val card = BackgroundApps.fromNotification(n, 0xA1)!!
        val text = BackgroundApps.lines(card).joinToString("\n")
        listOf("Central Station", "Car", "Head to Central Station", "3.2 km", "14:05", "Points: 12").forEach { assertTrue(it, text.contains(it)) }
        assertTrue(n.contentIntent != null && card.content != null)
    }

    @Test fun `the tracker publishes its card to the hub after startForeground and on every refresh`() {
        val dirs = listOf("../../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps", "../ab_cloud-libs-shared/libs/maps/src/main/java/com/diegonmarcos/cloudnav/maps")
        val svc = dirs.map { File(it, "LocationTrackerService.kt") }.first { it.exists() }.readText()
        assertTrue(svc.contains("NavCard.publish(this, NOTIF_ID, card)"))
        assertFalse("a refresh must go through FleetNotify.post, which republishes", svc.contains(".notify(NOTIF_ID"))
    }
}

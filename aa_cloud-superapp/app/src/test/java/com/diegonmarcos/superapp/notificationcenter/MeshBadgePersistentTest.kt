package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.network.NetworkBadgeModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1844 the Mesh badge is in the shade whatever the tunnel does, and every badge keeps its group.
 * Root cause pinned here: a swipe of the UNCHANGED disconnected card was followed by a re-post the NotifyGate dedupe dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MeshBadgePersistentTest {
    private lateinit var app: Application
    private lateinit var nm: NotificationManager
    private var now = 1_000L

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("c", "c", NotificationManager.IMPORTANCE_LOW))
        NotifyGate.reset(); now = 1_000L
        NotifyGate.clock = { now }
        NotifyGate.defer = { _, _ -> }
    }
    @After fun tearDown() { NotifyGate.reset() }

    private fun disconnected(): Notification {
        val card = NetworkBadgeModel.card(NetworkBadgeModel.Snapshot(
            engineInstalled = true, connected = false, tunnel = "t", nowMs = 0L))
        return NotifyGroups.attach(app, NotificationCompat.Builder(app, "c")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(card.title).setContentText(card.text).setOngoing(true), NetworkBadgeService.BADGE_ID).build()
    }

    @Test fun disconnectedCardSaysDisconnectedAndOffersConnect() {
        val card = NetworkBadgeModel.card(NetworkBadgeModel.Snapshot(
            engineInstalled = true, connected = false, tunnel = "t", nowMs = 0L))
        assertTrue(card.title + card.text, (card.title + card.text).contains("Disconnected"))
        assertTrue(card.actions.any { it.label == "Connect" })
    }

    @Test fun postedWhileDisconnected() {
        assertTrue(NotifyGate.postKept(nm, null, NetworkBadgeService.NOTIF_ID, disconnected()))
        assertTrue(NotifyGate.inShade(nm, null, NetworkBadgeService.NOTIF_ID))
    }

    @Test fun backAfterADismissalEvenThoughNothingChanged() {
        NotifyGate.postKept(nm, null, NetworkBadgeService.NOTIF_ID, disconnected())
        nm.cancel(NetworkBadgeService.NOTIF_ID) // swiped (Android 14 lets an ongoing card go)
        now += 5_000 // the delete intent restarts the service, which refreshes: same content
        assertTrue("the dedupe dropped the re-post", NotifyGate.postKept(nm, null, NetworkBadgeService.NOTIF_ID, disconnected()))
        assertTrue(NotifyGate.inShade(nm, null, NetworkBadgeService.NOTIF_ID))
    }

    @Test fun anUnchangedCardStillThereIsNotPostedAgain() {
        NotifyGate.postKept(nm, null, NetworkBadgeService.NOTIF_ID, disconnected())
        now += 5_000
        assertFalse(NotifyGate.postKept(nm, null, NetworkBadgeService.NOTIF_ID, disconnected()))
    }

    @Test fun aSwipedSummaryComesBack() {
        val g = NotifyGroups.groupOf(NetworkBadgeService.BADGE_ID)!!
        NotifyGroups.postSummary(app, g)
        assertTrue(NotifyGate.inShade(nm, g.id, 0x6C00))
        nm.cancel(g.id, 0x6C00); now += 5_000
        NotifyGroups.postSummary(app, g)
        assertTrue("children lose their group without a summary", NotifyGate.inShade(nm, g.id, 0x6C00))
    }

    @Test fun everyDeclaredBadgeWithAServiceHasAGroupKeySortKeyAndSummary() {
        for (b in BadgeDeclaration.badges(BadgeServices.declared)) {
            val g = NotifyGroups.groupOf(b.id)
            assertNotNull("badge ${b.id} has no declared group", g)
            val n = NotifyGroups.attach(app, NotificationCompat.Builder(app, "c")
                .setSmallIcon(android.R.drawable.ic_dialog_info), b.id).build()
            assertEquals(b.id, NotifyGroups.key(g!!), n.group)
            assertNotNull("sort key of ${b.id}", n.sortKey)
            if (!g.alerts) {
                val s = nm.activeNotifications.filter { it.id == 0x6C00 && it.tag == g.id }
                assertEquals("summary of ${g.id}", 1, s.size)
                assertTrue(s[0].notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
                assertEquals(NotifyGroups.key(g), s[0].notification.group)
            }
        }
    }

    @Test fun meshStaysDeclaredPersistentAndRestartedByEnsureAll() {
        val b = BadgeServices.declared.first { it.id == NetworkBadgeService.BADGE_ID }
        assertTrue(b.persistent && b.enabled)
        assertTrue(BadgeDeclaration.restartServices(BadgeServices.declared).contains(NetworkBadgeService::class.java.name))
    }
}

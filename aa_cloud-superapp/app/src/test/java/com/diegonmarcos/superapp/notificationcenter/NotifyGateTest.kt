package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guard for the notification storm (217,685 posts/hour, about 60/s, in one batterystats wakelock line): N identical
 * updates are ONE notify(), a high-rate burst of different updates is at most two (first + last), a cancel re-arms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyGateTest {
    private lateinit var nm: NotificationManager
    private lateinit var app: Application
    private var now = 1_000L
    private val deferred = ArrayList<Pair<Long, Runnable>>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("c", "c", NotificationManager.IMPORTANCE_LOW))
        NotifyGate.reset(); deferred.clear(); now = 1_000L
        NotifyGate.clock = { now }
        NotifyGate.defer = { d, r -> deferred += d to r }
    }

    /** The frozen clock and the captured scheduler are this test's own: the next test class gets the real ones back. */
    @After fun tearDown() { NotifyGate.reset() }

    private fun n(text: String): Notification =
        NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("t").setContentText(text).build()

    @Test fun identicalUpdatesPostOnce() {
        repeat(1000) { NotifyGate.post(nm, null, 1, n("same")); now += 16 }
        assertEquals(1, NotifyGate.notifyCount)
    }

    @Test fun sixtyHzBurstOfChangesIsFirstPlusLast() {
        repeat(60) { i -> NotifyGate.post(nm, null, 1, n("v$i")); now += 16 } // under one gap
        assertEquals(1, NotifyGate.notifyCount)
        now += NotifyGate.MIN_GAP_MS
        deferred.forEach { it.second.run() }
        assertEquals(2, NotifyGate.notifyCount) // the last state is not lost
    }

    @Test fun forceStillDebounces() {
        repeat(100) { NotifyGate.post(nm, null, 1, n("same"), force = true); now += 5 }
        assertEquals(1, NotifyGate.notifyCount)
    }

    @Test fun cancelRearmsTheDedupe() {
        assertTrue(NotifyGate.post(nm, null, 1, n("same")))
        assertFalse(NotifyGate.post(nm, null, 1, n("same")))
        NotifyGate.invalidate(null, 1); now += 2_000
        assertTrue(NotifyGate.post(nm, null, 1, n("same")))
    }

    @Test fun idsAreIndependent() {
        NotifyGate.post(nm, null, 1, n("a")); NotifyGate.post(nm, null, 2, n("a")); NotifyGate.post(nm, "tag", 1, n("a"))
        assertEquals(3, NotifyGate.notifyCount)
    }

    // ── A data widget card (postCtx): forced only when it is really not in the shade ─────────────────────────────

    @Test fun anUnchangedWidgetCardStillInTheShadeIsNotPostedAgain() {
        assertTrue(NotifyGate.postCtx(app, 7, n("72%")))
        // Every screen-on used to re-post every widget card, changed or not.
        repeat(200) { now += 5_000; assertFalse(NotifyGate.postCtx(app, 7, n("72%"))) }
        assertEquals(1, NotifyGate.notifyCount)
        assertTrue("a changed card still goes out", NotifyGate.postCtx(app, 7, n("71%")))
        assertEquals(2, NotifyGate.notifyCount)
    }

    @Test fun aWidgetCardThatLeftTheShadeIsPutBackEvenUnchanged() {
        assertTrue(NotifyGate.postCtx(app, 7, n("72%")))
        nm.cancel(7) // swiped away: nobody told the gate
        assertFalse("control: the card is gone", NotifyGate.inShade(nm, null, 7))
        now += 5_000
        assertTrue("the shade is asked, not assumed", NotifyGate.postCtx(app, 7, n("72%")))
        assertTrue(NotifyGate.inShade(nm, null, 7))
        assertEquals(2, NotifyGate.notifyCount)
    }

    @Test fun aCardSwipedInsideTheGapComesBackWhenTheGapEnds() {
        assertTrue(NotifyGate.postCtx(app, 7, n("72%")))
        nm.cancel(7)
        now += 100 // inside the gap: held, forced
        assertFalse(NotifyGate.postCtx(app, 7, n("72%")))
        // A plain identical post in between must not void the held forced one.
        assertFalse(NotifyGate.post(nm, null, 7, n("72%")))
        now += NotifyGate.MIN_GAP_MS
        deferred.toList().forEach { it.second.run() }
        assertTrue("the held card was dropped as a duplicate", NotifyGate.inShade(nm, null, 7))
        assertEquals(2, NotifyGate.notifyCount)
    }

    @Test fun aHeldOlderStateNeverOverwritesANewerOneAlreadySent() {
        NotifyGate.post(nm, null, 1, n("a"))
        now += 100
        NotifyGate.post(nm, null, 1, n("b")) // held
        now += NotifyGate.MIN_GAP_MS         // the gap is over, the flush has not run yet
        assertTrue(NotifyGate.post(nm, null, 1, n("c")))
        deferred.toList().forEach { it.second.run() } // the late flush
        assertEquals("the stale held 'b' went out after 'c'", 2, NotifyGate.notifyCount)
        val shown = nm.activeNotifications.first { it.id == 1 }.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("c", shown)
    }

    @Test fun batteryGapIs90s() {
        assertEquals(90_000L, BatteryBadgeService.MIN_GAP_MS)
    }
}

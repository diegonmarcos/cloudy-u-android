package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
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

    @Test fun batteryGapIs90s() {
        assertEquals(90_000L, BatteryBadgeService.MIN_GAP_MS)
    }
}

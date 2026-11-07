package com.diegonmarcos.superapp.core

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.ServerSocket

/**
 * The two persistent-notification kinds: a keep-alive is ongoing, has a Stop action and rides startForeground; a data
 * widget is ongoing but is never a foreground-service notification, so Android does not list the app as kept alive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FleetNotifyTest {

    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private fun pi(): PendingIntent = PendingIntent.getBroadcast(ctx, 0, Intent("stop"), PendingIntent.FLAG_IMMUTABLE)

    private fun keepAlive(lines: List<String> = listOf("8002 up", "mesh up")) =
        FleetNotify.keepAliveNotification(ctx, android.R.drawable.ic_dialog_info, "Server", lines, stop = pi())

    /** Starts foreground the way every keep-alive owner does: through the helper. */
    class KeepAliveSvc : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
        override fun onCreate() {
            super.onCreate()
            FleetNotify.startKeepAlive(this, 7, FleetNotify.keepAliveNotification(
                this, android.R.drawable.ic_dialog_info, "Server", listOf("ports up"),
                PendingIntent.getService(this, 0, Intent(this, KeepAliveSvc::class.java), PendingIntent.FLAG_IMMUTABLE)))
        }
    }

    /** A data widget's owner: posts with notify and never startForeground. */
    class WidgetSvc : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
        override fun onCreate() {
            super.onCreate()
            FleetNotify.post(this, 8, FleetNotify.dataWidgetNotification(this, android.R.drawable.ic_dialog_info, "Weather", listOf("12 C")))
        }
    }

    @Test fun keepAliveIsOngoingAndNotDismissable() {
        val n = keepAlive()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(FleetNotify.CHANNEL_KEEP_ALIVE, n.channelId)
    }

    @Test fun keepAliveHasAStopActionAndLiveData() {
        val n = keepAlive(listOf("8002 up", "mesh up"))
        assertEquals(listOf(FleetNotify.STOP_LABEL), n.actions.map { it.title.toString() })
        assertEquals("8002 up", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("8002 up\nmesh up", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals("Server", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test fun keepAliveChannelIsLowAndSilent() {
        keepAlive()
        val ch = ctx.getSystemService(NotificationManager::class.java).getNotificationChannel(FleetNotify.CHANNEL_KEEP_ALIVE)
        assertEquals(FleetNotify.CHANNEL_KEEP_ALIVE_NAME, ch.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_LOW, ch.importance)
        assertNull(ch.sound)
    }

    @Test fun keepAliveIsUsedWithStartForeground() {
        val svc = Robolectric.buildService(KeepAliveSvc::class.java).create().get()
        val shadow = shadowOf(svc)
        assertNotNull(shadow.lastForegroundNotification)
        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertEquals(FleetNotify.CHANNEL_KEEP_ALIVE, shadow.lastForegroundNotification.channelId)
    }

    @Test fun extraActionsFollowStop() {
        val n = FleetNotify.keepAliveNotification(ctx, android.R.drawable.ic_dialog_info, "T", listOf("l"), pi(),
            extraActions = listOf(FleetNotify.Action(0, "Wake lock", pi())))
        assertEquals(listOf("Stop", "Wake lock"), n.actions.map { it.title.toString() })
    }

    @Test fun dataWidgetIsOngoingButNotAForegroundServiceNotification() {
        val n = FleetNotify.dataWidgetNotification(ctx, android.R.drawable.ic_dialog_info, "Battery", listOf("80%", "2h to full"))
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(0, n.flags and Notification.FLAG_FOREGROUND_SERVICE)
        assertEquals(NotificationCompat.FOREGROUND_SERVICE_DEFAULT, NotificationCompat.getForegroundServiceBehavior(n))
        assertEquals(FleetNotify.CHANNEL_DATA_WIDGET, n.channelId)
        assertTrue(n.actions == null || n.actions.isEmpty())
    }

    @Test fun dataWidgetOwnerNeverStartsForeground() {
        val svc = Robolectric.buildService(WidgetSvc::class.java).create().get()
        assertNull(shadowOf(svc).lastForegroundNotification)
        val posted = shadowOf(ctx.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(1, posted.size)
        assertEquals(0, posted[0].flags and Notification.FLAG_FOREGROUND_SERVICE)
    }

    @Test fun unpinnedDataWidgetIsSwipeable() {
        val n = FleetNotify.dataWidgetNotification(ctx, android.R.drawable.ic_dialog_info, "W", listOf("x"), pinned = false)
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
    }

    @Test fun portsLineAndProbe() {
        assertEquals("ports 8002 up · 8024 down", KeepAliveStatus.portsLine("ports", linkedMapOf(8002 to true, 8024 to false)))
        ServerSocket(0).use { assertTrue(KeepAliveStatus.portUp(it.localPort)) }
        val closed = ServerSocket(0).use { it.localPort }
        assertFalse(KeepAliveStatus.portUp(closed))
    }

    @Test fun meshIsUpOnlyForAnUpTunnel() {
        assertTrue(KeepAliveStatus.meshUp(listOf("wlan0" to true, "wg0" to true)))
        assertFalse(KeepAliveStatus.meshUp(listOf("wlan0" to true, "wg0" to false)))
        assertFalse(KeepAliveStatus.meshUp(emptyList()))
        assertEquals("mesh down", KeepAliveStatus.meshLine(false))
    }
}

package com.diegonmarcos.cloudstore

import android.app.Application
import android.app.Notification
import com.diegonmarcos.superapp.updater.BatchForeground
import com.diegonmarcos.superapp.updater.BatchForegroundService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** While the Store is kept alive (a held batch) it shows the fleet keep-alive badge: ongoing, FGS, update state, Stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreKeepAliveBadgeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val defaultLines = BatchForeground.statusLines

    @After fun restore() { BatchForeground.statusLines = defaultLines }

    private fun state() { BatchForeground.statusLines = { listOf("Downloading Maps 1.2 · 40% · 2 of 5", "3 updates pending") } }

    @Test fun `the badge is ongoing, carries the update state and has a Stop action`() {
        state()
        val n = BatchForeground.notification(app)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(listOf("Stop"), n.actions.map { it.title.toString() })
        assertEquals("Downloading Maps 1.2 · 40% · 2 of 5", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("3 updates pending"))
    }

    @Test fun `the service enters the foreground with that notification`() {
        state()
        val svc = Robolectric.buildService(BatchForegroundService::class.java).create().get()
        svc.onStartCommand(null, 0, 1)
        val shadow = shadowOf(svc)
        assertNotNull(shadow.lastForegroundNotification)
        assertTrue(shadow.isLastForegroundNotificationAttached)
        assertTrue(shadow.lastForegroundNotification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("3 updates pending"))
    }

    @Test fun `with no wired status the badge still shows a line and never goes empty`() {
        BatchForeground.statusLines = { emptyList() }
        val n = BatchForeground.notification(app)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().isNotBlank())
    }
}

package app.sterna.push

import android.app.Application
import android.app.Notification
import app.sterna.R
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.settings.NotificationContent
import com.diegonmarcos.superapp.core.FleetNotify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** The push service's badge shows MAIL DATA (unread count, latest sender and subject) and is a keep-alive notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class KeepAliveBadgeTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private val texts = KeepAliveBadge.Texts(
        unread = { "$it unread" }, noUnread = "No unread mail", watching = "Watching for new mail",
        hidden = "Message hidden", unknownSender = "(unknown sender)", noSubject = "(no subject)",
    )

    private fun row(from: String? = "Alice", subject: String? = "Lunch on Friday") =
        RecentEmailRow("m1", "a1", "inbox", subject, from, "alice@example.org", false, 1L, null)

    @Test fun `the headline is the unread count and the first line the latest sender and subject`() {
        val c = KeepAliveBadge.content(3, row(), NotificationContent.SENDER_AND_SUBJECT, false, texts)
        assertEquals("3 unread", c.title)
        assertEquals("Alice - Lunch on Friday", c.lines.first())
        assertTrue(c.lines.contains("Watching for new mail"))
    }

    @Test fun `no unread says so, and an unknown count falls back to the watching line`() {
        assertEquals("No unread mail", KeepAliveBadge.content(0, row(), NotificationContent.SENDER_AND_SUBJECT, false, texts).title)
        val unknown = KeepAliveBadge.content(null, null, NotificationContent.SENDER_AND_SUBJECT, false, texts)
        assertEquals("Watching for new mail", unknown.title)
        assertTrue(unknown.lines.isEmpty())
    }

    @Test fun `the content setting and the app lock hide names exactly as on the widget`() {
        val none = KeepAliveBadge.content(2, row(), NotificationContent.NONE, false, texts)
        assertEquals("2 unread", none.title)
        assertEquals("Message hidden", none.lines.first())
        val locked = KeepAliveBadge.content(2, row(), NotificationContent.SENDER_AND_SUBJECT, true, texts)
        assertFalse(locked.lines.any { it.contains("Alice") || it.contains("Lunch") })
        val senderOnly = KeepAliveBadge.content(2, row(), NotificationContent.SENDER_ONLY, false, texts)
        assertEquals("Alice - Message hidden", senderOnly.lines.first())
    }

    @Test fun `a blank sender or subject takes the stand-ins`() {
        val c = KeepAliveBadge.content(1, row(from = "", subject = " ").copy(fromEmail = null), NotificationContent.SENDER_AND_SUBJECT, false, texts)
        assertEquals("(unknown sender) - (no subject)", c.lines.first())
    }

    @Test fun `the service notification is an ongoing keep-alive with a Stop action and the mail data`() {
        val badge = KeepAliveBadge.content(3, row(), NotificationContent.SENDER_AND_SUBJECT, false, KeepAliveBadge.texts(app))
        val n = Notifications.serviceNotification(app, badge)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(FleetNotify.CHANNEL_KEEP_ALIVE, n.channelId)
        assertTrue("a tap must open the app", n.contentIntent != null)
        assertEquals(listOf(app.getString(R.string.notif_keepalive_stop)), n.actions.map { it.title.toString() })
        assertEquals(badge.title, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("3"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Alice"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Lunch on Friday"))
    }

    @Test fun `the first frame, before the data lands, is still a keep-alive with Stop`() {
        val n = Notifications.serviceNotification(app)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(1, n.actions.size)
    }

    @Test fun `the push service starts foreground through the shared helper and refreshes the badge from mail`() {
        val svc = File("src/main/kotlin/app/sterna/push/PushService.kt").readText()
        assertTrue(svc.contains("FleetNotify.startKeepAlive("))
        assertTrue(svc.contains("KeepAliveBadge.read("))
        assertTrue(svc.contains("ACTION_STOP"))
        val notif = File("src/main/kotlin/app/sterna/push/Notifications.kt").readText()
        assertTrue(notif.contains("FleetNotify.keepAliveNotification("))
    }
}

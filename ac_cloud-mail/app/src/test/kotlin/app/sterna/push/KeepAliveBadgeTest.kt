package app.sterna.push

import android.app.Application
import android.app.Notification
import app.sterna.R
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.settings.NotificationContent
import android.app.NotificationManager
import com.diegonmarcos.superapp.core.BackgroundApps
import com.diegonmarcos.superapp.core.BackgroundCards
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
        assertEquals(FleetNotify.CHANNEL_BACKGROUND_SERVICE, n.channelId)   // #1965 the silent channel (was CHANNEL_KEEP_ALIVE)
        assertTrue("a tap must open the app", n.contentIntent != null)
        // #1965 Stop stays first and unchanged; Sync now is added after it.
        assertEquals(listOf(app.getString(R.string.notif_keepalive_stop), Notifications.SYNC_LABEL), n.actions.map { it.title.toString() })
        assertEquals(badge.title, n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("3"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Alice"))
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Lunch on Friday"))
    }

    @Test fun `the first frame, before the data lands, is still a keep-alive with Stop`() {
        val n = Notifications.serviceNotification(app)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(app.getString(R.string.notif_keepalive_stop), n.actions.first().title.toString())
        assertEquals(2, n.actions.size)   // #1965 Stop + Sync now
    }

    @Test fun `the push service starts foreground through the shared helper and refreshes the badge from mail`() {
        val svc = File("src/main/kotlin/app/sterna/push/PushService.kt").readText()
        assertTrue(svc.contains("FleetNotify.startKeepAlive("))
        assertTrue(svc.contains("KeepAliveBadge.read("))
        assertTrue(svc.contains("ACTION_STOP"))
        val notif = File("src/main/kotlin/app/sterna/push/Notifications.kt").readText()
        assertTrue(notif.contains("FleetNotify.keepAliveNotification("))
    }

    // ── #1944 per-folder unread for the categories 0* to 9*, in the shared background group ──

    @Test fun `only folders whose name starts with a digit 0 to 9 get a line, one per folder, in name order`() {
        val lines = KeepAliveBadge.folderLines(
            listOf("1_Work" to 4, "0_Inbox" to 12, "Inbox" to 99, "Archive" to 7, "9_Later" to 0, "_x" to 5, "5_Money" to 2), texts,
        )
        assertEquals(listOf("0_Inbox · 12 unread", "1_Work · 4 unread", "5_Money · 2 unread", "9_Later · 0 unread"), lines)
    }

    @Test fun `every digit from 0 to 9 is a category and a letter is not`() {
        ('0'..'9').forEach { assertTrue("$it", KeepAliveBadge.isCategoryFolder("${it}_f")) }
        assertFalse(KeepAliveBadge.isCategoryFolder("A_f"))
        assertFalse(KeepAliveBadge.isCategoryFolder(""))
    }

    @Test fun `the same folder on two accounts is one line with the counts added`() {
        assertEquals(listOf("0_Inbox · 5 unread"), KeepAliveBadge.folderLines(listOf("0_Inbox" to 2, "0_Inbox" to 3), texts))
    }

    @Test fun `the badge content carries the folder lines before the watching line`() {
        val c = KeepAliveBadge.content(3, row(), NotificationContent.SENDER_AND_SUBJECT, false, texts, folders = listOf("2_Bills" to 1, "Misc" to 8))
        assertTrue(c.lines.contains("2_Bills · 1 unread"))
        assertFalse(c.lines.any { it.startsWith("Misc") })
        assertEquals("Watching for new mail", c.lines.last())
    }

    @Test fun `the card is in the shared group and the summary is the groups ongoing summary`() {
        val n = Notifications.serviceNotification(app, KeepAliveBadge.content(1, row(), NotificationContent.SENDER_AND_SUBJECT, false, texts, folders = listOf("0_a" to 1)))
        assertEquals(FleetNotify.GROUP_RUNNING, n.group)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("0_a · 1 unread"))
        val s = Notifications.backgroundSummary(app)
        assertEquals(FleetNotify.GROUP_RUNNING, s.group)
        assertTrue(s.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertTrue(s.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun `the push service posts the summary with its card and removes it when it stops`() {
        val svc = File("src/main/kotlin/app/sterna/push/PushService.kt").readText()
        assertTrue(svc.contains("FleetNotify.SUMMARY_ID, Notifications.backgroundSummary("))
        assertTrue(svc.substringAfter("override fun onDestroy()").contains("FleetNotify.cancelBackgroundSummary("))
        assertTrue(File("src/main/kotlin/app/sterna/push/KeepAliveBadge.kt").readText().contains("observeMailboxes("))
    }

    // ── #1965 the "Background Apps" card: declared fields and buttons, the silent FGS channel, nothing lost ──

    private fun richBadge() = KeepAliveBadge.content(
        7, row(), NotificationContent.SENDER_AND_SUBJECT, false, texts,
        folders = listOf("0_Inbox" to 5, "3_Bills" to 2, "Archive" to 9), lastSync = "18:34",
    )

    @Test fun `the Background Apps card carries every declared Mail field and button`() {
        val card = BackgroundApps.fromNotification(Notifications.serviceNotification(app, richBadge()), Notifications.SERVICE_ID)!!
        assertEquals(BackgroundCards.MAIL, card.kind)
        assertEquals(emptyList<String>(), BackgroundCards.missingFields(card))
        assertEquals(emptyList<String>(), BackgroundCards.missingButtons(card))
        assertEquals(listOf(BackgroundCards.SYNC, BackgroundCards.OPEN, BackgroundCards.PAUSE), card.buttonKinds())
        val v = card.fields.groupBy({ it.key }, { it.value })
        assertEquals(listOf("7 unread"), v[BackgroundCards.Mail.UNREAD])
        assertEquals(listOf("0_Inbox · 5 unread", "3_Bills · 2 unread"), v[BackgroundCards.Mail.FOLDER])
        assertEquals(listOf("18:34"), v[BackgroundCards.Mail.LAST_SYNC])
        assertEquals(listOf("Alice - Lunch on Friday"), v[BackgroundCards.Mail.LATEST])
    }

    @Test fun `an unknown count says counting, never the in-flight headline as the unread field`() {
        val c = KeepAliveBadge.content(null, null, NotificationContent.SENDER_AND_SUBJECT, false, texts, folders = listOf("1_a" to 1))
        assertEquals("counting…", c.fields.first { it.key == BackgroundCards.Mail.UNREAD }.value)
        assertEquals("never", c.fields.first { it.key == BackgroundCards.Mail.LAST_SYNC }.value)
        assertEquals(listOf("1_a · 1 unread"), c.fields.filter { it.key == BackgroundCards.Mail.FOLDER }.map { it.value })
    }

    @Test fun `a category folder inside an IMAP path counts by its own name`() {
        assertTrue(KeepAliveBadge.isCategoryFolder("INBOX/4_Travel"))
        assertEquals(listOf("4_Travel · 3 unread"), KeepAliveBadge.folderLines(listOf("INBOX/4_Travel" to 3), texts))
        assertTrue(KeepAliveBadge.isCategoryFolder("1. Urgent"))
    }

    @Test fun `the mail FGS card is on the silent MIN Background service channel`() {
        val n = Notifications.serviceNotification(app, richBadge())
        assertEquals(FleetNotify.CHANNEL_BACKGROUND_SERVICE, n.channelId)
        val ch = app.getSystemService(NotificationManager::class.java).getNotificationChannel(n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_MIN, ch.importance)
        assertEquals(null, ch.sound)
    }

    @Test fun `rule 16 every line of the app card is in the Background Apps card`() {
        val badge = richBadge()
        val card = BackgroundApps.fromNotification(Notifications.serviceNotification(app, badge), Notifications.SERVICE_ID)!!
        val shown = card.fields.map { it.value } + BackgroundApps.lines(card)
        badge.lines.forEach { assertTrue("lost from the card: $it", it in shown) }
        assertTrue(badge.title in shown)
        // the app card keeps every former line and gained the last sync
        assertTrue(badge.lines.contains("Watching for new mail"))
        assertTrue(badge.lines.contains("Last sync: 18:34"))
    }

    @Test fun `the Sync now action reaches the running service`() {
        val svc = File("src/main/kotlin/app/sterna/push/PushService.kt").readText()
        assertTrue(svc.contains("intent?.action == ACTION_SYNC"))
        assertTrue(svc.substringAfter("intent?.action == ACTION_SYNC").substringBefore("return START_STICKY").contains("OfflineSyncWorker.runNow(this)"))
    }
}

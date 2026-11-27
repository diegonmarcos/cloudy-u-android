package com.diegonmarcos.superapp.core

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1965 The ONE "Background Apps" group: every fleet app's background card lands in the hub's single group with one summary,
 * each card carries its declared fields and buttons, a keep-alive sends its card to the hub, and the FGS card itself is on the
 * silent MIN "Background service" channel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackgroundAppsTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val nm get() = ctx.getSystemService(NotificationManager::class.java)
    private fun pi(a: String): PendingIntent = PendingIntent.getBroadcast(ctx, a.hashCode(), Intent(a), PendingIntent.FLAG_IMMUTABLE)
    private fun btn(kind: String) = BackgroundApps.Button(kind, kind, pi(kind))

    private val mail = BackgroundApps.Card("app.sterna", 1, BackgroundCards.MAIL, "Cloud Mail", "7 unread",
        listOf(BackgroundApps.Field("Unread", "7 unread"), BackgroundApps.Field("Folder", "0_Inbox · 5 unread"),
            BackgroundApps.Field("Folder", "3_Bills · 2 unread"), BackgroundApps.Field("Last sync", "18:34"),
            BackgroundApps.Field("Push", "Watching for new mail"), BackgroundApps.Field("Latest", "Alice - Lunch")),
        listOf(btn(BackgroundCards.SYNC), btn(BackgroundCards.OPEN), btn(BackgroundCards.PAUSE)), pi("open-mail"))
    private val store = BackgroundApps.Card("com.diegonmarcos.cloudstore", 0xB47C, BackgroundCards.STORE, "Cloud Store", "Updates",
        listOf(BackgroundApps.Field("Progress", "3 apps · 40%"), BackgroundApps.Field("Speed", "2.0 MB/s"),
            BackgroundApps.Field("App", "Maps · downloading · 40%"), BackgroundApps.Field("State", "2 pending")),
        listOf(btn(BackgroundCards.STOP), btn(BackgroundCards.OPEN)))
    private val nav = BackgroundApps.Card("com.diegonmarcos.cloudnav", 0xA1, BackgroundCards.NAV, "Cloud Nav", "To Home",
        listOf(BackgroundApps.Field("Route", "Home · Car"), BackgroundApps.Field("Next turn", "Left on Main St"),
            BackgroundApps.Field("Distance", "3.2 km"), BackgroundApps.Field("ETA", "14:05"), BackgroundApps.Field("Tracking", "Points: 12")),
        listOf(btn(BackgroundCards.OPEN)))
    private val web = BackgroundApps.Card("com.diegonmarcos.cloudwebserver", 8002, BackgroundCards.WEBSERVER, "Cloud C3 WebServer", "Cloud C3 WebServer",
        BackgroundCards.webserverFields(true, "http://127.0.0.1:8002", "Ports 8002 up · 8024 down", "mesh up", 2, 125_000, "18:20"),
        listOf(btn(BackgroundCards.STOP), btn(BackgroundCards.OPEN)))
    private val term = BackgroundApps.Card("cld.termux", 1337, BackgroundCards.OTHER, "Cloud Termux", "1 session",
        BackgroundApps.freeFields(listOf("1 session", "sshd up")), listOf(btn(BackgroundCards.STOP)))

    private fun children() = nm.activeNotifications.filter { it.tag != BackgroundApps.SUMMARY_TAG }
    private fun summary() = nm.activeNotifications.firstOrNull { it.tag == BackgroundApps.SUMMARY_TAG }

    @Before fun clean() { nm.cancelAll() }

    @Test fun `every background app lands in the one Background Apps group`() {
        listOf(mail, store, nav, web, term).forEach { BackgroundApps.hubPost(ctx, it) }
        val kids = children()
        assertEquals(5, kids.size)
        kids.forEach { assertEquals(BackgroundApps.GROUP, it.notification.group) }
        assertEquals("one tag per app", 5, kids.map { it.tag }.distinct().size)
        val s = summary()
        assertNotNull("one summary heads the group", s)
        assertEquals(BackgroundApps.GROUP, s!!.notification.group)
        assertTrue(s.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(BackgroundApps.TITLE, s.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val lines = s.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(listOf("Cloud C3 WebServer", "Cloud Mail · 7 unread", "Cloud Nav · To Home", "Cloud Store · Updates", "Cloud Termux · 1 session"), lines)
    }

    @Test fun `a retraction takes the app out and the last one takes the summary`() {
        listOf(mail, store).forEach { BackgroundApps.hubPost(ctx, it) }
        BackgroundApps.hubRetract(ctx, mail.pkg, null)
        assertEquals(listOf(BackgroundApps.tagOf(store.pkg)), children().map { it.tag })
        assertEquals(1, summary()!!.notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.size)
        BackgroundApps.hubRetract(ctx, store.pkg, store.id)
        assertTrue(children().isEmpty())
        assertNull(summary())
    }

    @Test fun `a child card is ongoing, silent and shows every field and its buttons with icons`() {
        val n = BackgroundApps.childNotification(ctx, mail)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(BackgroundApps.CHANNEL_HUB, n.channelId)
        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel(BackgroundApps.CHANNEL_HUB).importance)
        assertEquals("Cloud Mail · 7 unread", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val big = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        listOf("Unread: 7 unread", "Folder: 0_Inbox · 5 unread", "Folder: 3_Bills · 2 unread", "Last sync: 18:34", "Latest: Alice - Lunch")
            .forEach { assertTrue(it, big.contains(it)) }
        assertEquals(listOf("sync", "open", "pause"), n.actions.map { it.title.toString() })
        assertEquals(BackgroundApps.iconFor(BackgroundCards.SYNC), n.actions[0].icon)
        assertTrue("every button has an icon", n.actions.all { it.icon != 0 })
        assertTrue("a tap acts in the app (its own PendingIntent)", n.contentIntent === mail.content || n.contentIntent == mail.content)
    }

    @Test fun `a card round-trips through the wire bundle with its fields, buttons and intents`() {
        val back = BackgroundApps.fromBundle(BackgroundApps.toBundle(mail))!!
        assertEquals(mail.fields, back.fields)
        assertEquals(mail.buttonKinds(), back.buttonKinds())
        assertEquals(mail.buttons.map { it.intent }, back.buttons.map { it.intent })
        assertEquals(mail.content, back.content)
        assertEquals(listOf(mail.pkg, mail.kind, mail.app, mail.title), listOf(back.pkg, back.kind, back.app, back.title))
    }

    @Test fun `each declared card is complete`() {
        listOf(mail, store, nav, web).forEach {
            assertEquals(it.kind, emptyList<String>(), BackgroundCards.missingFields(it))
            assertEquals(it.kind, emptyList<String>(), BackgroundCards.missingButtons(it))
        }
        assertEquals(listOf("Unread", "Folder", "Last sync", "Push", "Latest"), BackgroundCards.spec(BackgroundCards.MAIL).fields)
        assertEquals(listOf(BackgroundCards.SYNC, BackgroundCards.OPEN, BackgroundCards.PAUSE), BackgroundCards.spec(BackgroundCards.MAIL).buttons)
        assertEquals(BackgroundCards.OTHER, BackgroundCards.spec("unknown").kind)
    }

    @Test fun `the webserver card says state, URL, ports, mesh, clients and uptime`() {
        val v = web.fields.associate { it.key to it.value }
        assertEquals("serving", v["State"])
        assertEquals("http://127.0.0.1:8002", v["URL"])
        assertEquals("Ports 8002 up · 8024 down", v["Ports"])
        assertEquals("mesh up", v["Mesh"])
        assertEquals("2", v["Clients"])
        assertEquals("2m 05s · since 18:20", v["Uptime"])
        val hidden = BackgroundCards.webserverFields(false, "u", "p", "m", null, 0).associate { it.key to it.value }
        assertEquals("not readable on this Android", hidden["Clients"])
        assertEquals("starting, port not answering yet", hidden["State"])
        assertEquals("2h 01m", BackgroundCards.uptime(7_260_000))
    }

    @Test fun `clients are the established connections on the port`() {
        val tcp = """  sl  local_address rem_address   st
   0: 0100007F:1F42 0100007F:A1B2 01 0
   1: 0100007F:1F42 00000000:0000 0A 0
   2: 0100007F:1F42 0100007F:A1B3 01 0
   3: 0100007F:1F58 0100007F:A1B4 01 0"""
        assertEquals(2, BackgroundCards.clientsOn(8002, listOf(tcp, null)))
        assertNull(BackgroundCards.clientsOn(8002, listOf(null, null)))
    }

    @Test fun `a keep-alive carries its card and posting it sends the card to the hub`() {
        val n = FleetNotify.keepAliveNotification(ctx, android.R.drawable.ic_dialog_info, "Server", listOf("8002 up", "mesh up"), pi("stop"),
            contentIntent = pi("open"))
        val card = BackgroundApps.fromNotification(n, 8002)!!
        assertEquals(8002, card.id)
        assertEquals(listOf("8002 up", "mesh up"), BackgroundApps.lines(card))
        assertEquals(listOf(BackgroundCards.STOP, BackgroundCards.OPEN), card.buttonKinds())
        FleetNotify.post(ctx, 8002, n)
        val sent = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last { it.action == BackgroundApps.ACTION_CARD }
        assertEquals(BackgroundApps.HUB_PACKAGE, sent.`package`)
        assertEquals(8002, BackgroundApps.fromBundle(sent.getBundleExtra(BackgroundApps.X_MARK))!!.id)
        FleetNotify.cancelBackgroundSummary(ctx)
        val retract = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.last()
        assertEquals(BackgroundApps.ACTION_RETRACT, retract.action)
        assertEquals(BackgroundApps.HUB_PACKAGE, retract.`package`)
    }

    @Test fun `a data widget is never sent to the hub`() {
        val before = shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.count { it.action == BackgroundApps.ACTION_CARD }
        FleetNotify.post(ctx, 9, FleetNotify.dataWidgetNotification(ctx, android.R.drawable.ic_dialog_info, "Weather", listOf("12 C")))
        assertEquals(before, shadowOf(RuntimeEnvironment.getApplication()).broadcastIntents.count { it.action == BackgroundApps.ACTION_CARD })
    }

    @Test fun `the FGS card moves to the silent MIN Background service channel`() {
        val n = FleetNotify.keepAliveNotification(ctx, android.R.drawable.ic_dialog_info, "Server", listOf("up"), pi("stop"))
        assertEquals(FleetNotify.CHANNEL_BACKGROUND_SERVICE, n.channelId)
        val ch = nm.getNotificationChannel(FleetNotify.CHANNEL_BACKGROUND_SERVICE)
        assertEquals(NotificationManager.IMPORTANCE_MIN, ch.importance)
        assertEquals(FleetNotify.CHANNEL_BACKGROUND_SERVICE_NAME, ch.name.toString())
        assertNull(ch.sound)
        assertTrue("still a legal ongoing FGS card", n.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    @Test fun `the hub receiver posts a card and takes it back`() {
        val r = BackgroundApps.Receiver()
        r.onReceive(ctx, Intent(BackgroundApps.ACTION_CARD).putExtra(BackgroundApps.X_MARK, BackgroundApps.toBundle(nav)))
        assertEquals(listOf(BackgroundApps.tagOf(nav.pkg)), children().map { it.tag })
        r.onReceive(ctx, Intent(BackgroundApps.ACTION_RETRACT).putExtra("fleet.bg.pkg", nav.pkg).putExtra("fleet.bg.id", nav.id))
        assertTrue(children().isEmpty())
    }

    @Test fun `reconcile drops a card whose source left the shade`() {
        listOf(mail, store).forEach { BackgroundApps.hubPost(ctx, it) }
        BackgroundApps.reconcile(ctx, setOf(store.pkg to store.id))
        assertEquals(listOf(BackgroundApps.tagOf(store.pkg)), children().map { it.tag })
    }
}

package com.diegonmarcos.cloudstore

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import com.diegonmarcos.superapp.appstore.JobBoard
import com.diegonmarcos.superapp.appstore.StoreJobs
import com.diegonmarcos.superapp.appstore.StoreProgressCard
import com.diegonmarcos.superapp.appstore.StoreStages
import android.content.Intent
import com.diegonmarcos.superapp.core.BackgroundApps
import com.diegonmarcos.superapp.core.BackgroundCards
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.updater.BatchForeground
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

/** #1944 Cloud Store's "running in the background" card: full per-app update progress, ongoing, in the shared group, gone on stop. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StoreBackgroundCardTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before fun fresh() { StoreJobs.board.reset() }
    @After fun clean() { StoreJobs.board.reset() }

    private fun board() = StoreJobs.board.apply {
        queue("pkg.maps", "Maps"); queue("pkg.chat", "Chat"); queue("pkg.mail", "Mail"); queue("pkg.nav", "Nav")
        download("pkg.maps", 20_000_000, 50_000_000)
        phase("pkg.chat", JobBoard.Phase.VERIFYING)
        fail("pkg.nav", "DNS: cannot resolve")
    }

    @Test fun `each app line carries name, phase, percent, bytes, queue position and the last error`() {
        board()
        val l = StoreProgressCard.lines(StoreJobs.board.rows(), StoreJobs.board.overall())
        assertTrue(l.first().startsWith("4 apps"))
        assertTrue(l.first().contains("1 failed"))
        assertTrue(l.any { it.startsWith("Maps · downloading · 40% · 20.0 MB / 50.0 MB") })
        assertTrue(l.any { it.startsWith("Chat · verifying") })
        assertTrue(l.any { it.startsWith("Mail · queued") && it.contains("#1 of 1 in queue") })
        assertTrue(l.any { it.startsWith("Nav · failed") && it.contains("error: DNS: cannot resolve") })
    }

    @Test fun `no jobs means no progress lines`() {
        assertTrue(StoreProgressCard.lines(emptyList(), JobBoard.Overall(0, 0, 0)).isEmpty())
    }

    @Test fun `a long queue is bounded and says how many more`() {
        (1..15).forEach { StoreJobs.board.queue("p$it", "App$it") }
        val l = StoreProgressCard.lines(StoreJobs.board.rows(), StoreJobs.board.overall())
        assertEquals(1 + StoreProgressCard.MAX_ROWS + 1, l.size)
        assertEquals("+5 more", l.last())
    }

    @Test fun `the Store wires the progress lines into the keep-alive card and keeps the former lines`() {
        Class.forName(StoreStages::class.java.name)   // runs the object init that wires BatchForeground
        board()
        val lines = BatchForeground.statusLines(app)
        assertTrue(lines.any { it.startsWith("Maps · downloading") })
        assertTrue("the former update-state line stays", lines.any { it.contains("Checking") || it.contains("pending") || it.contains("up to date") })
    }

    @Test fun `the card is ongoing, in the shared group, with a Stop action`() {
        val n = BatchForeground.notification(app)
        assertEquals(FleetNotify.GROUP_RUNNING, n.group)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_NO_CLEAR != 0)
        assertEquals(listOf("Stop"), n.actions.map { it.title.toString() })
    }

    @Test fun `a running batch posts the group summary and stopping removes it with the card`() {
        BatchForeground.begin(app)
        val summary = shadowOf(nm).getNotification(FleetNotify.SUMMARY_ID)
        assertNotNull(summary)
        assertTrue(summary.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(FleetNotify.GROUP_RUNNING, summary.group)
        BatchForeground.end(app)
        assertNull(shadowOf(nm).getNotification(FleetNotify.SUMMARY_ID))
        assertNull(shadowOf(nm).getNotification(BatchForeground.ID))
        assertFalse(BatchForeground.isHeld())
    }

    // ── #1965 the "Background Apps" card: declared fields and buttons, per-app speed, the silent FGS channel, nothing lost ──

    private fun wired() {
        Class.forName(StoreStages::class.java.name)
        BatchForeground.launch = { ctx -> Intent(Intent.ACTION_MAIN).setPackage(ctx.packageName) }
    }

    @Test fun `the Background Apps card carries every declared Store field and button`() {
        wired(); board()
        val card = BackgroundApps.fromNotification(BatchForeground.notification(app), BatchForeground.ID)!!
        assertEquals(BackgroundCards.STORE, card.kind)
        assertEquals(emptyList<String>(), BackgroundCards.missingFields(card))
        assertEquals(emptyList<String>(), BackgroundCards.missingButtons(card))
        val apps = card.fields.filter { it.key == BackgroundCards.Store.APP }.map { it.value }
        assertTrue(apps.any { it.startsWith("Maps · downloading · 40%") })
        assertTrue(apps.any { it.startsWith("Nav · failed") && it.contains("error: DNS: cannot resolve") })
        assertTrue(card.fields.first { it.key == BackgroundCards.Store.PROGRESS }.value.startsWith("4 apps"))
    }

    @Test fun `each downloading app shows its speed from two refreshes`() {
        board()
        StoreProgressCard.speeds(StoreJobs.board.rows(), now = 10_000)
        StoreJobs.board.download("pkg.maps", 24_000_000, 50_000_000)
        val sp = StoreProgressCard.speeds(StoreJobs.board.rows(), now = 12_000)
        assertEquals(2_000_000L, sp["pkg.maps"])
        val f = StoreProgressCard.fields(StoreJobs.board.rows(), StoreJobs.board.overall(), sp, listOf("2 pending"))
        assertEquals(StoreProgressCard.speedText(2_000_000), f.first { it.first == StoreProgressCard.F_SPEED }.second)
        assertTrue(f.any { it.first == StoreProgressCard.F_APP && it.second.startsWith("Maps · downloading · 48% · " + StoreProgressCard.speedText(2_000_000)) })
        assertEquals(listOf("2 pending"), f.filter { it.first == StoreProgressCard.F_STATE }.map { it.second })
        assertEquals(null, StoreProgressCard.speed(0, 0, 5, 10))
    }

    @Test fun `the store FGS card is on the silent MIN Background service channel`() {
        val n = BatchForeground.notification(app)
        assertTrue(n.channelId, n.channelId.startsWith(FleetNotify.CHANNEL_BACKGROUND_SERVICE))   // the Store posts on the _silent_v2 id
        assertEquals(NotificationManager.IMPORTANCE_MIN, nm.getNotificationChannel(n.channelId).importance)
    }

    @Test fun `rule 16 every line of the Store app card is in the Background Apps card`() {
        wired(); board()
        val n = BatchForeground.notification(app)
        val card = BackgroundApps.fromNotification(n, BatchForeground.ID)!!
        val values = card.fields.map { it.value }
        n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().split("\n").filter { it.isNotBlank() }
            .forEach { assertTrue("lost from the card: $it", it in values) }
    }
}

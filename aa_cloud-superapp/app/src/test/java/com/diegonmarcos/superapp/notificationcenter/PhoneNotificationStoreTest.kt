package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.PhoneNotificationStore.Entry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * #1629 The phone-notification history: every reader sees the list as it is now, the disk sees it a moment later and
 * far less often. Before, each post parsed the whole history from the prefs and wrote it back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhoneNotificationStoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var now = 0L
    private val deferred = ArrayList<Pair<Long, Runnable>>()

    @Before fun setUp() {
        PhoneNotificationStore.reset(); PhoneNotifIntake.reset()
        now = 0L; deferred.clear()
        PhoneNotificationStore.clock = { now }
        PhoneNotificationStore.defer = { d, r -> deferred += (now + d) to r }
    }

    @After fun tearDown() { PhoneNotificationStore.reset(); PhoneNotifIntake.reset() }

    /** Run every queued write whose time has come. */
    private fun runDue() {
        val due = deferred.filter { it.first <= now }
        deferred.removeAll(due.toSet())
        due.forEach { it.second.run() }
    }

    private fun e(key: String, text: String, ts: Long) = Entry(key, ts, "org.example.player", "Player", "Song", text)

    @Test fun `a re-post every second for ten minutes is a handful of disk writes and the reader still sees the real time`() {
        PhoneNotificationStore.push(app, e("k", "playing", 0L))
        now += PhoneNotificationStore.WRITE_GAP_MS; runDue()
        assertEquals(1, PhoneNotificationStore.diskWrites)
        repeat(600) {
            now += 1_000L
            assertTrue(PhoneNotificationStore.touch(app, "k", now))
            assertEquals("the reader sees the move at once", now, PhoneNotificationStore.all(app).first().ts)
            runDue()
        }
        val writes = PhoneNotificationStore.diskWrites
        assertTrue("600 re-posts were $writes disk writes (was 600)", writes <= 1 + 600_000L / PhoneNotificationStore.LAZY_GAP_MS + 1)
        assertEquals("the history was parsed from the prefs more than once", 1, PhoneNotificationStore.diskReads)
    }

    @Test fun `a burst of new notifications is one disk write, capped and newest first`() {
        repeat(120) { PhoneNotificationStore.push(app, e("k$it", "t$it", it.toLong())); now += 10 }
        assertEquals("written before the gap ended", 0, PhoneNotificationStore.diskWrites)
        assertEquals("k119", PhoneNotificationStore.all(app).first().key)
        assertEquals(50, PhoneNotificationStore.all(app).size)
        now += PhoneNotificationStore.WRITE_GAP_MS; runDue()
        assertEquals(1, PhoneNotificationStore.diskWrites)
    }

    @Test fun `what was written is what a new process reads, every field`() {
        PhoneNotificationStore.push(app, e("a", "one", 11L))
        PhoneNotificationStore.push(app, Entry("b", 22L, "org.example.chat", "Chat", "Ana", "two"))
        PhoneNotificationStore.flush(app)
        val before = PhoneNotificationStore.all(app)
        PhoneNotificationStore.reset() // a new process: nothing in memory
        assertEquals(before, PhoneNotificationStore.all(app))
        assertEquals(listOf(Entry("b", 22L, "org.example.chat", "Chat", "Ana", "two"), e("a", "one", 11L)), PhoneNotificationStore.all(app))
        assertEquals(mapOf("org.example.chat" to "Chat", "org.example.player" to "Player"), PhoneNotificationStore.apps(app))
    }

    @Test fun `a change is on disk within the short gap even while a lazy write is queued`() {
        PhoneNotificationStore.push(app, e("a", "one", 1L))
        PhoneNotificationStore.flush(app)
        assertEquals(1, PhoneNotificationStore.diskWrites)
        assertTrue(PhoneNotificationStore.touch(app, "a", 2L)) // lazy: due in a minute
        now += 500
        PhoneNotificationStore.push(app, e("b", "two", 3L))    // changed content: due in two seconds
        now += PhoneNotificationStore.WRITE_GAP_MS; runDue()
        assertEquals(2, PhoneNotificationStore.diskWrites)
        PhoneNotificationStore.reset()
        assertEquals(listOf("b", "a"), PhoneNotificationStore.all(app).map { it.key })
        assertEquals(2L, PhoneNotificationStore.all(app).first { it.key == "a" }.ts)
    }

    @Test fun `touching what is not held says so, and clear empties memory and disk`() {
        assertFalse(PhoneNotificationStore.touch(app, "nope", 1L))
        PhoneNotificationStore.push(app, e("a", "one", 1L))
        PhoneNotificationStore.flush(app)
        PhoneNotificationStore.clear(app)
        assertTrue(PhoneNotificationStore.all(app).isEmpty())
        assertFalse(PhoneNotificationStore.touch(app, "a", 2L))
        PhoneNotificationStore.reset()
        assertTrue(PhoneNotificationStore.all(app).isEmpty())
    }

    // ── The listener itself ──────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun sbn(text: String, postTime: Long) = StatusBarNotification(
        "org.example.player", "org.example.player", 5, null, 10123, 0, 0,
        NotificationCompat.Builder(app, "c").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Song").setContentText(text).build(),
        android.os.Process.myUserHandle(), postTime,
    )

    @Test fun `the listener records a re-posting app once, moves its time, and keeps the last state of a burst`() {
        val svc = Robolectric.buildService(PhoneNotificationListenerService::class.java).create().get()
        repeat(600) { svc.onNotificationPosted(sbn("playing", 1_000L + it)) }
        assertEquals(1, PhoneNotificationStore.all(app).size)
        assertEquals("playing", PhoneNotificationStore.all(app).first().text)
        assertEquals("the entry's time is the last post's", 1_599L, PhoneNotificationStore.all(app).first().ts)
        assertEquals("a disk write per post", 0, PhoneNotificationStore.diskWrites)

        // The track changes three times inside the gap: held, then the LAST one is recorded when the gap ends.
        svc.onNotificationPosted(sbn("next 1", 2_000L))
        svc.onNotificationPosted(sbn("next 2", 2_001L))
        svc.onNotificationPosted(sbn("next 3", 2_002L))
        assertEquals("recorded inside the gap", "playing", PhoneNotificationStore.all(app).first().text)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(PhoneNotifIntake.MIN_GAP_MS))
        assertEquals(1, PhoneNotificationStore.all(app).size)
        assertEquals("the last state of the burst was lost", "next 3", PhoneNotificationStore.all(app).first().text)
        assertEquals(2_002L, PhoneNotificationStore.all(app).first().ts)
    }

    @Test fun `a history the owner cleared is filled again by an unchanged re-post`() {
        val svc = Robolectric.buildService(PhoneNotificationListenerService::class.java).create().get()
        svc.onNotificationPosted(sbn("playing", 1_000L))
        PhoneNotificationStore.clear(app)
        svc.onNotificationPosted(sbn("playing", 1_001L))
        assertEquals("an unchanged re-post after a clear was dropped", 1, PhoneNotificationStore.all(app).size)
    }
}

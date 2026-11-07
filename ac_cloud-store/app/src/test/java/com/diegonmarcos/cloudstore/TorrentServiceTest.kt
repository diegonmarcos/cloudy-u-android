package com.diegonmarcos.cloudstore

import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.torrent.MemorySettings
import com.diegonmarcos.superapp.torrent.MemoryStore
import com.diegonmarcos.superapp.torrent.Phase
import com.diegonmarcos.superapp.torrent.RawStatus
import com.diegonmarcos.superapp.torrent.TorrentBackend
import com.diegonmarcos.superapp.torrent.TorrentEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The torrent foreground service: starts on an active add, keeps the engine alive, updates the notification, stops when downloads end. */
@RunWith(RobolectricTestRunner::class)
class TorrentServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private val h = "0123456789abcdef0123456789abcdef01234567"
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private class Session : TorrentBackend {
        val raw = LinkedHashMap<String, RawStatus>()
        var started = false; var stops = 0
        override fun start() { started = true }
        override fun stop() { started = false; stops++ }
        override fun addMagnet(id: String, magnet: String, saveDir: File, paused: Boolean) { raw[id] = RawStatus(id, "", Phase.DOWNLOADING, paused, 0f, 1000, 0, 100, 0, 1, 2, 0, 0) }
        override fun addTorrent(id: String, file: File, saveDir: File, paused: Boolean) = addMagnet(id, "", saveDir, paused)
        override fun snapshot(): Map<String, RawStatus> = LinkedHashMap(raw)
        override fun pause(id: String) { raw[id]?.let { raw[id] = it.copy(paused = true) } }
        override fun resume(id: String) { raw[id]?.let { raw[id] = it.copy(paused = false) } }
        override fun remove(id: String, deleteFiles: Boolean) { raw.remove(id) }
    }

    private lateinit var session: Session
    private lateinit var engine: TorrentEngine
    private var starts = 0
    private val before = TorrentService.engineOf

    @Before fun setUp() {
        session = Session()
        engine = TorrentEngine(session, MemoryStore(), MemorySettings(File(tmp.newFolder("d"), "Torrent")), tmp.newFolder("k"))
        TorrentService.engineOf = { engine }
        engine.onDownloadStarted = { starts++ }
    }

    @After fun tearDown() { TorrentService.engineOf = before }

    /** The worker thread posts back to the main looper: spin until [cond] or fail. */
    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out: $what")
    }

    private fun startService() = Robolectric.buildService(TorrentService::class.java, Intent(app, TorrentService::class.java)).create().startCommand(0, 1)
    private fun note() = shadowOf(app.getSystemService(NotificationManager::class.java)).getNotification(TorrentService.NOTE_ID)

    @Test fun startsOnActiveAddAndShowsAggregateNotification() {
        engine.addMagnet("magnet:?xt=urn:btih:$h&dn=x")
        assertEquals("an active add asks the host to start the service", 1, starts)
        val c = startService()
        assertNotNull("startForeground was called at once", shadowOf(c.get()).lastForegroundNotification)
        await("notification shows the numbers") { note()?.extras?.getCharSequence("android.text")?.toString()?.contains("1 downloading") == true }
        val text = note().extras.getCharSequence("android.text").toString()
        assertTrue(text, text.contains("↓") && text.contains("peers"))
        assertEquals(listOf("Pause all", "Stop"), note().actions.map { it.title.toString() })
    }

    @Test fun keepsTheEngineSessionAliveWhileDownloading() {
        engine.addMagnet("magnet:?xt=urn:btih:$h&dn=x")
        val c = startService()
        await("session started") { session.started }
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        assertTrue(session.started); assertEquals(0, session.stops)
        assertFalse(shadowOf(c.get()).isStoppedBySelf)
    }

    @Test fun locksAreHeldOnlyWhileBytesMoveAndReleasedAtOnce() {
        engine.addMagnet("magnet:?xt=urn:btih:$h&dn=x")
        val c = startService()
        val pm = shadowOf(app.getSystemService(PowerManager::class.java))
        await("wakelock held while downloading") { pm.latestWakeLock?.isHeld == true }
        session.raw[h] = session.raw.getValue(h).copy(phase = Phase.FINISHED, progress = 1f)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        await("service stopped") { shadowOf(c.get()).isStoppedBySelf }
        assertFalse("wakelock released when downloads finish", pm.latestWakeLock?.isHeld == true)
    }

    @Test fun stopsItselfWhenAllPaused() {
        engine.addMagnet("magnet:?xt=urn:btih:$h&dn=x")
        val c = startService()
        await("running") { note() != null }
        engine.pauseAll()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        await("stopped when all paused") { shadowOf(c.get()).isStoppedBySelf }
        assertTrue(shadowOf(c.get()).isForegroundStopped)
    }

    @Test fun pauseAllActionPausesTheDownloadsAndStopsTheService() {
        engine.addMagnet("magnet:?xt=urn:btih:$h&dn=x")
        val c = startService()
        await("running") { note() != null }
        c.get().onStartCommand(Intent(TorrentService.ACTION_PAUSE_ALL), 0, 2)
        await("paused and stopped") { shadowOf(c.get()).isStoppedBySelf }
        assertTrue(engine.status(h)!!.state == com.diegonmarcos.superapp.torrent.TorrentState.PAUSED)
    }

    @Test fun idleSyncDoesNotStartTheService() {
        TorrentService.sync(app, engine)
        assertEquals(null, shadowOf(app).nextStartedService)
    }

    @Test fun syncStartsTheServiceForAnActiveRememberedDownload() {
        engine.addMagnet("magnet:?xt=urn:btih:$h&dn=x")
        TorrentService.sync(app, engine)
        assertEquals(TorrentService::class.java.name, shadowOf(app).nextStartedService.component!!.className)
    }
}

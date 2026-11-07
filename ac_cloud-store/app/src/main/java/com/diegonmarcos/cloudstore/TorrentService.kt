package com.diegonmarcos.cloudstore

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.core.SilentChannels
import com.diegonmarcos.superapp.torrent.TorrentActivity
import com.diegonmarcos.superapp.torrent.TorrentEngine
import com.diegonmarcos.superapp.torrent.TorrentEngines
import java.util.concurrent.Executors

/**
 * The torrent download foreground service (type dataSync). ACTIVE-DOWNLOAD-SCOPED, never an always-on badge:
 *  - it starts when a torrent is added or resumed, or at launch when the remembered list has an active download ([sync]);
 *  - while it runs it keeps the process, and so the [TorrentEngines] singleton and its libtorrent session, alive after the Store UI closes;
 *  - every [TICK_MS] it reads [TorrentEngine.activity] and updates one ongoing notification ("N downloading · X% · rate · peers",
 *    Pause all / Stop actions);
 *  - the moment no torrent is downloading (all finished, paused, errored or removed) it calls stopForeground + stopSelf.
 * A partial WakeLock and a WifiLock are held only while bytes are actually moving ([TorrentActivity.transferring] > 0) and are released
 * as soon as that stops, and in onDestroy; nothing is held at idle. The engine logic stays in libs:torrent; this class owns no transfer state.
 */
class TorrentService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    @Volatile private var stopped = false
    private val tick = object : Runnable { override fun run() { poll(); if (!stopped) main.postDelayed(this, TICK_MS) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A foreground start must reach startForeground within seconds: do it first, with the last known numbers.
        runCatching {
            ServiceCompat.startForeground(this, NOTE_ID, build(TorrentActivity(0, 0, -1f, 0, 0)),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        }.onFailure { stopSelf(); return START_NOT_STICKY }
        val engine = engineOf(this)
        when (intent?.action) {
            ACTION_PAUSE_ALL -> worker.execute { engine.pauseAll(); main.post { poll() } }
            ACTION_STOP -> worker.execute {
                engine.activeIds().forEach { engine.pause(it) }
                main.post { finish() }
            }
        }
        main.removeCallbacks(tick)
        main.post(tick)
        return START_NOT_STICKY
    }

    /** Android 15+: the dataSync time budget ran out. Leave the foreground; the next add/resume/launch starts it again. */
    override fun onTimeout(startId: Int, fgsType: Int) = finish()

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacks(tick)
        locks(false)
        worker.shutdown()
        super.onDestroy()
    }

    private fun poll() {
        if (stopped) return
        worker.execute {
            val a = runCatching {
                val e = engineOf(this)
                e.start(); e.startPolling()
                e.activity()
            }.getOrNull()
            main.post { if (!stopped) apply(a) }
        }
    }

    private fun apply(a: TorrentActivity?) {
        if (a == null || !a.keepAlive) { finish(); return }
        locks(a.transferring > 0)
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTE_ID, build(a)) }
    }

    private fun finish() {
        if (stopped) return
        stopped = true
        locks(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @SuppressLint("WakelockTimeout")
    private fun locks(on: Boolean) {
        if (on) {
            if (wake == null) wake = runCatching {
                getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cloudstore:torrent")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
            if (wifi == null) wifi = runCatching {
                @Suppress("DEPRECATION")
                applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cloudstore:torrent").apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        } else {
            wake?.let { if (it.isHeld) it.release() }; wake = null
            wifi?.let { if (it.isHeld) it.release() }; wifi = null
        }
    }

    private fun build(a: TorrentActivity): Notification {
        val b = NotificationCompat.Builder(this, channel(this))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Torrents").setContentText(if (a.downloading > 0) a.text else "Starting")
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(this))
        if (a.progress >= 0f) b.setProgress(1000, (a.progress * 1000).toInt(), false) else b.setProgress(0, 0, true)
        b.addAction(0, "Pause all", action(ACTION_PAUSE_ALL))
        b.addAction(0, "Stop", action(ACTION_STOP))
        return b.build()
    }

    private fun action(a: String): PendingIntent =
        PendingIntent.getService(this, a.hashCode(), Intent(this, TorrentService::class.java).setAction(a), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    companion object {
        const val LEGACY_CHANNEL = "torrent_downloads"
        const val NOTE_ID = 0x7077
        const val TICK_MS = 2_000L
        const val ACTION_PAUSE_ALL = "com.diegonmarcos.cloudstore.torrent.PAUSE_ALL"
        const val ACTION_STOP = "com.diegonmarcos.cloudstore.torrent.STOP"
        @Volatile var running = false; private set
        /** The process-wide engine; tests swap in one over a fake session. */
        @Volatile var engineOf: (Context) -> TorrentEngine = { TorrentEngines.get(it) }

        /** Starts the service when the engine has a download; does nothing at idle or while it already runs. */
        fun sync(ctx: Context, engine: TorrentEngine = engineOf(ctx)) {
            if (running || !engine.activity().keepAlive) return
            start(ctx)
        }

        /** Unconditional start, for a just-added or just-resumed download (the service stops itself if it turns out idle). */
        fun start(ctx: Context) {
            if (running) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, TorrentService::class.java)) }
        }

        /** The channel id to post on: through libs:core SilentChannels like every Store channel (silent, low importance). */
        fun channel(ctx: Context): String = SilentChannels.ensure(ctx, LEGACY_CHANNEL, "Torrent downloads", NotificationManager.IMPORTANCE_LOW)

        private fun openApp(ctx: Context): PendingIntent? {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
            return PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}

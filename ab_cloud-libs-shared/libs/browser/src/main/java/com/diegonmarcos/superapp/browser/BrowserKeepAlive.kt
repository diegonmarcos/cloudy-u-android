package com.diegonmarcos.superapp.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * #1841 what keeps the browser alive in the background, and nothing more. What Android allows an app that is not in the foreground:
 *
 *  - Its process is cached, then killed by the low-memory killer, oldest and largest first; no flag prevents that. A WebView's renderer is
 *    a separate process the system kills before the app's own. So the page state is persisted ([BrowserSessionSnapshots]) and restored.
 *  - A battery-optimisation exemption (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, [exemptionIntent]) keeps Doze from deferring the app's
 *    network and jobs; it does not stop the low-memory killer. It is requested once, with an explanation, from the page the user opens
 *    (Configs > General > Perms), never forced.
 *  - A foreground service lifts the process out of the cached tier while it runs. It is used ONLY while media is playing
 *    ([BrowserKeepAliveService], type mediaPlayback) and, for transfers, by the download manager's own service (DlService). It stops the
 *    moment the audio stops; it is never held "just in case".
 *  - The renderer priority policy ([RENDERER_IMPORTANT]): the visible tab's renderer is IMPORTANT and not waived when the view is not
 *    visible, so the system keeps it while the app is merely backgrounded; only one tab is live at a time, the others live in their snapshots.
 */
object BrowserKeepAlive {
    /** What the policy decides for the app as it leaves the foreground. */
    data class Plan(val foregroundService: Boolean, val reason: String)

    /** Pure: a foreground service only for media; downloads are held by DlService; nothing else justifies one. */
    fun decide(mediaPlaying: Boolean, downloadsActive: Boolean, longTask: Boolean): Plan = when {
        mediaPlaying -> Plan(true, "media-playing")
        downloadsActive -> Plan(false, "downloads-held-by-DlService")
        longTask -> Plan(false, "long-task-uses-its-own-service")
        else -> Plan(false, "idle")
    }

    /** setRendererPriorityPolicy(IMPORTANT, waivedWhenNotVisible=false): the renderer keeps its priority while the app is backgrounded. */
    const val RENDERER_IMPORTANT = "important-not-waived"

    fun exempt(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false)

    /** The system's one-tap exemption dialog for this app (null on a phone that has no such screen). */
    fun exemptionIntent(ctx: Context): Intent? {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        return listOf(direct, list).firstOrNull { it.resolveActivity(ctx.packageManager) != null }
    }

    fun mediaPlaying(ctx: Context): Boolean =
        runCatching { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMusicActive }.getOrDefault(false)

    /** One line of the keep-alive state, logged on every transition (tag CBrowser/Keep). */
    fun log(ctx: Context, event: String, vararg extra: Pair<String, Any?>) {
        BrowserLog.i(BrowserLog.KEEP, event, "exempt" to exempt(ctx), "media" to mediaPlaying(ctx), "renderer_policy" to RENDERER_IMPORTANT, *extra)
    }

    /** The app is leaving the foreground: hold the media service if (and only if) audio is playing. */
    fun onBackground(ctx: Context, downloadsActive: Boolean) {
        val plan = decide(mediaPlaying(ctx), downloadsActive, longTask = false)
        log(ctx, "background", "plan_service" to plan.foregroundService, "reason" to plan.reason)
        if (plan.foregroundService) {
            val r = runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, BrowserKeepAliveService::class.java)) }
            BrowserLog.i(BrowserLog.KEEP, "service-start", "ok" to r.isSuccess, "error" to r.exceptionOrNull()?.javaClass?.simpleName)
        }
    }

    /** Back in the foreground: nothing needs the service any more. */
    fun onForeground(ctx: Context) {
        val stopped = runCatching { ctx.stopService(Intent(ctx, BrowserKeepAliveService::class.java)) }.getOrDefault(false)
        log(ctx, "foreground", "service_stopped" to stopped)
    }
}

/** #1841 the foreground service held only while a page is playing audio in the background; it stops itself when the audio stops. */
class BrowserKeepAliveService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!BrowserKeepAlive.mediaPlaying(this@BrowserKeepAliveService)) {
                BrowserLog.i(BrowserLog.KEEP, "service-stop", "why" to "audio-stopped")
                stopSelf()
            } else main.postDelayed(this, POLL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0, (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(applicationInfo.icon).setContentTitle("Playing in the background")
            .setContentText("The browser keeps the page's audio going. Tap to return.").setContentIntent(open).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(this, NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTE_ID, n)
        }.onFailure { BrowserLog.w(BrowserLog.KEEP, "service-foreground-refused", "error" to it.javaClass.simpleName); stopSelf() }
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(poll)
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Background audio", NotificationManager.IMPORTANCE_LOW))
    }

    companion object {
        private const val CHANNEL = "browser_keepalive"
        private const val NOTE_ID = 7411
        private const val POLL_MS = 20_000L
    }
}

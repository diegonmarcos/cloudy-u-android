package com.diegonmarcos.clocktools

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat


/**
 * The foreground service that keeps the Clock alive with the app closed: while a timer, the
 * stopwatch, an interval session or the sleep timer runs, or anything rings. It holds no state —
 * every [refresh] re-reads ClockEngine's store — so START_STICKY's restart after the process is
 * killed draws the same notification the dead process did. It plays the ring; it never decides
 * WHAT rings (ClockLogic.fire does) or WHEN (AlarmManager does).
 */
class ClockService : Service() {
    private var player: MediaPlayer? = null
    private var wake: PowerManager.WakeLock? = null
    private var playing: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
        ClockNotifications.channels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refresh()
        return START_STICKY
    }

    override fun onDestroy() {
        live = null
        quiet()
        super.onDestroy()
    }

    fun refresh() {
        val d = ClockEngine.load(this)
        val now = ClockEngine.now()
        // First, always: a service started with startForegroundService that does not call
        // startForeground within seconds takes the whole app down, even if it is about to stop.
        ServiceCompat.startForeground(this, ClockNotifications.ONGOING_ID, ClockNotifications.ongoing(this, d, now), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        ClockNotifications.timers(this, d, now)
        if (d.ringing.isEmpty()) quiet() else ring(d)
        if (!needed(d)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun ring(d: ClockData) {
        val top = d.ringing.last()
        if (playing == top.key) return
        quiet()
        playing = top.key
        val alarm = d.alarms.firstOrNull { ClockLogic.alarmKey(it.id) == top.key }
        // Bounded: a ring the user never answers is silenced by the SILENCE wakeup at ringMs.
        wake = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clocktools:ring")
            ?.apply { acquire(ClockTools.config(this@ClockService).ringMs + ClockLogic.MINUTE_MS) }
        val sound = alarm?.sound.orEmpty()
        if (sound != Alarm.SILENT) player = play(sound) ?: play("")
        if (alarm?.vibrate ?: ClockTools.config(this@ClockService).vibrateDefault) {
            vibrator(this)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0), ClockEngine.alarmAudio)
        }
    }

    /** [sound] "" is the system alarm sound (the user's choice in Settings); a URI that no longer plays falls back to it. */
    private fun play(sound: String): MediaPlayer? = runCatching {
        val uri: Uri = if (sound.isBlank()) {
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM) ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        } else Uri.parse(sound)
        MediaPlayer().apply {
            setAudioAttributes(ClockEngine.alarmAudio)
            setDataSource(this@ClockService, uri)
            isLooping = true
            prepare()
            start()
        }
    }.getOrNull()

    /** Stops the ring, and only the ring: an interval's short buzz is not this service's to cancel. */
    private fun quiet() {
        if (playing == null) return
        player?.let { p -> runCatching { p.stop() }; p.release() }
        player = null
        vibrator(this)?.cancel()
        wake?.takeIf { it.isHeld }?.release()
        wake = null
        playing = null
    }

    companion object {
        @Volatile private var live: ClockService? = null

        /** Whether anything needs the service: something rings, runs, or waits paused. */
        fun needed(d: ClockData): Boolean = d.ringing.isNotEmpty() ||
            d.timers.any { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED } ||
            d.stopwatch.running || d.interval != null || d.sleepUntil > 0

        fun running(): Boolean = live != null

        /** After every Clock write: refresh the live service, or start one if [d] needs it. */
        fun sync(ctx: Context, d: ClockData) {
            val s = live
            if (s != null) {
                Handler(Looper.getMainLooper()).post { s.refresh() }
                return
            }
            if (!needed(d)) return
            // From the background this is allowed when the call comes from an exact alarm or a
            // notification button; from anywhere else it may be refused, and the next wakeup or
            // the app's own screen starts it instead. The state is already saved either way.
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ClockService::class.java)) }
        }

        @Suppress("DEPRECATION")
        private fun vibrator(ctx: Context): Vibrator? = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        /** A short pattern on the alarm usage (an interval phase change). */
        fun buzz(ctx: Context, pattern: LongArray) {
            runCatching { vibrator(ctx)?.vibrate(VibrationEffect.createWaveform(pattern, -1), ClockEngine.alarmAudio) }
        }
    }
}

/** The Clock's notifications: channels, the service's own, one per timer, and the bedtime reminder. */
object ClockNotifications {
    const val ONGOING_ID = 768
    private const val REMINDER_ID = 769
    private const val TIMER_TAG = "clock_timer"
    const val CH_RING = "clock_ring"
    const val CH_RUNNING = "clock_running"
    const val CH_REMINDER = "clock_reminder"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_RING, ctx.getString(R.string.clock_channel_ring), NotificationManager.IMPORTANCE_HIGH).apply {
            // The service plays the sound and the vibration: a channel sound would double it.
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_RUNNING, ctx.getString(R.string.clock_channel_running), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, ctx.getString(R.string.clock_channel_reminder), NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun control(ctx: Context, op: String, key: String): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0,
        Intent(ctx, ClockReceiver::class.java).setAction(ClockEngine.ACTION_CONTROL)
            .setData(Uri.parse("cloudclockctl:$op/$key")).putExtra(ClockEngine.EXTRA_OP, op).putExtra(ClockEngine.EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ringScreen(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun base(ctx: Context, channel: String, modeKind: String): NotificationCompat.Builder =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_clock)
            .setContentIntent(ClockEngine.openIntent(ctx, ClockTools.host(ctx).modeId(modeKind)))
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    /** The service's notification: the ring (full-screen over the lock screen) or what is running. */
    fun ongoing(ctx: Context, d: ClockData, now: Long): Notification {
        if (d.ringing.isNotEmpty()) {
            val top = d.ringing.last()
            val b = base(ctx, CH_RING, if (top.key.startsWith("timer:")) "timers" else "alarms")
                .setContentTitle(top.label.ifBlank { ctx.getString(if (top.key.startsWith("alarm:")) R.string.clock_alarm else R.string.clock_times_up) })
                .setContentText(ctx.getString(R.string.clock_ringing))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setFullScreenIntent(ringScreen(ctx), true)
                .addAction(0, ctx.getString(R.string.clock_dismiss), control(ctx, "dismiss_all", ""))
            if (top.key.startsWith("alarm:")) b.addAction(0, ctx.getString(R.string.clock_snooze), control(ctx, "snooze", top.key))
            if (top.key.startsWith("timer:")) b.addAction(0, ctx.getString(R.string.clock_plus_minute), control(ctx, "timer_add", top.key))
            return b.build()
        }
        val sw = d.stopwatch
        val interval = d.interval?.let { i -> ClockLogic.phaseAt(i, now)?.let { i to it } }
        return when {
            interval != null -> {
                val (i, p) = interval
                base(ctx, CH_RUNNING, "interval")
                    .setContentTitle(ctx.getString(R.string.clock_interval_phase, phaseName(ctx, p.kind), p.round, i.rounds))
                    .setContentText(i.label)
                    .setUsesChronometer(true).setChronometerCountDown(true).setWhen(p.endsAt).setShowWhen(true)
                    .addAction(0, ctx.getString(R.string.clock_stop), control(ctx, "interval_stop", ""))
            }
            sw.running -> base(ctx, CH_RUNNING, "stopwatch")
                .setContentTitle(ctx.getString(R.string.clock_stopwatch))
                .setUsesChronometer(true).setWhen(now - ClockLogic.elapsed(sw, now)).setShowWhen(true)
                .addAction(0, ctx.getString(R.string.clock_lap), control(ctx, "stopwatch_lap", ""))
                .addAction(0, ctx.getString(R.string.clock_pause), control(ctx, "stopwatch_toggle", ""))
            d.sleepUntil > 0 -> base(ctx, CH_RUNNING, "bedtime")
                .setContentTitle(ctx.getString(R.string.clock_sleep_timer))
                .setUsesChronometer(true).setChronometerCountDown(true).setWhen(d.sleepUntil).setShowWhen(true)
                .addAction(0, ctx.getString(R.string.clock_cancel), control(ctx, "sleep_cancel", ""))
            else -> base(ctx, CH_RUNNING, "timers")
                .setContentTitle(ctx.resources.getQuantityString(R.plurals.clock_timers_running,
                    d.timers.count { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED },
                    d.timers.count { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED }))
        }.setOngoing(true).build()
    }

    /** One notification per running or paused timer, with its own controls; the rest are removed. */
    fun timers(ctx: Context, d: ClockData, now: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        val shown = d.timers.filter { it.state == TimerState.RUNNING || it.state == TimerState.PAUSED }
        val ids = shown.map { it.id.toInt() }.toSet()
        runCatching { nm.activeNotifications.filter { it.tag == TIMER_TAG && it.id !in ids }.forEach { nm.cancel(TIMER_TAG, it.id) } }
        shown.forEach { t ->
            val key = ClockLogic.timerKey(t.id)
            val running = t.state == TimerState.RUNNING
            val b = base(ctx, CH_RUNNING, "timers")
                .setContentTitle(t.label.ifBlank { ctx.getString(R.string.clock_timer) })
                .setOngoing(running)
                .addAction(0, ctx.getString(if (running) R.string.clock_pause else R.string.clock_resume), control(ctx, if (running) "timer_pause" else "timer_start", key))
                .addAction(0, ctx.getString(R.string.clock_plus_minute), control(ctx, "timer_add", key))
                .addAction(0, ctx.getString(R.string.clock_reset), control(ctx, "timer_reset", key))
            if (running) b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(t.endsAt).setShowWhen(true)
            else b.setContentText(ctx.getString(R.string.clock_paused_left, ClockLogic.countdown(ClockLogic.remaining(t, now))))
            runCatching { nm.notify(TIMER_TAG, t.id.toInt(), b.build()) }
        }
    }

    /** A quiet alarm (the bedtime reminder) fired. */
    fun reminder(ctx: Context, label: String) {
        channels(ctx)
        val n = base(ctx, CH_REMINDER, "bedtime")
            .setContentTitle(label.ifBlank { ctx.getString(R.string.clock_bedtime) })
            .setContentText(ctx.getString(R.string.clock_bedtime_text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        // Without POST_NOTIFICATIONS this is refused; the reminder is the notification, so there is nothing else to do.
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.notify(REMINDER_ID, n) }
    }

    fun phaseName(ctx: Context, k: ClockLogic.PhaseKind): String = ctx.getString(when (k) {
        ClockLogic.PhaseKind.WORK -> R.string.clock_phase_work
        ClockLogic.PhaseKind.REST -> R.string.clock_phase_rest
        ClockLogic.PhaseKind.LONG_REST -> R.string.clock_phase_long_rest
    })
}

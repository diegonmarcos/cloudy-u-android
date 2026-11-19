package com.diegonmarcos.superapp.system

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.configs.BrokerGrantChannel
import com.diegonmarcos.superapp.perms.DevVerifier
import com.diegonmarcos.superapp.perms.DevVerifierFlow
import java.util.concurrent.TimeUnit

/**
 * #1822 the notifications and the background re-check of the "Apps from unverified developers" flow. The decisions are
 * [DevVerifierFlow] (pure, JVM-tested); this only posts, schedules and re-checks. Alarms are inexact (no exact-alarm permission),
 * rebuilt from the remembered flow on boot, and the re-check runs every 6 hours and on boot through the shell channel.
 */
object DevVerifierNotifier {
    const val CHANNEL = "dev_verifier_flow"
    const val ACTION_ALARM = "com.diegonmarcos.superapp.system.DEV_VERIFIER_ALARM"
    const val EXTRA_KIND = "kind"
    /** The extra ShellActivity reads to open the Perms page with this row highlighted. */
    const val EXTRA_ROW = "perms_row"
    const val WORK = "dev-verifier-check"

    fun title(k: DevVerifierFlow.Kind) = when (k) {
        DevVerifierFlow.Kind.RESTART_PROMPT -> R.string.devver_restart_title
        DevVerifierFlow.Kind.WINDOW_END -> R.string.devver_window_title
        DevVerifierFlow.Kind.RESET -> R.string.devver_reset_title
    }
    fun body(k: DevVerifierFlow.Kind) = when (k) {
        DevVerifierFlow.Kind.RESTART_PROMPT -> R.string.devver_restart_body
        DevVerifierFlow.Kind.WINDOW_END -> R.string.devver_window_body
        DevVerifierFlow.Kind.RESET -> R.string.devver_reset_body
    }

    /** One measurement round, then every notification it owes and the alarms it needs. */
    fun run(ctx: Context, exec: (String) -> String?) {
        val r = DevVerifier.check(ctx, exec)
        for (k in DevVerifierFlow.immediate(r)) {
            post(ctx, k)
            if (k == DevVerifierFlow.Kind.RESTART_PROMPT) DevVerifier.markRestartPrompted(ctx)
        }
        schedule(ctx, r)
    }

    /** Re-arms the alarms from the remembered flow (after a reboot the alarm manager is empty). */
    fun reschedule(ctx: Context) = schedule(ctx, DevVerifier.flow(ctx))

    private fun schedule(ctx: Context, r: DevVerifierFlow.Result) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val due = DevVerifierFlow.alarms(r, System.currentTimeMillis()).associateBy { it.kind }
        for (k in DevVerifierFlow.Kind.values()) {
            val pi = alarmIntent(ctx, k)
            val a = due[k]
            if (a == null) am.cancel(pi) else runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pi) }
        }
    }

    private fun alarmIntent(ctx: Context, k: DevVerifierFlow.Kind): PendingIntent = PendingIntent.getBroadcast(ctx, 0x4456 + k.ordinal,
        Intent(ctx, DevVerifierReceiver::class.java).setAction(ACTION_ALARM).putExtra(EXTRA_KIND, k.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun post(ctx: Context, k: DevVerifierFlow.Kind) {
        runCatching {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.devver_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.putExtra(EXTRA_ROW, DevVerifier.ID)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val b = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(ctx.getString(title(k))).setContentText(ctx.getString(body(k)))
                .setStyle(NotificationCompat.BigTextStyle().bigText(ctx.getString(body(k))))
                .setAutoCancel(true)
            if (open != null) b.setContentIntent(PendingIntent.getActivity(ctx, 0x4456 + k.ordinal, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            NotificationManagerCompat.from(ctx).notify(0x4456 + k.ordinal, b.build())
        }
    }

    /** The periodic re-check; KEEP so an app start never resets its clock. */
    fun enqueuePeriodic(ctx: Context) {
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DevVerifierCheckWorker>(6, TimeUnit.HOURS).build())
        }
    }
}

/** The background re-check: through the shell channel when it is up; a down channel changes nothing. */
class DevVerifierCheckWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val ch = BrokerGrantChannel(applicationContext)
        if (!runCatching { ch.up() }.getOrDefault(false)) return Result.success()
        DevVerifierNotifier.run(applicationContext) { cmd -> runCatching { ch.exec(cmd) }.getOrNull() }
        return Result.success()
    }
}

/** The alarm (window over) and BOOT_COMPLETED (re-arm the alarm, re-check once). */
class DevVerifierReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DevVerifierNotifier.ACTION_ALARM ->
                DevVerifierFlow.Kind.values().firstOrNull { it.name == intent.getStringExtra(DevVerifierNotifier.EXTRA_KIND) }
                    ?.let { DevVerifierNotifier.post(context, it) }
            Intent.ACTION_BOOT_COMPLETED -> {
                DevVerifierNotifier.reschedule(context)
                DevVerifierNotifier.enqueuePeriodic(context)
                runCatching { WorkManager.getInstance(context).enqueue(androidx.work.OneTimeWorkRequestBuilder<DevVerifierCheckWorker>().build()) }
            }
        }
    }
}

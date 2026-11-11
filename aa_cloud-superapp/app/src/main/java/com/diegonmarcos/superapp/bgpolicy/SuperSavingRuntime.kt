package com.diegonmarcos.superapp.bgpolicy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.system.WirelessDebugKeeper

/**
 * #1506 Super Saving on the device: the declared steps over the real shell, the real sync switch and the real keeper, plus the
 * two event-driven pieces (a screen-off hook while the mode is on, one ongoing notification with a one-tap Off). Everything runs
 * on a tap; nothing is scheduled and nothing polls.
 */
object SuperSavingRuntime {
    const val ENGINE = "super_saving"
    private const val DECL = "super-saving.json"

    private fun decl(ctx: Context) = ctx.applicationContext.assets.open(DECL).bufferedReader().use { it.readText() }

    fun active(ctx: Context): Boolean = SuperSaving.isActive(BgPrefsKv(ctx.applicationContext))

    private fun host(app: Context) = object : SsHost {
        override fun masterSync() = ContentResolver.getMasterSyncAutomatically()
        override fun setMasterSync(on: Boolean) = ContentResolver.setMasterSyncAutomatically(on)
        override fun idleArmed() = BgPrefsKv(app).get(K_IDLE) == "1"
        override fun armIdle(on: Boolean) { BgPrefsKv(app).put(K_IDLE, if (on) "1" else "0"); if (on) SsScreenOff.register(app) else SsScreenOff.unregister(app) }
        override fun keeperEnabled() = WirelessDebugKeeper.Prefs.enabled(app)
        override fun setKeeper(on: Boolean) = WirelessDebugKeeper.Prefs.setEnabled(app, on)
        override fun packages() = BgHost.packages(app)
    }

    private fun build(app: Context, shell: BgShell?): SuperSaving {
        val cfg = BgHost.config(app)
        return SuperSaving(SsDecl.parse(decl(app)), shell, host(app), BgHost.engine(app, shell, cfg), cfg, BgPrefsKv(app)) { BgAudit.append(app, it) }
    }

    /** Take the snapshot and apply every step. Blocking: call off the main thread. [shell] null = the ADB steps are skipped with a note. */
    fun turnOn(ctx: Context, shell: BgShell?): List<SsResult> {
        val app = ctx.applicationContext
        if (SystemSaver.extreme(app)) { BgAudit.append(app, "super-saving not turned on: system extreme saver is on"); return emptyList() }
        val r = build(app, shell).on()
        if (active(app)) SsIndicator.post(app, SsDecl.indicator(decl(app)))
        return r
    }

    /** Restore the snapshot in reverse order. Blocking. The indicator goes only when nothing is left to restore. */
    fun turnOff(ctx: Context, shell: BgShell? = BgHost.shell(ctx)): List<SsResult> {
        val app = ctx.applicationContext
        val r = build(app, shell).off()
        if (!active(app)) SsIndicator.cancel(app)
        return r
    }

    /** App start: the screen-off hook and the indicator come back with the process when the mode is on (a reboot clears the notification). */
    fun onAppStart(app: Context) {
        if (!active(app)) return
        if (SystemSaver.extreme(app)) { Thread({ runCatching { turnOff(app) } }, "super-saving-yield").start(); return }
        if (BgPrefsKv(app).get(K_IDLE) == "1") SsScreenOff.register(app)
        runCatching { SsIndicator.post(app, SsDecl.indicator(decl(app))) }
    }

    fun summary(r: List<SsResult>): String {
        val a = r.count { it.state == SsState.APPLIED }; val s = r.count { it.state == SsState.SKIPPED }; val f = r.count { it.state == SsState.FAILED }
        return "$a steps done, $s skipped" + (if (f > 0) ", $f did not take" else "") + " (see the audit log)"
    }

    private const val K_IDLE = "ss_idle_armed"
}

/** The one ongoing notification: posted once on, cancelled on Off, never updated. Its action is a broadcast to [SuperSavingOffReceiver]. */
internal object SsIndicator {
    private const val CHANNEL = "super_saving"
    private const val ID = 0x5353

    fun post(app: Context, text: Triple<String, String, String>) {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Super Saving Battery Mode", NotificationManager.IMPORTANCE_LOW))
            val off = PendingIntent.getBroadcast(app, 0, Intent(app, SuperSavingOffReceiver::class.java).setAction(SuperSavingOffReceiver.ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(text.first).setContentText(text.second).setOngoing(true).setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS).addAction(0, text.third, off).build())
        }
    }

    fun cancel(app: Context) { runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } }
}

/** The notification's Off: restores the snapshot once and ends. */
class SuperSavingOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread({ try { SuperSavingRuntime.turnOff(app) } finally { pending.finish() } }, "super-saving-off").start()
    }
    companion object { const val ACTION = "com.diegonmarcos.superapp.SUPER_SAVING_OFF" }
}

/**
 * Doze is forced when the screen goes off and released when it comes on: a receiver registered only while the mode is on (the
 * screen broadcasts cannot be declared in the manifest). Event-driven, no polling; each event runs one shell command and ends.
 */
internal object SsScreenOff {
    private var receiver: BroadcastReceiver? = null

    @Synchronized fun register(app: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val off = i.action == Intent.ACTION_SCREEN_OFF
                val pending = goAsync()
                Thread({
                    try {
                        val sh = BgHost.shell(app)
                        if (sh == null) BgAudit.append(app, "super-saving doze ${if (off) "force-idle" else "unforce"} skipped: ${BgOutcome.NO_ADB}")
                        else { sh.exec(if (off) BgCmd.FORCE_IDLE else BgCmd.UNFORCE_IDLE); BgAudit.append(app, "super-saving doze ${if (off) "force-idle on screen off" else "unforced on screen on"}") }
                    } finally { pending.finish() }
                }, "super-saving-idle").start()
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    @Synchronized fun unregister(app: Context) {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
    }
}

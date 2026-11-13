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
import com.diegonmarcos.superapp.adbside.AdbSide
import com.diegonmarcos.superapp.settings.LauncherModes
import com.diegonmarcos.superapp.settings.LauncherSettingsPrefs
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.settings.ModeSwitch
import com.diegonmarcos.superapp.settings.PresetModes
import com.diegonmarcos.superapp.settings.PresetState
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1506 Super Saving on the device: the declared steps over the real shell, the real sync switch and the real keeper, plus the
 * two event-driven pieces (a screen-off hook while the mode is on, one ongoing notification with a one-tap Off). Everything runs
 * on a tap; nothing is scheduled and nothing polls.
 */
object SuperSavingRuntime {
    const val ENGINE = "super_saving"
    private const val DECL = "super-saving.json"

    private fun decl(ctx: Context) = ctx.applicationContext.assets.open(DECL).bufferedReader().use { it.readText() }

    /**
     * #1720 On = this is THE picked mode ([PresetState], the single state source of Presets). The restore journal is the
     * engine's own record of what to put back, not a second "on" flag: [PresetModes.migrate] repairs a store where the two
     * disagreed (a journal open while another mode was shown as picked).
     */
    fun active(ctx: Context): Boolean = PresetState.mode(ctx.applicationContext) == ENGINE

    private fun host(app: Context) = object : SsHost {
        override fun masterSync() = ContentResolver.getMasterSyncAutomatically()
        override fun setMasterSync(on: Boolean) = ContentResolver.setMasterSyncAutomatically(on)
        override fun idleArmed() = BgPrefsKv(app).get(K_IDLE) == "1"
        override fun armIdle(on: Boolean) { BgPrefsKv(app).put(K_IDLE, if (on) "1" else "0"); if (on) SsScreenOff.register(app) else SsScreenOff.unregister(app) }
        override fun keeperEnabled() = AdbSide.keeperEnabled(app)
        override fun setKeeper(on: Boolean) = AdbSide.setKeeper(app, on)
        override fun packages() = BgHost.packages(app)
        // #1720 the launcher switches of a mode, through the one switch engine (LauncherThemes.applyToggles)
        override fun toggle(id: String): Boolean? =
            LauncherSettingsPrefs.Config.toggles.firstOrNull { it.id == id }?.let { LauncherSettingsPrefs(app).toggle(it) }
        override fun setToggles(want: Map<String, Boolean>) = LauncherThemes.applyToggles(app, want)
        override fun manufacturer(): String = android.os.Build.MANUFACTURER.orEmpty()
        override fun sdk(): Int = android.os.Build.VERSION.SDK_INT
    }

    const val STEP_LAUNCHER = "launcher"

    /**
     * #1720 The ordered steps of a declared mode. An `engine` mode (Super Saving) reads its asset; every other mode leads with
     * its launcher switches (one journaled step) and continues with the `steps` it declares in build.json::ui.launcher_modes.
     */
    fun stepsOf(app: Context, mode: LauncherModes.Mode): List<SsStep> =
        if (mode.engine == ENGINE) SsDecl.parse(decl(app))
        else buildList {
            if (mode.toggles.isNotEmpty()) add(SsStep(STEP_LAUNCHER, "Launcher switches (${mode.toggles.size})", "launcher_toggles", false, false,
                JSONObject().put("toggles", JSONObject(mode.togglesJson.ifEmpty { "{}" }))))
            if (mode.steps.isNotEmpty()) addAll(SsDecl.parseArray(JSONArray(mode.steps)))
        }

    /** #1720 The engine of one declared mode over the journal at [journalKey]; null = the id is not declared (or its steps do not parse). */
    fun engine(ctx: Context, modeId: String, shell: BgShell?, journalKey: String = SuperSaving.K_SNAP): SuperSaving? = runCatching {
        val app = ctx.applicationContext
        val mode = LauncherModes.load().firstOrNull { it.id == modeId } ?: return null
        val cfg = BgHost.config(app)
        SuperSaving(stepsOf(app, mode), shell, host(app), BgHost.engine(app, shell, cfg), cfg, BgPrefsKv(app), { BgAudit.append(app, it) },
            modeId = modeId, label = mode.label, tag = if (mode.engine == ENGINE) "super-saving" else "mode-$modeId", journalKey = journalKey)
    }.getOrNull()

    /**
     * Take the snapshot and apply every step. Blocking: call off the main thread. [shell] null = the ADB steps are skipped with a note.
     * #1720 a pick like any other mode: the mode that was on is restored first ([PresetModes.select]).
     */
    fun turnOn(ctx: Context, shell: BgShell?): List<SsResult> {
        val app = ctx.applicationContext
        if (SystemSaver.extreme(app)) { BgAudit.append(app, "super-saving not turned on: system extreme saver is on"); return emptyList() }
        return PresetModes.select(app, ENGINE, shell).results
    }

    /** Restore the snapshot in reverse order. Blocking. The indicator goes only when nothing is left to restore. */
    fun turnOff(ctx: Context, shell: BgShell? = BgHost.shell(ctx)): List<SsResult> = turnOffOutcome(ctx, shell)?.restored.orEmpty()

    /** #1720 [turnOff] with the whole outcome (message and per-step reasons); null = this mode is not the one that is on. */
    fun turnOffOutcome(ctx: Context, shell: BgShell? = BgHost.shell(ctx)): ModeSwitch.Outcome? {
        val app = ctx.applicationContext
        PresetModes.migrate(app)
        if (!active(app)) { SsIndicator.cancel(app); return null }
        return PresetModes.off(app, shell)
    }

    /**
     * #1720 After every mode change: the Off notification is up exactly while this mode is the picked one. [note] replaces
     * its text (why an Off did not finish), so a tap on Off always answers in the place the owner tapped.
     */
    fun syncIndicator(ctx: Context, note: String = "") {
        val app = ctx.applicationContext
        if (active(app)) runCatching { SsIndicator.post(app, SsDecl.indicator(decl(app)), note) } else SsIndicator.cancel(app)
    }

    /** App start: the screen-off hook and the indicator come back with the process when the mode is on (a reboot clears the notification). */
    fun onAppStart(app: Context) {
        runCatching { PresetModes.migrate(app) } // #1720 upgrade repair of a double state, before anything reads the mode
        // the Presets badge is redrawn from the repaired state at every process start (a boot or an update starts the process
        // from a broadcast, where its short service may not be startable; the post itself always is)
        runCatching { com.diegonmarcos.superapp.notificationcenter.PresetsBadge.refresh(app) }
        if (!active(app)) return
        if (SystemSaver.extreme(app)) { Thread({ runCatching { turnOff(app) } }, "super-saving-yield").start(); return }
        if (BgPrefsKv(app).get(K_IDLE) == "1") SsScreenOff.register(app)
        syncIndicator(app)
    }

    fun summary(r: List<SsResult>): String {
        val a = r.count { it.state == SsState.APPLIED }; val s = r.count { it.state == SsState.SKIPPED }; val f = r.count { it.state == SsState.FAILED }
        return "$a steps done, $s skipped" + (if (f > 0) ", $f did not take" else "") + " (see the audit log)"
    }

    private const val K_IDLE = "ss_idle_armed"
}

/** The one ongoing notification: posted once on, cancelled on Off, never updated. Its action is a broadcast to [SuperSavingOffReceiver]. */
internal object SsIndicator {
    const val CHANNEL = "super_saving"
    private const val ID = 0x5353

    /**
     * #1720 Why the Off of this notification "did nothing": the action did reach [SuperSavingOffReceiver], but the restore it
     * ran could never empty the journal (a step with nothing captured failed on every try), so the notification stayed and
     * nothing told the owner. Now: the action is the same explicit, immutable broadcast; the receiver reports the outcome
     * here ([note]); and the body opens Quick actions > Presets (it had no content intent, so a tap on it was dead).
     */
    fun post(app: Context, text: Triple<String, String, String>, note: String = "") {
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Super Saving Battery Mode", NotificationManager.IMPORTANCE_LOW))
            val body = note.ifBlank { text.second }
            nm.notify(ID, NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(text.first).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(com.diegonmarcos.superapp.notificationcenter.BadgeClick.pi(app, PRESETS_PAGE))
                .setCategory(NotificationCompat.CATEGORY_STATUS).addAction(0, text.third, offIntent(app)).build())
        }
    }

    /** The Off action: an explicit broadcast to the manifest-declared [SuperSavingOffReceiver]. */
    fun offIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(app, 0,
        Intent(app, SuperSavingOffReceiver::class.java).setAction(SuperSavingOffReceiver.ACTION).setPackage(app.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    const val PRESETS_PAGE = "page:config/presets"
    const val NOTIFICATION_ID = ID

    fun cancel(app: Context) { runCatching { (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } }
}

/**
 * The notification's Off: restores the snapshot once and ends. #1720: it says what happened (a toast, and the notification
 * itself when steps are left), and it never holds the broadcast open: a long restore goes on in its own thread.
 */
class SuperSavingOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext
        // The broadcast is NOT held open (no goAsync): a restore runs many shell round trips and can outlast the broadcast
        // deadline, which ends in an ANR and a killed restore. The thread carries on by itself; the process is the Home app.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val text = runCatching { handle(app) }.getOrElse { "Off could not run: ${it.message ?: it.javaClass.simpleName}" }
            main.post { runCatching { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() } }
        }, "super-saving-off").start()
    }

    companion object {
        const val ACTION = "com.diegonmarcos.superapp.SUPER_SAVING_OFF"
        /** The whole Off, blocking: restore, then leave the notification saying why when steps are left. Returns the one-line verdict. */
        fun handle(app: Context, shell: BgShell? = BgHost.shell(app)): String {
            val o = runCatching { SuperSavingRuntime.turnOffOutcome(app, shell) }.getOrNull()
            val text = when {
                o == null && !SuperSavingRuntime.active(app) -> "Super Saving is not on."
                o == null -> "Off could not run. Open Quick actions > Presets."
                else -> o.message
            }
            SuperSavingRuntime.syncIndicator(app, if (SuperSavingRuntime.active(app)) "$text Tap to open Presets." else "")
            return text
        }
    }
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

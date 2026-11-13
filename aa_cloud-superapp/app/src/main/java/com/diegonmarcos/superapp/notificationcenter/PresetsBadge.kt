package com.diegonmarcos.superapp.notificationcenter

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ShellActivity
import com.diegonmarcos.superapp.bgpolicy.BgHost
import com.diegonmarcos.superapp.bgpolicy.BgShell
import com.diegonmarcos.superapp.bgpolicy.SuperSavingRuntime
import com.diegonmarcos.superapp.bgpolicy.SystemSaver
import com.diegonmarcos.superapp.core.FleetNotify
import com.diegonmarcos.superapp.settings.LauncherModes
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.settings.PresetModes
import com.diegonmarcos.superapp.settings.PresetState
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #1720 The persistent "Presets" badge of Notify > Actions: the current Mode, Sandbox and Theme, read from the single state
 * source ([PresetState]) and nothing else, with five quick actions: Presets (show: opens Quick actions > Presets), Mode off,
 * Next mode, Next sandbox, Next theme (change). A sandbox and a theme are never "off": each group always holds exactly one.
 *
 * A DATA WIDGET like Battery or AI: an ongoing notification posted with plain notify ([FleetNotify.dataWidgetBuilder] through
 * [NotifyGate.postCtx]), never a foreground service. It costs nothing while idle: no alarm, no timer, no receiver of its own.
 * It is redrawn only when the state changes ([PresetState.changed]), when the process starts ([BadgeServices.ensureAll]) and
 * when it is swiped away (its delete intent restarts this short job).
 */
class PresetsBadgeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext
        PresetsBadge.onWorker { try { PresetsBadge.refreshNow(app) } finally { stopSelf(startId) } }
        return START_NOT_STICKY
    }
}

object PresetsBadge {
    const val NOTIF_ID = 7720
    const val CHANNEL_ID = "presets_state"
    /** This badge's id in build.json::ui.notification_center.producers. */
    const val BADGE_ID = "presets_state"
    const val PAGE = "page:config/presets"

    const val ACT_MODE_OFF = "mode_off"
    const val ACT_MODE_NEXT = "mode_next"
    const val ACT_SANDBOX_NEXT = "sandbox_next"
    const val ACT_THEME_NEXT = "theme_next"

    private val worker by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "presets-badge").apply { isDaemon = true } } }
    /** Tests run the badge's work on the calling thread, so a post is not racing the assertions. */
    @Volatile internal var inline = false
    internal fun onWorker(block: () -> Unit) { if (inline) runCatching(block) else runCatching { worker.execute { runCatching(block) } } }

    /** What the badge says. Pure: the three picks and their declared labels. */
    data class Card(val title: String, val text: String)

    fun card(now: PresetState.Now, modeLabel: (String) -> String, sandboxLabel: (String) -> String, themeLabel: (String) -> String, pending: Int = 0): Card =
        Card(
            "Presets · Mode: " + (now.mode?.let(modeLabel) ?: "none") + if (pending > 0) " · restore pending" else "",
            "Sandbox: ${sandboxLabel(now.sandbox)} · Theme: ${themeLabel(now.theme)}",
        )

    fun card(ctx: Context): Card {
        val modes = LauncherModes.load(); val profiles = LauncherProfiles.loadFromBuildConfig(); val themes = LauncherThemes.loadFromBuildConfig()
        return card(PresetState.now(ctx),
            { id -> modes.firstOrNull { it.id == id }?.label ?: id },
            { id -> profiles.firstOrNull { it.id == id }?.label ?: id },
            { id -> themes.firstOrNull { it.id == id }?.label ?: id },
            runCatching { PresetModes.pending(ctx).size }.getOrDefault(0))
    }

    /** Redraw from the state, off the caller's thread (a pick on the UI thread must not wait for the shade). */
    fun refresh(ctx: Context) {
        val app = ctx.applicationContext
        onWorker { refreshNow(app) }
    }

    /** Blocking: post the card when the badge is switched on and may post; retire it when it is switched off. */
    fun refreshNow(ctx: Context) {
        val app = ctx.applicationContext
        track(app)
        if (!BadgeServices.wanted(app, PresetsBadgeService::class.java.name)) { DataWidgetRuntime.retiredIfOff(app, PresetsBadgeService::class.java); return }
        if (!BatteryBadgeService.canPost(app)) return
        ensureChannel(app)
        runCatching { NotifyGate.postCtx(app, NOTIF_ID, build(app)) }
    }

    internal fun build(ctx: Context): Notification {
        val pinned = BadgeServices.pinned(ctx, BADGE_ID)
        val c = card(ctx)
        val open = BadgeClick.pi(ctx, PAGE)
        // "Show action buttons" (the badge's own switch on the Notify page), read at post time
        val showActions = BadgeServices.declared.firstOrNull { it.id == BADGE_ID }?.let { BadgeCustomization.bool(ctx, it, "actions") } ?: true
        val b = FleetNotify.dataWidgetBuilder(ctx, R.drawable.ic_stat_notify, c.title, listOf(c.text), open,
                channelId = CHANNEL_ID, pinned = pinned, color = 0xFF0A0A0A.toInt(), subText = "Cloud SA - Presets")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(ctx, NOTIF_ID, PresetsBadgeService::class.java)) }
        if (showActions) {
            BadgeModel.add(b, actions(ctx, open))
            // the action-row layout of the Quick Actions and KDE badges: no media session, three buttons compact, five expanded
            b.setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        }
        return NotifyGroups.attach(ctx, b, BADGE_ID).build()
            .apply { if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    /** The five buttons: the notification's action row AND the Notify card's icon row (one declaration, [BadgeModel]). */
    fun actions(ctx: Context, open: PendingIntent): List<BadgeModel.Action> = listOf(
        BadgeModel.Action("presets", R.drawable.ic_act_open, "Presets", open),
        BadgeModel.Action("mode-off", R.drawable.ic_act_power, "Mode off", PresetsBadgeReceiver.pi(ctx, ACT_MODE_OFF)),
        BadgeModel.Action("next-mode", R.drawable.ic_act_refresh, "Next mode", PresetsBadgeReceiver.pi(ctx, ACT_MODE_NEXT)),
        BadgeModel.Action("next-sandbox", R.drawable.ic_act_shield, "Next sandbox", PresetsBadgeReceiver.pi(ctx, ACT_SANDBOX_NEXT)),
        BadgeModel.Action("next-theme", R.drawable.ic_act_settings, "Next theme", PresetsBadgeReceiver.pi(ctx, ACT_THEME_NEXT)),
    )

    /** The id after [cur] in [ids], wrapping; from an unknown or absent one, the first. */
    fun nextOf(ids: List<String>, cur: String?): String? {
        if (ids.isEmpty()) return null
        val i = if (cur == null) -1 else ids.indexOf(cur)
        return ids[(i + 1) % ids.size]
    }

    /**
     * One quick action, blocking (a mode change runs shell steps): call on a worker. Every change goes through the same single
     * state source as the Presets page, so the page, the tiles and this badge cannot disagree. Returns the line shown as a toast.
     */
    fun act(ctx: Context, what: String, shell: () -> BgShell? = { BgHost.shell(ctx) }): String {
        val app = ctx.applicationContext
        return when (what) {
            ACT_MODE_OFF -> PresetModes.off(app, shell()).message
            ACT_MODE_NEXT -> {
                // Super Saving yields to the system's own extreme saver, here as on the page
                val ids = LauncherModes.load().filter { !(it.engine == SuperSavingRuntime.ENGINE && SystemSaver.extreme(app)) }.map { it.id }
                val next = nextOf(ids, PresetState.mode(app)) ?: return "No mode is declared."
                val label = LauncherModes.load().firstOrNull { it.id == next }?.label ?: next
                "$label: " + PresetModes.select(app, next, shell()).message
            }
            ACT_SANDBOX_NEXT -> {
                val rows = LauncherProfiles.loadFromBuildConfig()
                val next = nextOf(rows.map { it.id }, PresetState.now(app).sandbox) ?: return "No sandbox is declared."
                PresetState.setSandbox(app, next)
                "Sandbox: " + (rows.firstOrNull { it.id == next }?.label ?: next)
            }
            ACT_THEME_NEXT -> {
                val rows = LauncherThemes.loadFromBuildConfig()
                val next = nextOf(rows.map { it.id }, PresetState.now(app).theme) ?: return "No theme is declared."
                PresetState.setTheme(app, next)
                // a theme names a style, and Android resolves a style once per Activity: the live shell is recreated, as the page does
                liveShell()?.let { a -> a.runOnUiThread { runCatching { (a as? ShellActivity)?.notifyLauncherThemeChanged() } } }
                "Theme: " + (rows.firstOrNull { it.id == next }?.label ?: next)
            }
            else -> "Unknown Presets action."
        }
    }

    // ── the live shell Activity, for the theme change (tracked through the application's lifecycle callbacks) ──
    @Volatile private var live: WeakReference<Activity>? = null
    private val tracking = AtomicBoolean(false)
    private fun liveShell(): Activity? = live?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun track(app: Context) {
        val a = app.applicationContext as? Application ?: return
        if (!tracking.compareAndSet(false, true)) return
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) { if (activity is ShellActivity) live = WeakReference(activity) }
            override fun onActivityStarted(activity: Activity) { if (activity is ShellActivity) live = WeakReference(activity) }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) { if (live?.get() === activity) live = null }
        })
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Presets", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Presets: the current mode, sandbox and theme, with quick actions."
                setShowBadge(false)
            },
        )
    }
}

/** The badge's quick actions. Each runs once on the badge worker and answers with a toast. */
class PresetsBadgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val what = intent.getStringExtra(EXTRA) ?: return
        val app = context.applicationContext
        // not held open (no goAsync): a mode change runs shell round trips and may outlast the broadcast deadline
        val main = Handler(Looper.getMainLooper())
        PresetsBadge.onWorker {
            val text = runCatching { PresetsBadge.act(app, what) }.getOrElse { "Presets: ${it.message ?: it.javaClass.simpleName}" }
            main.post { runCatching { android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show() } }
            PresetsBadge.refreshNow(app)
        }
    }

    companion object {
        const val EXTRA = "presets_action"
        /** A broadcast PendingIntent for one quick action (explicit component, stable per action). */
        fun pi(ctx: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            ctx, action.hashCode(),
            Intent(ctx, PresetsBadgeReceiver::class.java).setAction("com.diegonmarcos.superapp.PRESETS_$action").putExtra(EXTRA, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

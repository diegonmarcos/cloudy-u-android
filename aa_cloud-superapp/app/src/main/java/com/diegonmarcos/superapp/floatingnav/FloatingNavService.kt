package com.diegonmarcos.superapp.floatingnav
import com.diegonmarcos.superapp.system.ScreenLocker
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.MainActivity

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.OverlayCompose

/**
 * Floating Top Nav Bar — a SYSTEM_ALERT_WINDOW overlay shown whenever the
 * user has left Cloud-SuperApp. Collapsed it's a small transparent circle at
 * top-centre; tapped it expands into a context-aware top nav bar.
 *
 * Context is data-driven ([FloatingNavConfig]): a poll loop reads the
 * foreground package via [UsageStatsManager] every `poll_ms` and picks the
 * matching [NavContext] (Cloud-Comms → Fossy | Element | Mattermost |
 * FairMail; cloud-myterminal → Acode | Amaze File; otherwise the default
 * Cloud-Comms | cloud-myterminal). When the foreground IS Cloud-SuperApp the overlay
 * is hidden entirely — we're already home.
 *
 * Self-managing foreground service (START_STICKY) so Android keeps it alive
 * while the user roams other apps.
 *
 * #775 — the service and the overlay are two different things. The service
 * also hosts the Quick Actions / Media / Alerts badges (#535), so it is started
 * bare by BadgeServices.launch, by App.onCreate's ensureAll and by the
 * platform's sticky restart — none of which go through [startIfPermitted].
 * The user's Configs ▸ One-Hand switch used to be read only there, so every one
 * of those starts brought the button back over the user's OFF. Now
 * [overlayAllowed] is asked by every path that draws (the poll loop,
 * [showBubble], [showBar]) and a pref listener tears the windows down the
 * moment the switch flips; with it off the service runs headless and only
 * posts its badges.
 */
class FloatingNavService : Service() {

    private val cfg by lazy { FloatingNavConfig.get() }
    private val media by lazy { MediaProxy(this) }
    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bubble: OverlayCompose? = null      // collapsed circle
    private var bar: OverlayCompose? = null         // expanded nav bar
    private var expanded = false
    private var torchOn = false
    // When the menu was opened explicitly (Sirius Star on the home screen),
    // keep it shown even while Cloud-SuperApp is foreground; cleared on collapse.
    private var forced = false
    private var currentContextId: String? = null
    @Volatile private var lastForeground: String? = null
    // Battery: how many polls in a row saw the same foreground package (backs the poll off), and the screen gate.
    private var unchangedTicks = 0
    private var polledForeground: String? = null
    private var screenReceiver: BroadcastReceiver? = null
    // Persisted bubble position (px). Int.MIN_VALUE = unset → default top-centre.
    private var bubbleX = Int.MIN_VALUE
    private var bubbleY = Int.MIN_VALUE

    // Re-evaluate at once when the switch flips, rather than up to poll_ms
    // later: OFF must take the windows down immediately. Held in a field —
    // SharedPreferences keeps its listeners weakly.
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(pollTick); main.post(pollTick)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        FloatingNavPrefs.observe(this, prefListener)
        registerScreenGate()
        nudger = { main.post { unchangedTicks = 0; main.removeCallbacks(pollTick); main.post(pollTick) } }
        // Media follows the player's own session events, not the poll.
        main.post { runCatching { media.watch(); media.refresh() } }
        main.post(pollTick)
    }

    private fun interactive(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    /** Screen off: no poll at all (nobody sees the bubble). Screen on: one look at once, then the loop resumes. */
    private fun registerScreenGate() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                main.removeCallbacks(pollTick)
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    unchangedTicks = 0
                    main.post { runCatching { media.watch(); media.refresh() } }
                    main.post(pollTick)
                }
            }
        }
        screenReceiver = r
        val f = IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) }
        androidx.core.content.ContextCompat.registerReceiver(this, r, f, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_MENU -> {
                // Explicit open (Sirius Star). Force the bar even though SuperApp
                // is foreground; default context applies here.
                forced = true
                main.post { cfg.contextFor(packageName)?.let { showBar(it) } }
            }
            ACTION_NAV -> {
                // A notification action button (Light/Screensaver/Calc/…) was
                // tapped — run it, no menu.
                intent.getStringExtra(EXTRA_TARGET)?.let { dispatch(it, "") }
            }
            ACTION_RESET_POSITION -> main.post { resetPosition() }
            // #535: a BARE start is BadgeServices.launch (or the platform's sticky
            // restart) — put back every badge this service owns. Media dedupes
            // against what it last posted, so without this a badge that was
            // removed from the shade is never re-posted while the service runs.
            // (#777: Alerts left this service — AlertsNotifier draws it.)
            null -> main.post {
                runCatching { media.repost() }
                runCatching {
                    com.diegonmarcos.superapp.notificationcenter.NotifyGate.post(
                        getSystemService(NOTIFICATION_SERVICE) as NotificationManager, null, NOTIF_ID, buildNotification(), force = true)
                }
            }
            ACTION_RENOTIFY -> {
                // User swiped the (supposedly persistent) notification away on
                // Android 14+ — re-post it immediately.
                main.post {
                    runCatching {
                        com.diegonmarcos.superapp.notificationcenter.NotifyGate.post(
                            getSystemService(NOTIFICATION_SERVICE) as NotificationManager, null, NOTIF_ID, buildNotification(), force = true)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        armed = false
        nudger = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        FloatingNavPrefs.unobserve(this, prefListener)
        main.removeCallbacksAndMessages(null)
        removeBubble(); removeBar()
        runCatching { media.cancel() }
        com.diegonmarcos.superapp.notificationcenter.NotifyGroups.release(this, QUICK_ACTIONS_BADGE_ID)
        super.onDestroy()
    }

    // ── Poll loop ──────────────────────────────────────────────────
    private val pollIo by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "floatnav-poll").apply { isDaemon = true } } }

    private val pollTick = object : Runnable {
        override fun run() {
            armed = overlayAllowed(this@FloatingNavService)
            if (!armed) {
                removeBubble(); removeBar(); expanded = false; forced = false
                reschedule()
            } else {
                // UsageStats.queryEvents is a binder call: read it off the main thread, apply the result on it.
                runCatching { pollIo.execute {
                    val fg = foregroundPackage()
                    main.post { if (isRunning) { refresh(fg); reschedule() } }
                } }.onFailure { reschedule() }
            }
        }

        private fun reschedule() {
            // Media is NOT refreshed here: MediaProxy.watch() drives it from the session events.
            // Screen off: no next tick (the screen-on receiver resumes the loop).
            if (!interactive()) return
            val fg = lastForeground
            if (fg == polledForeground) unchangedTicks++ else { polledForeground = fg; unchangedTicks = 0 }
            main.postDelayed(this, pollDelayMs(cfg.pollMs, unchangedTicks))
        }
    }

    /** Last package moved to foreground in the recent window (UsageStats). */
    private fun foregroundPackage(): String? = runCatching {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - EVENT_WINDOW_MS, now) // wider than the longest back-off, so a quick switch is still seen
        val e = UsageEvents.Event()
        var pkg: String? = lastForeground
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) pkg = e.packageName
        }
        lastForeground = pkg
        pkg
    }.getOrNull()

    /** Show / hide / re-skin the overlay for the current foreground app. */
    private fun refresh(fg: String?) {
        // We're in Cloud-SuperApp → NO bubble (the in-app trigger is the Sirius
        // Star). hostForeground is the reliable signal from MainActivity's
        // lifecycle; we also treat an UNKNOWN foreground (fg == null, e.g. fresh
        // install before UsageStats populates / without usage access) as "still
        // home" so the circle never wrongly appears over Cloud-SuperApp.
        if (hostForeground || fg == packageName || fg == null) {
            if (forced) return
            removeBubble(); removeBar(); expanded = false; currentContextId = null; return
        }
        val ctx = cfg.contextFor(fg) ?: run { removeBubble(); removeBar(); return }
        if (ctx.id != currentContextId) {
            currentContextId = ctx.id
            if (expanded) showBar(ctx) // re-skin live if already open
        }
        if (!expanded && bubble == null) showBubble()
    }

    // ── Collapsed circle ───────────────────────────────────────────
    private fun showBubble() {
        if (bubble != null || !overlayAllowed(this)) return
        val size = dp(34)
        val params = bubbleParams(size)
        val v = OverlayCompose(this) { NavBubble() }
        v.view.setOnTouchListener(bubbleTouch(params))
        if (runCatching { wm.addView(v.view, params) }.isFailure) v.destroy()
        bubble = v
        bubbleDrawn = true
    }

    /**
     * Tap-and-hold to move, quick-tap to open. A long-press arms drag mode
     * (with haptic feedback); only then does a finger move reposition the
     * window. A plain tap (no hold, no move) expands the nav bar. The final
     * position is persisted so it survives re-shows / restarts.
     */
    private fun bubbleTouch(lp: WindowManager.LayoutParams): View.OnTouchListener {
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        return object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f
            private var startX = 0; private var startY = 0
            private var dragging = false

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                        dragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        // Free-drag: as soon as the finger travels past touch-slop
                        // it's a move (no long-press wait). First haptic = nice cue.
                        if (!dragging && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) {
                            dragging = true
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (dragging) {
                            lp.x = (startX + dx).toInt().coerceIn(0, maxBubbleX(view.width))
                            lp.y = (startY + dy).toInt().coerceIn(0, maxBubbleY(view.height))
                            runCatching { wm.updateViewLayout(view, lp) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            bubbleX = lp.x; bubbleY = lp.y; savePos()
                        } else if (e.actionMasked == MotionEvent.ACTION_UP) {
                            // No drag → a tap → open the menu.
                            view.performClick()
                            cfg.contextFor(lastForeground)?.let { showBar(it) }
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    private fun maxBubbleX(w: Int) = (resources.displayMetrics.widthPixels - w).coerceAtLeast(0)
    private fun maxBubbleY(h: Int) = (resources.displayMetrics.heightPixels - h).coerceAtLeast(0)

    private fun removeBubble() {
        bubble?.let { runCatching { wm.removeView(it.view) }; it.destroy() }; bubble = null; bubbleDrawn = false
    }

    /** Window params for the draggable bubble — gravity TOP|START so x/y are
     *  absolute; defaults to top-centre on first show, then the saved spot. */
    private fun bubbleParams(size: Int): WindowManager.LayoutParams {
        if (bubbleX == Int.MIN_VALUE) loadPos()
        val defX = ((resources.displayMetrics.widthPixels - size) / 2).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (bubbleX >= 0) bubbleX else defX
            y = if (bubbleY >= 0) bubbleY else dp(6)
        }
    }

    private fun posPrefs() =
        getSharedPreferences(FloatingNavPrefs.POS_FILE, Context.MODE_PRIVATE)
    private fun loadPos() {
        bubbleX = posPrefs().getInt(FloatingNavPrefs.KEY_POS_X, -1)
        bubbleY = posPrefs().getInt(FloatingNavPrefs.KEY_POS_Y, -1)
    }
    private fun savePos() {
        posPrefs().edit()
            .putInt(FloatingNavPrefs.KEY_POS_X, bubbleX)
            .putInt(FloatingNavPrefs.KEY_POS_Y, bubbleY)
            .apply()
    }

    /**
     * Back to the top-centre of whatever display we are on now. Dropping the
     * stored pair is enough — [bubbleParams] then falls through to its defX
     * (half of the current width minus half the circle) and dp(6) from the top,
     * so this is right after a rotation or on a tablet, which a hardcoded pixel
     * pair could never be. The re-add is what makes it visible immediately
     * instead of at the next show.
     */
    private fun resetPosition() {
        FloatingNavPrefs.clearPosition(this)
        bubbleX = -1; bubbleY = -1
        if (bubble != null) { removeBubble(); showBubble() }
    }

    // ── The menu box (compact 2-line, or Expanded view) ────────────
    private fun showBar(ctx: NavContext) {
        removeBubble(); removeBar()
        // ACTION_SHOW_MENU included: there is no "explicit one-shot" exception,
        // a switched-off button draws nothing from any door.
        if (!overlayAllowed(this)) { expanded = false; forced = false; return }
        expanded = true
        // Line 1 - the constellation apex (Cloud SuperApp), BIGGEST; line 2 - the sibling hubs, medium; line 3 - the current
        // context's children, SMALLEST (omitted when it has none, e.g. the forkless Cloud-Nav). The apex / hub matching the
        // current foreground app is bold.
        val col = OverlayCompose(this) {
            NavBar(
                root = cfg.root, parents = cfg.parents, children = ctx.children,
                boldOf = { parentMatchesContext(it.target, ctx) },
                onPick = { item -> handleTarget(item); main.post { collapse() } },
            )
        }
        if (runCatching { wm.addView(col.view, barParams(outsideTouch = true)) }.isFailure) col.destroy()
        col.view.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_OUTSIDE) { collapse(); true } else false
        }
        bar = col
        barDrawn = true
    }

    /** Data-driven hub→context match (no hardcoded package list): a `self`
     *  target is current in the default context (empty match_prefixes); an
     *  `app:<pkg>` target is current when the active context's match_prefixes
     *  cover that package. Add a hub + its context in build.json → bolds here. */
    private fun parentMatchesContext(target: String, ctx: NavContext): Boolean = when {
        target == "self" -> ctx.matchPrefixes.isEmpty()
        target.startsWith("app:") -> {
            val pkg = target.removePrefix("app:")
            ctx.matchPrefixes.any { pkg == it || pkg.startsWith("$it.") || it.startsWith("$pkg.") }
        }
        else -> false
    }

    private fun removeBar() { bar?.let { runCatching { wm.removeView(it.view) }; it.destroy() }; bar = null; barDrawn = false }

    private fun collapse() {
        expanded = false
        forced = false
        removeBar()
        if (lastForeground != packageName) showBubble()
    }

    // ── Item dispatch ──────────────────────────────────────────────
    /** Route a tapped nav item (overlay parents/children). */
    private fun handleTarget(item: NavItem) = dispatch(item.target, item.installApp)

    /** Shared target dispatch — used by the overlay nav AND the notification
     *  action buttons (Light/Screensaver/Calc/Lock/Search). */
    private fun dispatch(target: String, installApp: String) {
        when (target) {
            "self" -> launchPackage(packageName)
            "torch" -> toggleTorch()
            "calc" -> openCalculator()
            "lock" -> runCatching { com.diegonmarcos.superapp.system.ScreenLocker.lock(this) }
            "dnd" -> toggleDnd()
            "powersave" -> openPowerSaver()
            "screensaver" -> ScreensaverService.start(this)
            else -> when {
                // Media transport (Prev/Play-Pause/Next) → active session.
                target.startsWith("media:") -> media.transport(target)
                target.startsWith("app:") -> openApp(target.removePrefix("app:"), installApp)
                // section:/action:/page:/… → bring Cloud-SuperApp forward and
                // let its shortcut_action handler (onTileClicked) navigate.
                else -> openSuperApp(target)
            }
        }
    }

    /** Toggle the flashlight — CameraManager.setTorchMode needs no permission. */
    private fun toggleTorch() {
        runCatching {
            val cm = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        }
    }

    /** Toggle Do-Not-Disturb. Needs notification-policy access; if not granted,
     *  jump to the grant screen instead. */
    private fun toggleDnd() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        val dndOn = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        nm.setInterruptionFilter(
            if (dndOn) NotificationManager.INTERRUPTION_FILTER_ALL
            else NotificationManager.INTERRUPTION_FILTER_PRIORITY,
        )
    }

    /** Battery saver can't be toggled programmatically (system-restricted) —
     *  open the battery-saver settings so the user flips it. */
    private fun openPowerSaver() {
        runCatching {
            startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Open Cloud Calc; absent, Cloud Store on its Cloud tab, else SuperApp's own Store (never the system calculator). */
    private fun openCalculator() {
        val ok = when (com.diegonmarcos.superapp.apps.CalcHandoff.target(this)) {
            com.diegonmarcos.superapp.apps.CalcHandoff.Target.CLOUD_CALC -> com.diegonmarcos.superapp.apps.CalcHandoff.launch(this)
            com.diegonmarcos.superapp.apps.CalcHandoff.Target.CLOUD_STORE ->
                com.diegonmarcos.superapp.apps.CloudStoreHandoff.open(this, com.diegonmarcos.superapp.apps.CalcHandoff.STORE_TAB)
            com.diegonmarcos.superapp.apps.CalcHandoff.Target.SUPERAPP_STORE -> false
        }
        if (!ok) openApp(com.diegonmarcos.superapp.apps.CalcHandoff.PKG, "cloud-calc")
    }

    private fun openApp(pkg: String, installApp: String) {
        if (launchPackage(pkg)) return
        if (installApp.isNotBlank()) {
            val app = Sections.externalApp(installApp)
            if (app != null && app.installApkUrl.isNotBlank() && app.installPackage.isNotBlank()) {
                runCatching {
                    com.diegonmarcos.superapp.extinstall.ExtAppInstalls.start(applicationContext, app.installPackage, app.label, app.installApkUrl)
                }
                return
            }
        }
        launchPackage(packageName) // fall back to SuperApp so it can be installed there
    }

    /** Bring Cloud-SuperApp forward carrying a shortcut_action so it navigates
     *  to the section/action/page (works cold or warm — see MainActivity). */
    private fun openSuperApp(shortcutAction: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        intent.putExtra("shortcut_action", shortcutAction)
        runCatching { startActivity(intent) }
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    private fun barParams(outsideTouch: Boolean = false): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (outsideTouch) flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val w = resources.displayMetrics.widthPixels * cfg.widthPct / 100
        return WindowManager.LayoutParams(w, WindowManager.LayoutParams.WRAP_CONTENT, type, flags,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // Just below the dynamic island (data-driven dp from the top edge).
            y = dp(cfg.topOffsetDp)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ── Foreground-service notification (MediaStyle) ───────────────
    // Collapsed = the first `compact_action_count` (3) action buttons; expanded
    // = the album-art card (mock) + ALL action buttons (up to 5). Persistent
    // (FLAG_NO_CLEAR). Buttons + mock art are data-driven (ui.floating_nav).
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Floating nav bar", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Cloud SuperApp floating nav + quick actions."; setShowBadge(false) })
            }
        }
        return quickActionsNotification(this, cfg)
    }

    companion object {
        /**
         * The Quick Actions notification, built from a Context alone so the Notify card and its parity test see the very
         * notification the service posts. System MediaStyle template (a custom RemoteViews falls back to a broken default
         * on some OEMs): collapsed shows the first compact_action_count, expanded all 5. Buttons are data-driven
         * (ui.floating_nav), declared once through [com.diegonmarcos.superapp.notificationcenter.BadgeModel].
         */
        internal fun quickActionsNotification(ctx: Context, cfg: FloatingNavConfig): Notification {
            val pinned = com.diegonmarcos.superapp.notificationcenter.BadgeServices.pinned(ctx, QUICK_ACTIONS_BADGE_ID)
            val open = PendingIntent.getActivity(
                ctx, 0,
                (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(0xFF0A0A0A.toInt())
                .setContentTitle("Cloud SA - Quick Actions")
                .setContentText("Quick actions")
                .setOngoing(pinned).setOnlyAlertOnce(true)
                .setContentIntent(open)
                // Android 14+ lets the user swipe-dismiss even an ongoing FGS notification; re-post it immediately
                // when that happens so it stays effectively un-removable.
                .apply { if (pinned) setDeleteIntent(serviceAction(ctx, ACTION_RENOTIFY)) }
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                // #777 group G2 (Quick Actions · KDE Connect).
                .let { com.diegonmarcos.superapp.notificationcenter.NotifyGroups.attach(ctx, it, QUICK_ACTIONS_BADGE_ID) }
            val acts = quickActions(ctx, cfg)
            com.diegonmarcos.superapp.notificationcenter.BadgeModel.add(b, acts)
            val compact = IntArray(minOf(cfg.compactActionCount, acts.size)) { it }
            b.setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(*compact))
            return b.build().apply {
                // Persistent: blocks swipe-to-dismiss + "clear all".
                if (pinned) flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
        }

        /** The (up to five) buttons: the notification's action row and the Notify card's icon row. */
        internal fun quickActions(ctx: Context, cfg: FloatingNavConfig): List<com.diegonmarcos.superapp.notificationcenter.BadgeModel.Action> =
            cfg.actions.take(5).map {
                com.diegonmarcos.superapp.notificationcenter.BadgeModel.Action(it.target, actionIcon(it.target), it.label, actionPi(ctx, it.target))
            }

        /** PendingIntent for a notification action - routed through the invisible NavActionActivity so tapping it ALSO
         *  closes the notification shade (a service PendingIntent would leave the shade open). */
        private fun actionPi(ctx: Context, target: String): PendingIntent = PendingIntent.getActivity(
            ctx, target.hashCode(),
            Intent(ctx, NavActionActivity::class.java)
                .putExtra(NavActionActivity.EXTRA_TARGET, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** PendingIntent for a bare service action (e.g. re-notify on dismiss). */
        private fun serviceAction(ctx: Context, action: String): PendingIntent = PendingIntent.getService(
            ctx, action.hashCode(),
            Intent(ctx, FloatingNavService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Refined thin-line action icons (One-UI style), tinted by the system. */
        private fun actionIcon(target: String): Int = when (target) {
            "torch" -> R.drawable.ic_nav_torch
            "screensaver" -> R.drawable.ic_nav_screensaver
            "calc" -> R.drawable.ic_nav_calc
            "dnd" -> R.drawable.ic_nav_dnd
            "powersave" -> R.drawable.ic_nav_powersave
            "lock" -> R.drawable.ic_nav_lock
            else -> R.drawable.ic_nav_search
        }

        private const val CHANNEL_ID = "floating_nav"
        private const val NOTIF_ID = 0xF1
        /** The Quick Actions badge's id in build.json::ui.notification_center.producers. */
        private const val QUICK_ACTIONS_BADGE_ID = "floating_nav_quick_actions"
        const val ACTION_SHOW_MENU = "com.diegonmarcos.superapp.floatingnav.SHOW_MENU"
        internal const val ACTION_NAV = "com.diegonmarcos.superapp.floatingnav.NAV_ACTION"
        private const val ACTION_RENOTIFY = "com.diegonmarcos.superapp.floatingnav.RENOTIFY"
        private const val ACTION_RESET_POSITION =
            "com.diegonmarcos.superapp.floatingnav.RESET_POSITION"
        internal const val EXTRA_TARGET = "target"

        /** Force-open the nav menu (the Sirius Star on the home screen). Starts
         *  the service if needed and force-expands the bar even while SuperApp
         *  is foreground. Returns false if "display over other apps" isn't
         *  granted (caller should prompt). */
        fun showMenu(ctx: Context): Boolean {
            if (!overlayAllowed(ctx)) return false
            val i = Intent(ctx, FloatingNavService::class.java).setAction(ACTION_SHOW_MENU)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            return true
        }

        /** True while the overlay service is alive — drives the single
         *  Start/Stop toggle in Configs → Permissions. */
        @Volatile
        var isRunning: Boolean = false
            private set

        /** #775 — the ONE rule for whether this service may draw anything:
         *  the user's switch, the build's kill-switch, and the grant. Asked on
         *  every draw, so no way of starting the service can override it. */
        fun overlayAllowed(ctx: Context): Boolean =
            FloatingNavPrefs.enabled(ctx) && buildEnabled && Settings.canDrawOverlays(ctx)

        // A build constant; get() re-parses the baked JSON, and this is asked every poll.
        private val buildEnabled by lazy { FloatingNavConfig.get().enabled }

        /** The poll loop's last [overlayAllowed] verdict — "the button is on",
         *  as opposed to [isRunning], which a headless badge host also is. */
        @Volatile
        var armed: Boolean = false
            private set

        /** The overlay windows actually added to the WindowManager right now
         *  (read by /api/overlays). */
        @Volatile
        var bubbleDrawn: Boolean = false
            private set
        @Volatile
        var barDrawn: Boolean = false
            private set

        /** The switch in Configs (One-Hand, Permissions, DeviceControls) — one
         *  implementation for all three. ON writes the pref and starts the
         *  service. OFF writes the pref, which the running service hears and
         *  drops its windows; the service itself is stopped only when no badge
         *  it hosts still wants it, so Media / Alerts / Quick Actions stay in
         *  the shade with the button off. Returns whether the button is on. */
        fun setEnabled(ctx: Context, on: Boolean): Boolean {
            FloatingNavPrefs.setEnabled(ctx, on)
            if (on) return startIfPermitted(ctx)
            if (!com.diegonmarcos.superapp.notificationcenter.BadgeServices
                    .wanted(ctx, FloatingNavService::class.java.name)) stop(ctx)
            return false
        }

        /** How many [com.diegonmarcos.superapp.ShellActivity] instances are
         *  resumed right now. This is a COUNTER and not a boolean on purpose:
         *  once the launcher (HomeActivity) and the app (MainActivity) became
         *  two activities that can both be alive — the home screen behind, an
         *  app instance in a split half — a flag was wrong, because Android
         *  resumes the incoming activity BEFORE pausing the outgoing one. The
         *  `true` from the new instance was immediately overwritten by the
         *  `false` from the old one, leaving this false while the host was
         *  still on screen, and the floating circle drew itself over us. */
        private val hostVisible = java.util.concurrent.atomic.AtomicInteger(0)

        /** Polls with the same foreground package before the loop backs off to [IDLE_POLL_MS]. */
        const val IDLE_AFTER_TICKS = 3
        const val IDLE_POLL_MS = 15_000L
        private const val EVENT_WINDOW_MS = 35_000L

        /** The next poll delay: the declared `poll_ms`, backed off to [IDLE_POLL_MS] once the foreground has not changed. */
        fun pollDelayMs(pollMs: Long, unchangedTicks: Int): Long =
            if (unchangedTicks >= IDLE_AFTER_TICKS) maxOf(pollMs, IDLE_POLL_MS) else pollMs

        /** Set while the service lives: re-polls at once when the host app comes or goes (a host edge, not a timer). */
        @Volatile private var nudger: (() -> Unit)? = null

        /** True while any Cloud-SuperApp shell is in the foreground. The
         *  reliable signal for "we're home" — the floating circle must never
         *  show here (the in-app trigger is the Sirius Star). Maintained by
         *  [hostEnter] / [hostExit] from ShellActivity.onResume / onPause. */
        val hostForeground: Boolean get() = hostVisible.get() > 0

        /** ShellActivity.onResume. */
        fun hostEnter() { hostVisible.incrementAndGet(); nudger?.invoke() }

        /** ShellActivity.onPause. Clamped at zero so a stray unbalanced pause
         *  (process death mid-transition) cannot wedge the counter negative and
         *  suppress the overlay forever. */
        fun hostExit() { if (hostVisible.decrementAndGet() < 0) hostVisible.set(0); nudger?.invoke() }

        /** Start the overlay iff the user has it ON, the build allows it, and
         *  "display over other apps" is granted. Returns whether it started
         *  (false = switched off, feature disabled, or permission missing).
         *
         *  The [FloatingNavPrefs] check is first and is what makes the switch
         *  in Configs → One-Hand stick: MainActivity calls this on every open,
         *  so without it the overlay simply came back the next time the app was
         *  launched. */
        fun startIfPermitted(ctx: Context): Boolean {
            if (!overlayAllowed(ctx)) return false
            val i = Intent(ctx, FloatingNavService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
            return true
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, FloatingNavService::class.java))
        }

        /** Put the floating circle back at the top-centre of the screen.
         *
         *  The stored position is cleared here rather than in the service so it
         *  also takes effect when nothing is running — the circle then comes up
         *  centred the next time it is switched on. Only a live service gets the
         *  intent, because starting one just to move a view that does not exist
         *  would turn a reset into an enable. */
        fun resetPosition(ctx: Context) {
            FloatingNavPrefs.clearPosition(ctx)
            if (!isRunning) return
            val i = Intent(ctx, FloatingNavService::class.java)
                .setAction(ACTION_RESET_POSITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        /** Run a notification-action target on the service (called by the
         *  NavActionActivity trampoline after it closes the shade). */
        fun runAction(ctx: Context, target: String) {
            val i = Intent(ctx, FloatingNavService::class.java)
                .setAction(ACTION_NAV).putExtra(EXTRA_TARGET, target)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}

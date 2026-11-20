package com.diegonmarcos.superapp.onehand

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.HapticFeedbackConstants
import kotlin.math.hypot

/**
 * Hosts the edge handles (WindowManager overlay) and the live gesture-preview
 * arrow. A plain foreground service: it needs ONLY "Display over other apps"
 * (TYPE_APPLICATION_OVERLAY) — no Accessibility grant. One Hand Operation+
 * mechanic: swipe INWARD, the tilt of the drag (diagonal) picks the direction;
 * an arrow follows the finger and shows the action about to fire; release
 * triggers it. Every action is an app / in-host launch; nothing here performs
 * a system global action.
 *
 * #775 — OFF STAYS OFF: the service refuses to draw (and stops itself) unless
 * [OneHandPrefs.isEnabled] and the overlay permission both hold, checked at
 * every start and again inside [showHandles].
 */
class OneHandOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private val views = mutableListOf<View>()
    private var preview: GesturePreviewView? = null
    private var cfg: OneHandConfig? = null
    private val handler = Handler(Looper.getMainLooper())
    private var testOff: Runnable? = null

    private var downX = 0f
    private var downY = 0f
    private var activated = false
    private var machine: EdgeGestureMachine? = null
    private var holdCue: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        // NO foreground service, NO notification: overlay windows need only
        // "Display over other apps". Wipe what pre-fix builds left behind.
        OneHandController.retireLegacyNotification(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) {
            val ms = intent.getLongExtra(EXTRA_MS, 30_000L)
            OneHandPrefs.setEnabled(this, true)
            testOff?.let { handler.removeCallbacks(it) }
            testOff = Runnable {
                OneHandPrefs.setEnabled(this, false)
                Log.i(TAG, "testActivate: auto-disabled after ${ms}ms")
                stopSelf()
            }.also { handler.postDelayed(it, ms) }
            Log.i(TAG, "testActivate: on for ${ms}ms")
        }
        // #775: off stays off, and no overlay permission means nothing to host.
        if (!OneHandPrefs.isEnabled(this) || !Settings.canDrawOverlays(this)) {
            EdgeEventLog.record("service", reason = if (!OneHandPrefs.isEnabled(this)) "stopped: switch off" else "stopped: no overlay permission")
            stopSelf()
            return START_NOT_STICKY
        }
        showHandles()
        return START_STICKY
    }

    override fun onDestroy() {
        testOff?.let { handler.removeCallbacks(it) }
        endGesture(); hideHandles(); if (instance === this) instance = null; super.onDestroy()
    }

    /** Edge-handle windows currently added (read by the app's /api/overlays). */
    val handleCount: Int get() = views.size

    /** The idle strip windows (emulator test + /api/overlays read their screen bounds). */
    val handleViews: List<View> get() = views.toList()

    /** Where each strip really is on screen right now (Diagnostics + remote read): attached flag and absolute rect. */
    data class StripRect(val id: String, val attached: Boolean, val left: Int, val top: Int, val width: Int, val height: Int)

    fun stripRects(): List<StripRect> = views.mapIndexed { i, v ->
        val loc = IntArray(2)
        if (v.isAttachedToWindow) v.getLocationOnScreen(loc)
        val lp = v.layoutParams as? WindowManager.LayoutParams
        StripRect(cfg?.handles?.getOrNull(i)?.id ?: "h$i", v.isAttachedToWindow,
            if (v.isAttachedToWindow) loc[0] else lp?.x ?: 0, if (v.isAttachedToWindow) loc[1] else lp?.y ?: 0,
            v.width.takeIf { it > 0 } ?: lp?.width ?: 0, v.height.takeIf { it > 0 } ?: lp?.height ?: 0)
    }

    /**
     * Test gesture into OUR OWN strip window (no system input, no permission): DOWN at the strip centre, hold, push
     * inward [pushDp], UP. Runs the real touch listener, so the event log and machine see what a thumb would send.
     * Returns false when no strip of [edge] is attached.
     */
    fun injectGesture(edge: OneHandConfig.Edge, holdMs: Long, pushDp: Int): Boolean {
        val i = cfg?.handles?.indexOfFirst { it.edge == edge } ?: -1
        val v = views.getOrNull(i)?.takeIf { it.isAttachedToWindow } ?: return false
        val r = stripRects()[i]
        val x0 = r.left + r.width / 2f; val y0 = r.top + r.height / 2f
        val dir = if (edge == OneHandConfig.Edge.LEFT) 1f else -1f
        val t0 = android.os.SystemClock.uptimeMillis()
        fun ev(action: Int, t: Long, x: Float) = MotionEvent.obtain(t0, t, action, x, y0, 0)
        EdgeEventLog.record("service", r.id, "injected test gesture hold=${holdMs}ms push=${pushDp}dp")
        handler.post { v.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, t0, x0)) }
        val steps = 6
        for (k in 1..steps) handler.postDelayed({
            val t = android.os.SystemClock.uptimeMillis()
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, t, x0 + dir * dp(pushDp) * k / steps))
        }, holdMs + 40L * k)
        handler.postDelayed({
            v.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis(), x0 + dir * dp(pushDp)))
        }, holdMs + 40L * (steps + 3))
        return true
    }

    /** True while the hold-then-push menu window is on screen. */
    val menuShown: Boolean get() = activated && preview?.isAttachedToWindow == true

    fun showHandles() {
        hideHandles()
        // #775: the switch is checked at the draw, not only by the callers that
        // happen to check it today — no door may bring the strips back while
        // edge menus are off. (testActivate sets it first, so it still works.)
        if (!OneHandPrefs.isEnabled(this)) return
        val c = OneHandConfig.effective(this).also { cfg = it }
        Log.i(TAG, "showHandles: ${c.handles.size} handles, trigger=${c.trigger}, longPressMs=${c.longPressMs}")
        c.handles.forEach { addHandle(it) }
        // NO persistent full-screen layer here: a MATCH_PARENT overlay (even
        // FLAG_NOT_TOUCHABLE) covering the whole screen makes One UI's untrusted-
        // touch policy drop taps to the app below (this is what killed Bitwarden).
        // The preview veil is created lazily in activate() and torn down on end.
    }

    fun hideHandles() {
        views.forEach { runCatching { windowManager?.removeView(it) } }
        views.clear()
        activated = false
        preview?.let { runCatching { windowManager?.removeView(it) } }
        preview = null
        cfg = null
    }

    private fun addPreviewLayer() {
        val wm = windowManager ?: return
        val v = GesturePreviewView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            EdgeHandleWindow.PREVIEW_FLAGS,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // FLAG_NOT_TOUCHABLE: this overlay never intercepts touches so the
        // untrusted-touch opacity rule does NOT apply here. Keep fully opaque so
        // menu items are drawn at full colour (0.6 made them look washed-out).
        lp.alpha = 1.0f
        wm.addView(v, lp)
        preview = v
    }

    private fun addHandle(h: OneHandConfig.Handle) {
        val wm = windowManager ?: return
        val dm = resources.displayMetrics
        val view = View(this).apply {
            background = handleBackground(h.transparency)
            setOnTouchListener { v, e -> onHandleTouch(h, v, e) }
        }
        // Idle window = thin bounded strip, NOT_TOUCH_MODAL (outside it, touches reach the app below).
        val len = if (h.lengthDp > 0) dp(h.lengthDp)
            else (if (h.edge == OneHandConfig.Edge.BOTTOM) dm.widthPixels else dm.heightPixels) * h.lengthPct / 100
        val size = EdgeHandleWindow.size(h.edge, len, dp(OneHandPrefs.stripWidthDp(this)), dm.density,
            EdgeHandleWindow.USER_MAX_THICKNESS_DP)
        val lp = WindowManager.LayoutParams(
            size.widthPx,
            size.heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            EdgeHandleWindow.FLAGS,
            PixelFormat.TRANSLUCENT,
        )
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // #1833 a window title `dumpsys window` prints: the Security tab's emulator proof counts the strips from outside the app.
        lp.title = STRIP_TITLE_PREFIX + h.id
        // GUARANTEE no back-gesture conflict: in gesture-nav mode Samsung's back
        // swipe fires only from the very edge strip, so we sit our handle just
        // PAST that strip (the safe zone). In 3-button mode there's no edge
        // gesture, so we hug the very edge (works perfectly there).
        val gestureNav = isGestureNav()
        val inset = dp(OneHandPrefs.edgeInsetDp(this) ?: if (gestureNav) cfg?.edgeInsetGestureDp ?: 28 else h.edgeInsetDp)
        Log.i(TAG, "addHandle ${h.id} gestureNav=$gestureNav inset=$inset")
        when (h.edge) {
            OneHandConfig.Edge.BOTTOM -> {
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.x = dm.widthPixels * h.positionPct / 100 - lp.width / 2
                lp.y = inset
            }
            else -> {
                lp.gravity = (if (h.edge == OneHandConfig.Edge.LEFT) Gravity.START else Gravity.END) or
                    Gravity.TOP
                lp.x = inset
                lp.y = dm.heightPixels * h.positionPct / 100 - lp.height / 2
            }
        }
        lp.alpha = OVERLAY_ALPHA // < 0.8 → doesn't block the app's touches (untrusted-touch rule)
        // A revoked grant (an update can drop it) throws here; a crash would take the whole service down silently.
        if (runCatching { wm.addView(view, lp) }.onFailure {
                EdgeEventLog.record("error", h.id, "addView failed: ${it.javaClass.simpleName} ${it.message}")
                Log.w(TAG, "addHandle ${h.id} failed: $it")
            }.isFailure) return
        views.add(view)
        EdgeEventLog.record("service", h.id, "strip added ${lp.width}x${lp.height} at x=${lp.x} y=${lp.y} inset=$inset gestureNav=$gestureNav")
        Log.i(TAG, "addHandle ${h.id} edge=${h.edge} size=${lp.width}x${lp.height} x=${lp.x} y=${lp.y} inset=$inset")
        // CRITICAL for the LEFT handle: our inward swipe there IS the system
        // back-gesture direction, so without exclusion the OS steals it. The rect
        // MUST be set from a real layout pass (view.post fires while width/height
        // are still 0 → an EMPTY rect → no protection → the flaky, left-biased
        // failure). Re-apply on every layout so it's always a valid, non-empty rect.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            view.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val w = r - l; val hgt = b - t
                if (w > 0 && hgt > 0) {
                    v.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, w, hgt))
                }
            }
        }
    }

    private fun onHandleTouch(h: OneHandConfig.Handle, view: View, e: MotionEvent): Boolean {
        val c = cfg ?: return true
        // Tap-hold-and-push only: nothing is drawn or fired until the finger has been HELD for the
        // system long-press time; then an inward push opens the menu. A tap or an early sweep stays
        // in PENDING/PASS and does nothing. (We must still return true on DOWN to keep receiving
        // the stream; the OS cannot hand a window's stream back to the app below.)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                EdgeEventLog.record("down", h.id, "x=${e.rawX.toInt()} y=${e.rawY.toInt()}", e.rawX, e.rawY)
                downX = e.rawX; downY = e.rawY; activated = false
                val holdMs = EdgeGestureMachine.holdMs(ViewConfiguration.getLongPressTimeout().toLong(), c.longPressMs)
                val m = EdgeGestureMachine(h.edge, holdMs,
                    EdgeGestureMachine.slopPx(ViewConfiguration.get(this).scaledTouchSlop.toFloat()), dp(c.swipeThresholdDp).toFloat())
                    .also { it.down(e.rawX, e.rawY, e.eventTime); machine = it }
                holdCue?.let { handler.removeCallbacks(it) }
                holdCue = Runnable {
                    if (m.tick(android.os.SystemClock.uptimeMillis())) {
                        EdgeEventLog.record("hold", h.id, "armed after ${holdMs}ms, push inward")
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) // "armed": now push
                    }
                }.also { handler.postDelayed(it, holdMs) }
            }
            MotionEvent.ACTION_MOVE -> {
                val m = machine ?: return true
                val before = m.state
                m.move(e.rawX, e.rawY, e.eventTime)
                if (before == EdgeGestureMachine.State.PENDING && m.state == EdgeGestureMachine.State.PASS)
                    EdgeEventLog.record("pass", h.id, "moved past slop before the hold elapsed", e.rawX, e.rawY)
                if (before != EdgeGestureMachine.State.MENU && m.state == EdgeGestureMachine.State.MENU)
                    EdgeEventLog.record("push", h.id, if (before == EdgeGestureMachine.State.ARMED) "menu opened (hold+push)" else "menu opened (swipe)", e.rawX, e.rawY)
                if (m.state == EdgeGestureMachine.State.MENU) {
                    if (!activated) {
                        activate(h)
                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    updatePreview(h, e.rawX, e.rawY)
                }
            }
            MotionEvent.ACTION_UP -> {
                val outcome = machine?.up(e.eventTime)
                EdgeEventLog.record("up", h.id, when (outcome) {
                    EdgeGestureMachine.Outcome.FIRE -> "fire"
                    EdgeGestureMachine.Outcome.NONE -> "released armed, no push"
                    EdgeGestureMachine.Outcome.TAP -> "tap/early release before hold"
                    null -> "no gesture state"
                }, e.rawX, e.rawY)
                if (outcome == EdgeGestureMachine.Outcome.FIRE) {
                    val key = SwipeClassifier.classify(h.edge, e.rawX - downX, e.rawY - downY, dp(c.swipeThresholdDp))
                    key?.let { slot -> h.gestures[slot]?.let { it.perform(this, labelForAction(it)) } }
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> {
                EdgeEventLog.record("cancel", h.id, "system cancelled the stream (state=${machine?.state})", e.rawX, e.rawY)
                machine?.cancel(); endGesture()
            }
        }
        return true
    }

    /** End of every stream: drop the hold timer and REMOVE (not hide) the preview window. */
    private fun endGesture() {
        holdCue?.let { handler.removeCallbacks(it) }; holdCue = null
        machine = null
        endPreview()
        activated = false
    }

    private fun activate(h: OneHandConfig.Handle) {
        activated = true
        Log.i(TAG, "activate ${h.id} — menu shown")
        if (preview == null) addPreviewLayer() // veil exists ONLY during the gesture
        preview?.begin(buildOptions(h), h.edge)
        preview?.update(downX, downY, downX, downY, null, 0f)
    }

    /** Tear down the mid-gesture veil so no full-screen overlay lingers over the
     *  app (a persistent one makes One UI drop the app's touches). */
    private fun endPreview() {
        preview?.let { runCatching { it.end() }; runCatching { windowManager?.removeView(it) } }
        preview = null
    }

    private fun updatePreview(h: OneHandConfig.Handle, x: Float, y: Float) {
        val dx = x - downX; val dy = y - downY
        val dist = hypot(dx, dy)
        // progress 0→1 over the second threshold distance so L1→L2 morphs smoothly
        val c = cfg ?: return
        val progress = ((dist - dp(c.swipeThresholdDp)) / dp(80)).coerceIn(0f, 1f)
        preview?.update(downX, downY, x, y, SwipeClassifier.sector(h.edge, dx, dy), progress)
    }

    /** Fan of the handle's sector options for the preview, at canonical angles. */
    private fun buildOptions(h: OneHandConfig.Handle): List<GesturePreviewView.Option> =
        OneHandConfig.slotsFor(h.edge).map { slot ->
            val action = h.gestures[slot.key]
            val label = action?.let { labelForAction(it) } ?: slot.label
            val icon = action?.let { iconForAction(it) }
            GesturePreviewView.Option(slot.key, label, icon)
        }

    private fun appIcon(pkg: String): android.graphics.Bitmap? = runCatching {
        val d = packageManager.getApplicationIcon(pkg)
        val size = dp(48)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(android.graphics.Canvas(bmp)); bmp
    }.getOrNull()

    /** The catalogue entry describing an in-app target, matched on the
     *  prefix-insensitive key rather than on either raw spelling. Comparing
     *  raw strings is what let `action:intent://…` and `intent://…` be two
     *  different destinations, and a miss then drew the sector as its own URL. */
    private fun catalogueFor(action: GestureAction.AppTarget): OneHandConfig.AppAction? =
        cfg?.appActions?.firstOrNull { it.key == "action:" + action.target.removePrefix("action:") }

    private fun labelForAction(action: GestureAction): String = when (action) {
        is GestureAction.OpenApp ->
            cfg?.apps?.firstOrNull { it.pkg == action.pkg }?.label ?: action.pkg
        is GestureAction.AppTarget ->
            catalogueFor(action)?.label
                // Unreachable for a DECLARED sector: build.gradle fails the
                // build when a declared target resolves to no tile, so this can
                // only be reached by a stale user override naming a target that
                // has since been removed. It stays deliberately ugly — an
                // obviously wrong row is recoverable, a plausible wrong one is
                // what hid this bug three times.
                ?: action.target.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    /** The glyph for a sector. An `app:` target borrows the launcher icon; an
     *  `action:` target names no package, so it takes the drawable its tile
     *  declares — resolved by NAME against the host app's resources, which is
     *  where the SuperApp keeps `ic_drive`, `ic_ai_chat` and the rest.
     *
     *  The `app:` branch FALLS BACK to that same declared drawable, because
     *  PackageManager is an install-dependent source and it was the only one it
     *  had: `getApplicationIcon` throws for a package that is not installed, so
     *  the sector drew a label with no glyph. That is not hypothetical — Drive
     *  was extracted into its own APK (#302/#304), so the phone now has a sector
     *  pointing at a package that may legitimately be absent, and "no icon on
     *  Drive" has been reported three times. PackageManager still goes FIRST: an
     *  installed app should show its own launcher icon, not a stand-in. */
    private fun iconForAction(action: GestureAction): android.graphics.Bitmap? = when (action) {
        is GestureAction.OpenApp -> appIcon(action.pkg) ?: declaredIcon(action.serialize())
        is GestureAction.AppTarget -> catalogueFor(action)?.icon
            ?.takeIf { it.isNotBlank() }?.let { drawableIcon(it) }
        else -> null
    }

    /** The drawable the tile catalogue declares for a raw target ("app:<pkg>"),
     *  matched on the same normalised key every other lookup here uses. */
    private fun declaredIcon(target: String): android.graphics.Bitmap? =
        cfg?.appActions?.firstOrNull { it.key == "action:" + target.removePrefix("action:") }
            ?.icon?.takeIf { it.isNotBlank() }?.let { drawableIcon(it) }

    private fun drawableIcon(name: String): android.graphics.Bitmap? = runCatching {
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id == 0) return null
        val d = getDrawable(id) ?: return null
        val size = dp(48)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(android.graphics.Canvas(bmp)); bmp
    }.getOrNull()

    /** 0=3-button, 1=2-button, 2=gesture nav. Read from the framework resource. */
    private fun isGestureNav(): Boolean = runCatching {
        val id = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
        id != 0 && resources.getInteger(id) == 2
    }.getOrDefault(false)

    // Visible rounded accent tab so the (otherwise invisible) touch zone is
    // findable. transparency 0..100 → alpha; a small non-zero default keeps it
    // subtle. Fully transparent (0) is still allowed for an invisible handle.
    private fun handleBackground(transparency: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            if (OneHandPrefs.debugVisible(this@OneHandOverlayService)) {
                // Debug: bright bar + outline so placement/size is obvious on-device.
                setColor(Color.argb(120, 255, 0, 0))
                setStroke(dp(2), Color.argb(230, 255, 60, 60))
            } else {
                setColor(Color.argb((transparency.coerceIn(0, 100)) * 255 / 100, 77, 163, 255))
            }
        }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics,
    ).toInt()

    companion object {
        private const val TAG = "OneHand"
        // Keep our overlay windows under the 0.8 opacity threshold of Android 12+
        // "block untrusted touches", so taps still reach the app underneath.
        private const val OVERLAY_ALPHA = 0.6f
        const val ACTION_TEST = "com.diegonmarcos.superapp.onehand.TEST"
        const val EXTRA_MS = "ms"
        /** #1833 every idle strip window's title prefix (WindowManager.LayoutParams.title), visible in `dumpsys window`. */
        const val STRIP_TITLE_PREFIX = "c3-edge-strip-"
        @Volatile var instance: OneHandOverlayService? = null
            private set
        val isRunning: Boolean get() = instance != null
    }
}

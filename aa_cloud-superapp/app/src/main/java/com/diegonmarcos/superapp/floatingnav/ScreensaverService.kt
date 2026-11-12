package com.diegonmarcos.superapp.floatingnav

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.BuildConfig
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.OverlayCompose
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Screensaver — a full-screen OPAQUE BLACK overlay (SYSTEM_ALERT_WINDOW), NOT a
 * lock and NOT an Activity: the app underneath stays resumed, so a playing
 * video/audio keeps going, just hidden behind the black. Shows the time + date
 * at the top and a single "Unlock" button at the bottom (the only actionable).
 *
 * Behaviour matches the "Black Screen" app, renamed Screensaver. Triggered by
 * the floating-nav `screensaver` action. Formats are data-driven
 * (build.json::ui.screensaver → BuildConfig.SCREENSAVER_*).
 */
class ScreensaverService : Service() {

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var overlay: OverlayCompose? = null
    private var clockText by mutableStateOf("")
    private var dateText by mutableStateOf("")

    private val timeFmt by lazy { SimpleDateFormat(BuildConfig.SCREENSAVER_TIME_FORMAT, Locale.getDefault()) }
    private val dateFmt by lazy { SimpleDateFormat(BuildConfig.SCREENSAVER_DATE_FORMAT, Locale.getDefault()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIF_ID, buildNotification())
        // #775: the gate is here, at the draw, and not only in [start]: the
        // idle timer in ShellActivity starts this service bare, and used to
        // put the black cover up with the build's kill-switch off.
        if (!allowed(this)) { stopSelf(); return }
        showOverlay()
        main.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        overlay?.let { runCatching { wm.removeView(it.view) }; it.destroy() }; overlay = null; drawn = false
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            clockText = runCatching { timeFmt.format(now) }.getOrDefault("")
            dateText = runCatching { dateFmt.format(now) }.getOrDefault("")
            main.postDelayed(this, 1000L)
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        // Configs → Launcher → Screensaver picker. 'neon_lights' swaps the black backdrop for the iOS-18 Siri edge-lighting
        // glow + an inner Solar-System orrery; anything else = the classic black clock.
        val neon = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).screensaver == "neon_lights"
        }.getOrDefault(false)
        val root = OverlayCompose(this) {
            ScreensaverScene(neon, clockText, dateText, BuildConfig.SCREENSAVER_UNLOCK_LABEL, onUnlock = { stopSelf() })
        }
        if (runCatching { wm.addView(root.view, overlayParams()) }.isFailure) root.destroy()
        overlay = root
        drawn = true
    }

    private fun overlayParams(): WindowManager.LayoutParams {
        @Suppress("DEPRECATION")
        val flags = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        // Use the REAL screen size (incl. the system-bar regions) so the black
        // covers edge-to-edge — MATCH_PARENT gets clipped to the content frame,
        // leaving the app's purple gradient + nav bar area showing.
        val (w, h) = realScreenSize()
        return WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags, android.graphics.PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    /** Full physical screen size, including the status- and nav-bar regions. */
    private fun realScreenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, "Screensaver", NotificationManager.IMPORTANCE_MIN,
                ).apply { description = "Active while the black-screen screensaver is up."; setShowBadge(false) })
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
            .setContentTitle("Cloud SuperApp — Screensaver")
            .setContentText("Tap Unlock on screen to dismiss.")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "screensaver"
        private const val NOTIF_ID = 0xF2

        @Volatile
        var isRunning: Boolean = false
            private set

        /** The cover window is up right now (read by /api/overlays). */
        @Volatile
        var drawn: Boolean = false
            private set

        /** Whether the screensaver may draw: the build's switch and the grant. */
        fun allowed(ctx: Context): Boolean =
            BuildConfig.SCREENSAVER_ENABLED && Settings.canDrawOverlays(ctx)

        /** Start the screensaver iff enabled and "display over other apps"
         *  is granted. Returns false otherwise (caller can prompt). */
        fun start(ctx: Context): Boolean {
            if (!allowed(ctx)) return false
            val i = Intent(ctx, ScreensaverService::class.java)
            // #1675 targetSdk 35: a background start no longer rides the overlay exemption unless an overlay is visible
            // (ForegroundServiceStartNotAllowedException); never crash for it.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            }.onFailure { android.util.Log.w("FgsStart", "could not start ScreensaverService: ${it.message}") }
            return true
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ScreensaverService::class.java)) }
    }
}

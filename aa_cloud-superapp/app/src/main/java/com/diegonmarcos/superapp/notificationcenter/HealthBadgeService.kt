package com.diegonmarcos.superapp.notificationcenter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.core.FleetNotify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The "MyHealth" badge (was "Health", #517): today's steps in km with the step count, resting heart rate and last
 * night's sleep. A DATA WIDGET, not a keep-alive: an ongoing notification posted with plain notify
 * ([FleetNotify.dataWidgetBuilder]), never startForeground, so Android does not list the app as running in the background
 * for it. This service is a short job (post the cached face, read, post, schedule the next read, stopSelf); started by
 * [BadgeServices.ensureAll] or by its own alarm ([DataWidgetRuntime]).
 *
 * DATA ROUTE: the SAME one as Boards > MyHealth - `readHealthBoard` (HealthBoardReader + the pure HealthAgg) - and no
 * other. The old separate Gateway path is gone from this card. Nothing
 * is estimated or shown as 0 when missing; see [HealthBadgeModel] for the faces.
 */
class HealthBadgeService : Service() {

    private val scope = CoroutineScope(SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Switched off: no Health Connect read, no card, no next alarm.
        if (DataWidgetRuntime.retiredIfOff(this, HealthBadgeService::class.java)) { stopSelf(startId); return START_NOT_STICKY }
        // The last cached face at once, then the read fills it in.
        post()
        refresh(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun post() { runCatching { NotifyGate.postCtx(this, NOTIF_ID, build()) } }

    /** Re-read on the declared cadence. `refresh_minutes` is a customization option, so the owner sets how hard this hits
     *  Health Connect. One inexact alarm, not a loop in a held process. */
    private fun schedule() = DataWidgetRuntime.scheduleNext(this, HealthBadgeService::class.java, NOTIF_ID, refreshMs())

    private fun badge(): BadgeDeclaration.Badge? =
        BadgeServices.declared.firstOrNull { it.id == BADGE_ID }

    private fun refreshMs(): Long {
        val m = badge()?.let { BadgeCustomization.text(this, it, "refresh_minutes") }
            ?.toLongOrNull() ?: DEFAULT_REFRESH_MIN
        return m.coerceIn(5L, 240L) * 60_000L
    }

    /**
     * The card is filled from the last read at once (with its age), then re-read in the background. The read is the
     * shared MyHealth route, for TODAY only (the card needs no 30-day window), under a timeout: whatever happens it ends
     * as data or as a named error, never as "reading".
     */
    private fun refresh(startId: Int) {
        scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val read = kotlinx.coroutines.withTimeoutOrNull(HealthBadgeModel.TIMEOUT_S * 1000L) {
                com.diegonmarcos.superapp.boards.readHealthBoard(applicationContext, days = 1)
            }
            android.util.Log.i("MyHealthCard", "read ${if (read == null) "TIMEOUT" else "done"} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val face = HealthBadgeModel.face(read)
            // A failed refresh keeps showing the last good values (marked with their age) instead of replacing them.
            val failed = face.state == HealthBadgeModel.State.ERROR || face.state == HealthBadgeModel.State.TIMEOUT
            val cached = cached()
            if (failed && cached != null) save(cached.first, cached.second, face.text) else save(face)
            // Switched off while the read was in flight: the result is kept, but nothing is posted or re-armed.
            if (!DataWidgetRuntime.retiredIfOff(this@HealthBadgeService, HealthBadgeService::class.java)) {
                post()
                schedule()
            }
            stopSelf(startId)
        }
    }

    private fun prefs() = getSharedPreferences("myhealth_card", MODE_PRIVATE)
    private fun cached(): Pair<HealthBadgeModel.Face, Long>? =
        HealthBadgeModel.faceFromJson(prefs().getString("face", null))?.let { it to prefs().getLong("at", 0L) }
    private fun save(f: HealthBadgeModel.Face, at: Long = System.currentTimeMillis(), failure: String = "") {
        prefs().edit().putString("face", f.toJson()).putLong("at", at).putString("failure", failure).apply()
    }

    /** One face: the cached one with "updated Xm ago", or - nothing cached yet - the honest first-read line. */
    private fun build(): android.app.Notification {
        val c = cached()
        val f = c?.first
        val age = c?.let { HealthBadgeModel.updated(System.currentTimeMillis() - it.second) }
        val failure = prefs().getString("failure", "").orEmpty()
        val second = if (f == null) getString(R.string.badge_health_reading) else f.text
        val pinned = BadgeServices.pinned(this, BADGE_ID)
        return FleetNotify.dataWidgetBuilder(this, R.drawable.ic_stat_notify, f?.title ?: HealthBadgeModel.TITLE, listOf(second),
                channelId = CHANNEL_ID, pinned = pinned, color = 0xFF0A0A0A.toInt(),
                subText = listOfNotNull("Cloud SA - MyHealth", age).joinToString(" · "))
            .apply { if (pinned) setDeleteIntent(BadgeServices.repostOnDismiss(this@HealthBadgeService, NOTIF_ID)) }
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE) // health data stays off the lockscreen
            .apply {
                val detail = (f?.lines.orEmpty() + listOfNotNull(age, failure.takeIf { it.isNotBlank() }?.let { "Last refresh failed: $it" }))
                if (detail.isNotEmpty()) setStyle(NotificationCompat.BigTextStyle().bigText((listOf(second) + detail).joinToString("\n")))
            }
            // Tap = Boards > MyHealth; with a missing grant, the button runs the same Health Connect dialog the board uses.
            .setContentIntent(BadgeClick.pi(this, BadgeClick.MYHEALTH))
            .apply {
                if (f?.grant == true) BadgeModel.add(this, BadgeModel.Action("grant-access", R.drawable.ic_act_key, "Grant access",
                    android.app.PendingIntent.getActivity(this@HealthBadgeService, 7716,
                        Intent(this@HealthBadgeService, HealthGrantActivity::class.java)
                            .putExtra(HealthGrantActivity.EXTRA_BOARD, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)))
            }
            .let { NotifyGroups.attach(this, it, BADGE_ID) } // #777 group G1
            .build()
            .apply { if (pinned) flags = flags or android.app.Notification.FLAG_NO_CLEAR or android.app.Notification.FLAG_ONGOING_EVENT }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Health activity", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Steps in km, resting heart rate and last night's sleep."
                setShowBadge(false)
            },
        )
    }

    companion object {
        const val NOTIF_ID = 7713
        private const val CHANNEL_ID = "health_activity"
        private const val DEFAULT_REFRESH_MIN = 30L

        /** This badge's id in build.json::ui.notification_center.producers. */
        const val BADGE_ID = "myhealth"
    }
}

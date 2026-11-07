package com.diegonmarcos.superapp.core

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Cloud Store's notifications are SILENT: no sound, no vibration, no light, importance LOW at most (MIN
 * for a progress line). Every channel it posts to is made by a shared lib (FleetAlerts, libs:updater,
 * libs:shizuku-adb-debug-tools), each through [ensure]. PER APP: only [SILENT_PACKAGES] change; every
 * other app keeps its ids, importance and Android's defaults. MIGRATION: Android never changes an
 * existing channel's sound, so a silent app posts on legacy id + [SUFFIX] and [retireLegacy] deletes the
 * old ids at start. A channel that exists is never touched, so what the user raises in Android's
 * settings is kept. Guard: ac_cloud-store/test/test-store-silent-notify.sh.
 */
object SilentChannels {
    const val SUFFIX = "_silent_v2"
    val SILENT_PACKAGES = setOf(StoreNotifyGate.STORE_PKG)
    /** Every id a silent app posted on before [SUFFIX]. */
    val LEGACY_IDS = listOf("fleet_alerts", "superapp-updater", "store_batch", "adb_pairing", "host_shell_channel", "wd_keeper_channel",
        // FleetNotify's two shared channels: a silent app always posts on the suffixed id.
        "fleet_keep_alive", "fleet_data_widget",
        // Cloud Store's torrent download service (TorrentService).
        "torrent_downloads")

    fun isSilent(packageName: String): Boolean = packageName in SILENT_PACKAGES
    fun isSilent(ctx: Context): Boolean = isSilent(ctx.packageName)

    data class Spec(val id: String, val importance: Int, val silent: Boolean)

    fun spec(packageName: String, legacyId: String, importance: Int, progress: Boolean = false): Spec =
        if (!isSilent(packageName)) Spec(legacyId, importance, false)
        else Spec(legacyId + SUFFIX, if (progress) NotificationManager.IMPORTANCE_MIN else importance.coerceAtMost(NotificationManager.IMPORTANCE_LOW), true)

    /** No sound, no vibration (nor a pattern), no light; the badge stays as the caller set it. */
    fun quiet(ch: NotificationChannel): NotificationChannel = ch.apply {
        setSound(null, null)
        enableVibration(false)
        vibrationPattern = null
        enableLights(false)
    }

    fun quiet(ctx: Context, b: NotificationCompat.Builder): NotificationCompat.Builder =
        if (isSilent(ctx)) b.setOnlyAlertOnce(true).setSilent(true) else b

    /** Create [legacyId]'s channel for this app if absent ([configure] runs before [quiet]); returns the id to post on. */
    fun ensure(ctx: Context, legacyId: String, name: CharSequence, importance: Int,
               progress: Boolean = false, configure: NotificationChannel.() -> Unit = {}): String {
        val s = spec(ctx.packageName, legacyId, importance, progress)
        runCatching {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(s.id) == null)
                nm.createNotificationChannel(NotificationChannel(s.id, name, s.importance).apply {
                    configure()
                    if (s.silent) quiet(this)
                })
        }.onFailure { Log.w("SilentChannels", "channel ${s.id}: ${it.javaClass.simpleName}") }
        return s.id
    }

    /** Idempotent, at every start: the first start after the update removes them. */
    fun retireLegacy(ctx: Context) {
        if (!isSilent(ctx)) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        LEGACY_IDS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
    }
}

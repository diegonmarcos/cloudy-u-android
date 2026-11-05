package com.diegonmarcos.cloudaccount

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.diegonmarcos.superapp.profile.AccountDevice
import com.diegonmarcos.superapp.profile.ActivityFeed
import com.diegonmarcos.superapp.profile.AutoBackup
import com.diegonmarcos.superapp.profile.DeviceVault
import com.diegonmarcos.superapp.profile.MeAutocompleteSync

/**
 * #1502 Cloud Account never wakes itself. The Autocomplete sync (#1103) and Settings ▸ Auto-backup (spec 4.11) used to be
 * two periodic WorkManager jobs (6 h and daily), which started this process after the owner had killed it. They now run
 * only while the app is in the FOREGROUND: once per launch from [MainActivity], and when Settings changes the backup mode.
 * Nothing here schedules anything; there is no WorkManager job, alarm, boot receiver or service of Account's own.
 */
object AccountForeground {
    private const val TAG = "AccountForeground"
    private const val PREFS = "account_foreground"
    private const val KEY_LAST_BACKUP = "last_auto_backup"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val LEGACY_WORK = listOf("cloud-account-me-autocomplete", "cloud-account-auto-backup")

    /** Drop the two periodic jobs an older build persisted in WorkManager. Cheap and idempotent. */
    fun retireLegacyJobs(ctx: Context) {
        runCatching { val wm = WorkManager.getInstance(ctx); LEGACY_WORK.forEach { wm.cancelUniqueWork(it) } }
            .onFailure { Log.w(TAG, "legacy jobs not cancelled: ${it.javaClass.simpleName}") }
    }

    /** Called from [MainActivity] (the app is on screen): the Autocomplete pull and, when due, the auto-backup. */
    fun onForeground(ctx: Context) {
        val app = ctx.applicationContext
        kotlin.concurrent.thread(name = "account-foreground-sync") {
            runCatching { MeAutocompleteSync.sync(app) }.onFailure { Log.w(TAG, "autocomplete sync failed: ${it.javaClass.simpleName}") }
            backupIfDue(app)
        }
    }

    /** Pure: is an auto-backup due ([mode] is not Off and the last one is at least a day old)? */
    fun due(mode: String, lastMs: Long, nowMs: Long): Boolean = mode != AutoBackup.OFF && nowMs - lastMs >= DAY_MS

    /** Blocking. Captures this phone into the device vault when [due]; commits nothing when only captured_at would change. */
    fun backupIfDue(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!due(AutoBackup.mode(ctx), p.getLong(KEY_LAST_BACKUP, 0L), now)) return
        val id = AccountDevice.id(ctx)
        if (id.isBlank()) return   // no device picked: nothing to back up to
        val r = runCatching { DeviceVault(ctx).backup(id, false) }.getOrNull() ?: return
        val ok = r.optBoolean("ok")
        if (ok) p.edit().putLong(KEY_LAST_BACKUP, now).apply()
        ActivityFeed.record(ctx, "backup", "auto-backup of $id: " + if (ok) "ok" else "failed, will retry on the next launch")
    }
}

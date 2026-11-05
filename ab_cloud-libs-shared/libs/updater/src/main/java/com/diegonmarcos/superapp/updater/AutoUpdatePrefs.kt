package com.diegonmarcos.superapp.updater

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build

/**
 * Runtime toggle for silent auto-update. Default ON — when on, the installer
 * commits with USER_ACTION_NOT_REQUIRED (no system dialog); when off it uses
 * the normal USER_ACTION_REQUIRED prompt. The Configs/About page reads/writes
 * [silent] and surfaces the one-time "install unknown apps" grant that makes
 * NOT_REQUIRED actually silent (without the grant Android transparently falls
 * back to a prompt). Declared default lives in build.json::release.auto_update.
 * ponytail: one boolean in SharedPreferences — no DataStore.
 */
object AutoUpdatePrefs {
    private const val PREFS = "auto_update"
    private const val KEY_SILENT = "silent"
    private const val KEY_UNATTENDED = "unattended_pass"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_REQUIRE_SILENT = "require_silent"
    private const val KEY_REQUIRE_UNMETERED = "require_unmetered"
    private const val KEY_LAST_CHECK_AT = "last_check_at"
    private const val KEY_LAST_REMOTE_DIGEST = "last_remote_digest"
    private const val KEY_LAST_REMOTE_BYTES = "last_remote_bytes"

    /**
     * Master runtime on/off for auto-update. This is what the "Auto-update"
     * toggle controls — the periodic workers (self + constellation) check this
     * at runtime, so flipping it OFF actually stops auto-updating (the baked
     * BuildConfig.AUTO_UPDATE_ENABLED is only the shipped default). OFF ⇒ manual
     * updates only.
     */
    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, BuildConfig.AUTO_UPDATE_ENABLED)

    fun setEnabled(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()

    fun silent(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SILENT, true)

    /**
     * True while an UNATTENDED pass is in flight — nobody asked for this work,
     * so nothing about it may interrupt.
     *
     * Persisted rather than kept in memory like [UpdateProgress.quiet] because
     * the consumer is [PackageInstallerReceiver]: install results arrive
     * asynchronously, tens of seconds after commit(), and a broadcast receiver
     * has no access to the worker's stack — nor any guarantee of sharing its
     * process lifetime. A flag in a field is simply not observable there.
     *
     * Set for the duration of the pass and cleared in a finally, so a crash
     * mid-pass costs at most one silenced toast, never a permanently muted app.
     */
    fun unattendedPass(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_UNATTENDED, false)

    /** commit(), not apply(): the receiver may read this from a cold process. */
    @SuppressLint("ApplySharedPref")
    fun setUnattendedPass(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_UNATTENDED, on).commit()
    }

    /**
     * "Never show me an install confirmation, even if that means not
     * installing." OFF by default, and it must stay that way.
     *
     * This is the opt-in replacement for a compile-time constant that removed
     * the prompting PackageInstaller from [Fleet]'s ladder for the whole fleet
     * — see the ladder comment in Fleet.channels. One APK ships to thousands of
     * devices, so a build flag cannot express "this device". A preference can:
     * it subtracts the fallback only from the device whose owner asked for it,
     * and it is reversible without a release.
     *
     * Only meaningful on a device with a live privileged shell channel
     * (Wireless debugging / Shizuku), because that is the only case where
     * turning the fallback off costs nothing. Anywhere else it costs every
     * update. UI entry point for the toggle:
     * [requireSilent] / [setRequireSilent], intended for a developer or
     * power-user settings row.
     */
    fun requireSilent(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_SILENT, false)

    fun setRequireSilent(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REQUIRE_SILENT, on).apply()

    /**
     * "Only spend my mobile data on updates if I asked for them." ON by
     * default — the fail-safe answer, and the one a user who never opens this
     * screen would pick. Same reasoning as [requireSilent]: what used to be
     * BuildConfig.AU_REQUIRE_UNMETERED could only say "every device on this
     * build", and one APK ships to thousands of devices on wildly different
     * data plans. The default is hard-coded `true` rather than read from the
     * build constant because the constant is now true everywhere anyway, and a
     * shipped `false` must not be able to silently start spending a stranger's
     * data.
     *
     * Only gates UNATTENDED passes. A user-initiated check/update is explicit
     * consent and downloads on any network — see the `force` branches in
     * [UpdateWorker].
     */
    fun requireUnmetered(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_REQUIRE_UNMETERED, true)

    fun setRequireUnmetered(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REQUIRE_UNMETERED, on).apply()

    fun setSilent(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SILENT, on).apply()

    /**
     * Whether the OS will let us install without a prompt — the "install
     * unknown apps" special access. USER_ACTION_NOT_REQUIRED silently degrades
     * to a prompt without it, so this drives the About grant-row status.
     */
    fun canInstallSilently(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            ctx.packageManager.canRequestPackageInstalls()

    /**
     * Metered per the ACTIVE network. Unknown network ⇒ metered: the cost of
     * guessing wrong is the user's mobile data, and a deferred pass costs only
     * the wait until the next one.
     *
     * There were two byte-identical copies of this, one per worker, and their
     * own comments record that they once DISAGREED — so which data policy
     * applied to a pass depended on which of the two racing workers won.
     * Deliberately the active network rather than WorkManager's UNMETERED
     * constraint, which asks the system default network and reports metered on
     * a permanently-VPN'd phone even over Wi-Fi.
     */
    /** #1502 the ACTIVE network is unmetered (Wi-Fi), the gate Cloud Store's background work asks. */
    fun onUnmetered(ctx: Context): Boolean = !isMetered(ctx)

    private fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return true
        return !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /**
     * What the last COMPLETED check found.
     *
     * Persisted, not held in a field, because the two questions a settings page
     * asks - "when did you last look?" and "what did you find?" - both outlive
     * the process that answered them. A successful self-install KILLS this
     * process by definition (Android tears the app down to replace its APK), so
     * an in-memory answer is guaranteed to be missing at exactly the moment the
     * user goes looking for it.
     *
     * [remoteDigest12] empty means the check ran and found nothing newer. That
     * is a different fact from "no check has ever run", which is [lastCheck]
     * returning null, and the two must not render as the same blank.
     */
    class LastCheck(
        val atMillis: Long,
        val remoteDigest12: String,
        val remoteBytes: Long,
    ) {
        val upToDate: Boolean get() = remoteDigest12.isEmpty()
    }

    /** The last completed check, or null when none has ever finished. */
    fun lastCheck(ctx: Context): LastCheck? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = p.getLong(KEY_LAST_CHECK_AT, 0L)
        if (at <= 0L) return null
        return LastCheck(
            atMillis = at,
            remoteDigest12 = p.getString(KEY_LAST_REMOTE_DIGEST, "").orEmpty(),
            remoteBytes = p.getLong(KEY_LAST_REMOTE_BYTES, 0L),
        )
    }

    /**
     * Record a check that REACHED AN ANSWER. Never called for a check that
     * threw: a failed lookup establishes nothing about the published build, and
     * writing the clock anyway would make "we have not been able to check since
     * Tuesday" render as "checked just now, all fine" - the same class of lie
     * as reporting an install nobody observed.
     *
     * [remoteDigest12] is empty for "up to date"; commit(), not apply(), because
     * the very next thing a successful pass does is install an APK that kills
     * this process.
     */
    @SuppressLint("ApplySharedPref")
    fun recordCheck(ctx: Context, remoteDigest12: String, remoteBytes: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_CHECK_AT, System.currentTimeMillis())
            .putString(KEY_LAST_REMOTE_DIGEST, remoteDigest12)
            .putLong(KEY_LAST_REMOTE_BYTES, remoteBytes)
            .commit()
    }

    /**
     * Why an AUTOMATIC pass must not download right now, in a sentence fit to
     * show a user — or null when nothing is holding it.
     *
     * Task #46 (never auto-download over mobile data) was honoured by a log
     * line and an early return, which is correct behaviour reported as nothing
     * at all: a pass parked on an unmet constraint produced the same silence as
     * a download that had died. Returning the reason as a STRING is what lets
     * the caller publish [UpdateProgress.State.Waiting] instead, so "held back
     * on purpose" and "broken" stop looking alike.
     *
     * Only ever describes the automatic path. A user-initiated install is
     * consent, and consent is not a constraint.
     */
    fun deferredReason(ctx: Context): String? =
        if (requireUnmetered(ctx) && isMetered(ctx))
            "on mobile data — auto-update waits for Wi-Fi. Install now to use mobile data anyway"
        else null
}

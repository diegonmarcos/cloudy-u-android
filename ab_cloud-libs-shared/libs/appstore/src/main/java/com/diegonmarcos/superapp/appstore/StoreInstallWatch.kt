package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.pm.PackageInstaller
import android.util.Log
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.cache.ApkCache
import org.json.JSONObject

/**
 * #858 A handed-over install never strands its row on "Installing…".
 *
 * [StoreStages] records every handover to Android's installer here. Each one
 * is then resolved by [decide]: landed (the installed build changed), retry
 * (the session was aborted / abandoned, or [Decl.timeoutMs] passed with no
 * result) or still waiting. Retry writes an Install-stage note, which turns the
 * row into its retryable "install did not finish — Retry" stage whose tap
 * re-installs from the cached APK. [onForeground] re-launches the prompt of a
 * handover still waiting when the Store comes back.
 */
object StoreInstallWatch {
    private const val TAG = "StoreInstallWatch"
    /** #1562 the declaration is a served dataset of Cloud Store (shared), never an APK asset. */
    const val DATASET = "appstore-install-watch"
    // StoreAuto's declared store (fleet-config.json `store_auto`, device
    // state), under its own key prefix: a handover is device state too.
    private const val PREFS = "store_auto"
    private const val PREFIX = "watch:"

    const val LANDED = "landed"
    const val RETRY = "retry"
    const val WAITING = "waiting"

    const val MSG_RETRY = "the install prompt was dismissed, aborted or not answered — Retry installs " +
        "the downloaded APK again"

    /** #906 The retry sentence, closed with what PackageInstaller last said (or that it said nothing),
     *  so a handover that went quiet never reads as a bare "cached". Pure. */
    fun retryMessage(lastStatus: String?): String =
        "$MSG_RETRY (PackageInstaller: ${lastStatus ?: "no status was delivered for this session"})"

    /** #906 The row text while a handover is still waiting. Pure. */
    fun waitingText(lastStatus: String?): String =
        "waiting on Android's installer - ${lastStatus ?: "handed over, no status delivered yet"}"

    /** A session may not be listed for a moment after handover (or a channel
     *  may not use one at all): "gone" counts only after this long. */
    const val SETTLE_MS = 10_000L

    class Decl(val timeoutMs: Long, val graceMs: Long, val retryLabel: String)
    val DEFAULT = Decl(180_000L, 20_000L, "Retry")

    fun parse(o: JSONObject) = Decl(o.optLong("prompt_timeout_s", 180L) * 1000L,
        o.optLong("resurface_grace_s", 20L) * 1000L, o.optString("retry_label", "Retry"))

    fun load(ctx: Context): Decl = runCatching {
        FleetData.get(ctx, DATASET) { parse(JSONObject(it)) }
    }.getOrNull() ?: DEFAULT

    /** One handover: the package, when, and the installed build before it. */
    class Pending(val pkg: String, val since: Long, val before: String)

    /**
     * Pure. [installed] is the installed build now; [failed] = the installer
     * reported a failure (ABORTED included) for this package; [sessionAlive] =
     * our PackageInstaller session for it still exists.
     */
    fun decide(p: Pending, now: Long, installed: String, failed: Boolean, sessionAlive: Boolean, timeoutMs: Long,
               settleMs: Long = SETTLE_MS): String =
        when {
            installed != p.before -> LANDED
            failed || (!sessionAlive && now - p.since >= settleMs) -> RETRY
            now - p.since >= timeoutMs -> RETRY
            else -> WAITING
        }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(ctx: Context, pkg: String, before: String, now: Long = System.currentTimeMillis()) {
        prefs(ctx).edit().putString(PREFIX + pkg, JSONObject().put("since", now).put("before", before).toString()).commit()
    }

    fun pending(ctx: Context, pkg: String): Pending? = runCatching {
        val o = JSONObject(prefs(ctx).getString(PREFIX + pkg, null)!!)
        Pending(pkg, o.getLong("since"), o.getString("before"))
    }.getOrNull()

    fun forget(ctx: Context, pkg: String) { prefs(ctx).edit().remove(PREFIX + pkg).commit() }

    private fun sessionAlive(ctx: Context, pkg: String): Boolean = runCatching {
        ctx.packageManager.packageInstaller.mySessions.any { it.appPackageName == pkg }
    }.getOrDefault(true)

    private fun failedFor(pkg: String): Boolean =
        (UpdateProgress.state as? UpdateProgress.State.Failed)?.pkg == pkg

    /**
     * Resolve [app]'s handover, if any, and return the verdict (null = none).
     * RETRY leaves the row retryable and unsticks a progress bar still
     * reading Installing / Waiting.
     */
    fun sweep(ctx: Context, app: Fleet.App, now: Long = System.currentTimeMillis()): String? {
        val p = pending(ctx, app.pkg) ?: return null
        val v = decide(p, now, StoreStages.installedKey(ctx, app), failedFor(app.pkg),
            sessionAlive(ctx, app.pkg), load(ctx).timeoutMs)
        when (v) {
            LANDED -> {
                forget(ctx, app.pkg)
                runCatching { UnpackAfterInstall.run(ctx, app.pkg) }   // #1121
            }
            RETRY -> {
                forget(ctx, app.pkg)
                // The installer's own reason (ABORTED: "User rejected") wins.
                if (ApkCache.noteOf(ctx, app.pkg) == null)
                    ApkCache.note(ctx, app.pkg, ApkCache.STAGE_INSTALL, retryMessage(ApkCache.installerStatusOf(ctx, app.pkg)))
                val st = UpdateProgress.state
                if (st is UpdateProgress.State.Installing || st is UpdateProgress.State.Waiting)
                    UpdateProgress.update(UpdateProgress.State.Failed(retryMessage(ApkCache.installerStatusOf(ctx, app.pkg)), appId = app.id, pkg = app.pkg))
                Log.i(TAG, "${app.pkg}: install did not finish — row back to Retry")
            }
        }
        return v
    }

    /**
     * The Store is in front again: resolve every handover, and launch the
     * system prompt again — from the cached APK — for one still waiting after
     * the grace period. Blocking; call off the main thread.
     */
    fun onForeground(ctx: Context, fleet: List<Fleet.App>, now: Long = System.currentTimeMillis()): Boolean {
        val grace = load(ctx).graceMs
        var any = false
        for (app in fleet) {
            val p = pending(ctx, app.pkg) ?: continue
            any = true
            if (sweep(ctx, app, now) != WAITING || now - p.since < grace) continue
            Log.i(TAG, "${app.pkg}: prompt still pending on return — showing it again")
            forget(ctx, app.pkg)
            StoreStages.install(ctx, app)
        }
        return any
    }
}

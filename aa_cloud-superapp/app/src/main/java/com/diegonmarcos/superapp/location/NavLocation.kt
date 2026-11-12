package com.diegonmarcos.superapp.location

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import com.diegonmarcos.superapp.devtools.FleetIpc
import org.json.JSONObject

/**
 * #1690 The SuperApp does not track location: Cloud Nav is the fleet's ONE tracker owner. Every location view of
 * this app (the Weather card, the status strip's GPS dots, the network popup's GPS section) asks Cloud Nav over the
 * fleet IPC door (`tracker/location`, contract v1, served by Cloud Nav's NavLocationWire) for the current / last fix,
 * and [subscribe]s while it is on screen. When Cloud Nav is not installed, does not answer, refuses or has no fix,
 * the [Answer.Unavailable] says exactly why and [openOrInstall] opens or installs it. There is NO fallback that
 * tracks inside the SuperApp: this file makes no location-class call.
 *
 * Pure parts ([parse], [classify]) are JVM tests (NavLocationTest).
 */
object NavLocation {
    const val PKG = "com.diegonmarcos.cloudnav"
    const val LABEL = "Cloud Nav"
    const val OP = "tracker/location"

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long, val ageMs: Long,
                   val source: String, val predicted: Boolean)

    enum class Why { NOT_INSTALLED, NOT_RUNNING, DENIED, OUTDATED, NO_FIX }

    sealed interface Answer {
        data class Ok(val fix: Fix) : Answer
        data class Unavailable(val why: Why, val detail: String) : Answer
    }

    /** The exact line each reason shows; the view puts the open / install action beside it. */
    fun reasonLine(why: Why): String = when (why) {
        Why.NOT_INSTALLED -> "Location comes from Cloud Nav, which is not installed: install it"
        Why.NOT_RUNNING -> "Cloud Nav did not answer: open it so it can share the location"
        Why.DENIED -> "Cloud Nav refused to share the location (this build is not signed with the fleet key)"
        Why.OUTDATED -> "Cloud Nav is too old to share the location: update it"
        Why.NO_FIX -> "Cloud Nav has no location yet: turn its Tracker on"
    }

    /** Pure: the `tracker/location` body to an answer. An unreadable body is OUTDATED (a contract this build does not know). */
    fun parse(body: String): Answer = runCatching {
        val j = JSONObject(body)
        if (!j.optBoolean("fix", false))
            return Answer.Unavailable(Why.NO_FIX, j.optString("reason").ifBlank { reasonLine(Why.NO_FIX) })
        val lat = j.getDouble("lat"); val lon = j.getDouble("lon"); val ts = j.getLong("ts")
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0 || ts <= 0)
            return Answer.Unavailable(Why.NO_FIX, reasonLine(Why.NO_FIX))
        val acc = j.optDouble("accuracy_m", Double.NaN).takeIf { it.isFinite() && it > 0 }?.toFloat()
        Answer.Ok(Fix(lat, lon, acc, ts, j.optLong("age_ms", 0L).coerceAtLeast(0L),
            j.optString("source").ifBlank { "unknown" }, j.optBoolean("predicted", false)))
    }.getOrElse { Answer.Unavailable(Why.OUTDATED, "Cloud Nav answered a location this app cannot read: update both") }

    /** Pure: what the IPC reply means. */
    fun classify(hasDoor: Boolean, ok: Boolean, status: Int, body: String, note: String): Answer = when {
        !hasDoor -> Answer.Unavailable(Why.NOT_INSTALLED, reasonLine(Why.NOT_INSTALLED))
        ok -> parse(body)
        status == 403 -> Answer.Unavailable(Why.DENIED, reasonLine(Why.DENIED))
        status == 404 -> Answer.Unavailable(Why.OUTDATED, reasonLine(Why.OUTDATED))
        else -> Answer.Unavailable(Why.NOT_RUNNING, (reasonLine(Why.NOT_RUNNING) + " ($status $note)").replace(" ( )", "").trim())
    }

    /** The newest answer any view got; the strip reads only this (it never asks on its own). */
    @Volatile var last: Answer? = null
        private set

    /** Blocking (IPC): call off the main thread. Never throws. */
    fun ask(ctx: Context): Answer {
        val a = runCatching {
            val app = ctx.applicationContext
            if (!FleetIpc.hasDoor(app, PKG)) classify(false, false, 0, "", "")
            else FleetIpc.call(app, PKG, OP, emptyMap(), httpFallback = false).let { r -> classify(true, r.ok, r.status, r.body, r.note) }
        }.getOrElse { Answer.Unavailable(Why.NOT_RUNNING, reasonLine(Why.NOT_RUNNING)) }
        last = a
        return a
    }

    /** The newest fix [last] holds, or null. */
    fun lastFix(): Fix? = (last as? Answer.Ok)?.fix

    /**
     * Subscribe: ask now and every [periodMs] on a background thread, [onAnswer] on that thread, until the returned
     * handle is closed (a view closes it when it leaves the screen). Asking Cloud Nav costs this app no location.
     */
    fun subscribe(ctx: Context, periodMs: Long = 15_000L, onAnswer: (Answer) -> Unit): AutoCloseable {
        val t = HandlerThread("nav-location").apply { start() }
        val h = Handler(t.looper)
        val app = ctx.applicationContext
        val tick = object : Runnable {
            override fun run() {
                runCatching { onAnswer(ask(app)) }
                h.postDelayed(this, periodMs.coerceAtLeast(5_000L))
            }
        }
        h.post(tick)
        return AutoCloseable { h.removeCallbacksAndMessages(null); t.quitSafely() }
    }

    /** Opens Cloud Nav, or starts its fleet install when it is absent. Returns the line to show (null = opened). */
    fun openOrInstall(ctx: Context): String? {
        val launch = runCatching { ctx.packageManager.getLaunchIntentForPackage(PKG) }.getOrNull()
        if (launch != null && runCatching { ctx.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)) return null
        return runCatching { com.diegonmarcos.superapp.launcher.AppInstall.start(ctx, PKG, LABEL).message }
            .getOrElse { "Cloud Nav could not be opened or installed: ${it.message ?: it.javaClass.simpleName}" }
    }
}

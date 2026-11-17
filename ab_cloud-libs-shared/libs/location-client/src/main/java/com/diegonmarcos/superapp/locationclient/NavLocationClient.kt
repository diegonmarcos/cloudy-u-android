package com.diegonmarcos.superapp.locationclient

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONObject

/**
 * #1770 THE fleet client of location. Cloud Nav is the only app that starts a location fix; every other app asks Cloud Nav for
 * the LAST KNOWN fix over the fleet IPC door (a ContentProvider call on `com.diegonmarcos.cloudnav.fleet`, op `tracker/location`,
 * contract v1 written by Cloud Nav's NavLocationWire) and gets it with its age and accuracy. Nothing here ever asks the platform
 * for a position: no android.location import, no Play Services (LocationClientNeverLocatesTest, location-owner guard).
 *
 * Pure parts ([parse], [classify], [freshEnough]) are JVM tests. [ask] is blocking IPC: call off the main thread.
 */
object NavLocationClient {
    const val PKG = "com.diegonmarcos.cloudnav"
    const val OP = "tracker/location"

    // The fleet IPC wire (devtools FleetIpcWire); the location-owner guard checks these equal the originals.
    const val METHOD = "fleet.ipc.v1"
    const val K_STATUS = "status"
    const val K_BODY = "body"
    const val K_PIPE = "pipe"

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Float?, val timeMs: Long, val ageMs: Long,
                   val source: String, val predicted: Boolean)

    enum class Why { NOT_INSTALLED, NOT_RUNNING, DENIED, OUTDATED, NO_FIX }

    sealed interface Answer {
        data class Ok(val fix: Fix) : Answer
        data class Unavailable(val why: Why, val detail: String) : Answer
    }

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

    /** Pure: is [fix] young enough (its age at answer time plus the time since [answeredAtMs]) and accurate enough for the caller? */
    fun freshEnough(fix: Fix, answeredAtMs: Long, nowMs: Long, maxAgeMs: Long, maxAccuracyM: Float = Float.MAX_VALUE): Boolean =
        fix.ageMs + (nowMs - answeredAtMs).coerceAtLeast(0L) <= maxAgeMs && (fix.accuracyM ?: Float.MAX_VALUE) <= maxAccuracyM

    /** The newest answer any caller got; reading it costs no IPC. */
    @Volatile var last: Answer? = null
        private set
    @Volatile var lastAtMs: Long = 0L
        private set

    /** Blocking (IPC): call off the main thread. Never throws, never starts a location fix: Cloud Nav answers from its own store. */
    fun ask(ctx: Context, nowMs: Long = System.currentTimeMillis()): Answer {
        val a = runCatching {
            val app = ctx.applicationContext
            val door = runCatching { app.packageManager.resolveContentProvider("$PKG.fleet", 0) != null }.getOrDefault(false)
            if (!door) classify(false, false, 0, "", "")
            else call(app).let { classify(true, it.first in 200..299, it.first, it.second, "") }
        }.getOrElse { Answer.Unavailable(Why.NOT_RUNNING, reasonLine(Why.NOT_RUNNING)) }
        last = a; lastAtMs = nowMs
        return a
    }

    /** The fix, or null with the reason in [last]; [maxAgeMs] bounds how old Cloud Nav's fix may be. */
    fun lastKnown(ctx: Context, maxAgeMs: Long = 15 * 60_000L, maxAccuracyM: Float = Float.MAX_VALUE): Fix? {
        val at = System.currentTimeMillis()
        val a = ask(ctx, at)
        return (a as? Answer.Ok)?.fix?.takeIf { freshEnough(it, at, at, maxAgeMs, maxAccuracyM) }
    }

    /** (status, body) of one provider call; status 504-like 0 when the door returned nothing. */
    @Suppress("DEPRECATION")
    private fun call(ctx: Context): Pair<Int, String> {
        val client = ctx.contentResolver.acquireUnstableContentProviderClient("$PKG.fleet") ?: return 0 to ""
        try {
            val b: Bundle = client.call(METHOD, OP, Bundle()) ?: return 0 to ""
            val body = b.getString(K_BODY)
                ?: b.getParcelable<ParcelFileDescriptor>(K_PIPE)?.let { pfd ->
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
                }.orEmpty()
            return b.getInt(K_STATUS, 500) to body
        } finally {
            client.close()
        }
    }
}

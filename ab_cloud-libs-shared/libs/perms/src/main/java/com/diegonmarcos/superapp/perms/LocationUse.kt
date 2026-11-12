package com.diegonmarcos.superapp.perms

import android.content.Context

/**
 * #1666 The app's own ledger of location-class accesses: the last time each call site touched an API Android counts as location
 * use (a fix, getLastKnownLocation, a GNSS status callback, a Wi-Fi scan or scan-result read, an SSID / BSSID read, a BLE scan,
 * cell identity), with the API and what triggered it. X-Ray App's "Location use" section lists it next to the system's own
 * app-ops view, so a lit location dot can be traced to the call site that lit it.
 *
 * One SharedPreferences key per call site (the latest access overwrites the previous one): bounded, cheap, never throws. Every
 * call site that an owner allowlist names (aa_cloud-superapp/test/location-use-allowlist.txt) notes here.
 */
object LocationUse {

    /** Who asked: a screen the user opened, a button the user pressed, or the 4 h weather cadence (#1654). */
    object Trigger {
        const val SCREEN = "screen open"
        const val BUTTON = "user action"
        const val WEATHER = "weather 4 h"
        const val TRACKER = "tracker (user started)"
    }

    data class Entry(val site: String, val atMs: Long, val api: String, val trigger: String)

    private const val PREFS = "location_use_ledger"
    private const val SEP = "\u001f"

    /** A call site polled every second (the Wi-Fi page's link sample) writes at most once per this window. */
    const val WRITE_EVERY_MS = 5_000L
    private val lastWrite = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Record that [site] just called [api] because of [trigger]. Never throws. */
    fun note(ctx: Context, site: String, api: String, trigger: String, now: Long = System.currentTimeMillis()) {
        val prev = lastWrite[site]
        if (prev != null && now - prev in 0 until WRITE_EVERY_MS) return
        lastWrite[site] = now
        runCatching {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(site, encode(now, api, trigger)).apply()
        }
    }

    /** Every call site's last access, newest first; empty when nothing was ever noted or the store cannot be read. */
    fun all(ctx: Context): List<Entry> = runCatching {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.mapNotNull { (k, v) -> decode(k, v as? String) }
    }.getOrDefault(emptyList()).sortedByDescending { it.atMs }

    fun encode(atMs: Long, api: String, trigger: String): String =
        listOf(atMs.toString(), api.replace(SEP, " "), trigger.replace(SEP, " ")).joinToString(SEP)

    fun decode(site: String, raw: String?): Entry? {
        val p = raw?.split(SEP) ?: return null
        if (p.size < 3) return null
        val at = p[0].toLongOrNull() ?: return null
        return Entry(site, at, p[1], p[2])
    }
}

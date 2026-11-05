package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
import com.diegonmarcos.superapp.updater.Fleet
import org.json.JSONObject
import kotlin.concurrent.thread
import com.diegonmarcos.superapp.updater.BuildConfig as AuConfig

/**
 * #857 UPDATES FIRST. On an unmetered network with Auto-update ON, every
 * pending update is queued and downloaded (the [StoreAuto] chain, at the
 * highest priority) before the Store page does any other work. The rule —
 * whether it applies, what runs first, what waits, how long — is declared in
 * assets/appstore-priority.json; this object only evaluates it.
 */
object StorePriority {
    private const val TAG = "StorePriority"
    const val ASSET = "appstore-priority.json"
    const val HIGHEST = "highest"

    class Decl(
        val enabled: Boolean,
        val priority: String,
        val needAuto: Boolean,
        val network: String,
        val first: String,
        val after: List<String>,
    )

    /** What applies when the asset is missing or unreadable: updates first. */
    val DEFAULT = Decl(true, HIGHEST, true, "unmetered", "download_all_updates",
        listOf("catalogue_refresh", "icons", "details"))

    fun parse(o: JSONObject): Decl {
        val u = o.getJSONObject("updates_first")
        val w = u.optJSONObject("when") ?: JSONObject()
        val after = u.optJSONArray("after")
        return Decl(
            enabled = u.optBoolean("enabled", true),
            priority = u.optString("priority", HIGHEST),
            needAuto = w.optBoolean("auto_update", true),
            network = w.optString("network", "unmetered"),
            first = u.optString("first", "download_all_updates"),
            after = if (after == null) DEFAULT.after else List(after.length()) { after.getString(it) },
        )
    }

    fun load(ctx: Context): Decl = runCatching {
        parse(JSONObject(ctx.assets.open(ASSET).use { it.readBytes().decodeToString() }))
    }.getOrElse { DEFAULT }

    /** True when pending updates must run before any other Store work. Pure. */
    fun updatesFirst(d: Decl, autoOn: Boolean, unmetered: Boolean): Boolean =
        d.enabled && d.priority == HIGHEST && (autoOn || !d.needAuto) &&
            (unmetered || d.network != "unmetered")

    /** The order the Store page runs its work in. Pure. */
    fun plan(d: Decl, autoOn: Boolean, unmetered: Boolean): List<String> =
        if (updatesFirst(d, autoOn, unmetered)) listOf(d.first) + d.after else d.after

    fun autoOn(ctx: Context): Boolean = AuConfig.AUTO_UPDATE_ENABLED && AutoUpdatePrefs.enabled(ctx)

    fun unmetered(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)!!
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)!!
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    /**
     * #861 Start the auto chain on its own max-priority thread when
     * [updatesFirst] holds, and RETURN. Fire and forget: updates get their
     * priority in the download queue only. Nothing the Store page shows awaits,
     * joins or gates on this chain — #857 held the catalogue check until the
     * chain's download phase was over, so a failing or hung chain (#860 DNS)
     * left the page on "Checking…" forever. Returns true when it was started.
     */
    fun startUpdatesAsync(ctx: Context, fleet: List<Fleet.App>): Boolean {
        val app = ctx.applicationContext
        val d = load(app)
        if (!updatesFirst(d, autoOn(app), unmetered(app))) return false
        // #1517 At most once per TTL: every tab switch used to start another chain beside the user's download.
        if (!StoreChecks.mayStartUpdatesFirst()) return false
        Log.i(TAG, "Wi-Fi + Auto-update: downloading every pending update at max priority, beside the page")
        thread(name = "store-updates-first", priority = Thread.MAX_PRIORITY) {
            runCatching { StoreAuto.run(app, fleet, StoreAuto.TRIGGER_STORE_REFRESH) }
                .onFailure { Log.w(TAG, "updates-first chain failed: ${it.message}") }
        }
        return true
    }

}

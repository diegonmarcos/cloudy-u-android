package com.diegonmarcos.superapp.wifi

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import com.diegonmarcos.superapp.fleetdata.FleetPack

/**
 * The process-wide analyzer (#1263): one [WifiController] per process, so the five tabs (and any host that embeds the panel)
 * share one scan history, one throttle window and the running tools. The host's [WifiHostConfig] is read on first use.
 *
 * The defaults (`wifi-config` and `channel-plans`, #1547) are the served shared datasets, never an APK asset. While it is not served yet (first run, provider
 * absent) the controller runs on the built-in parse defaults and is swapped for one on the served config the next time the page opens
 * and nothing is attached to it (a scan history is never dropped under a running page).
 */
object WifiHub {
    @Volatile private var controller: WifiController? = null
    @Volatile private var onFallback = false
    private var lastTry = 0L

    /** The shared dataset `wifi-config`: thresholds, tool bounds and endpoints; null while it is not served or is refused. */
    const val CONFIG_DATASET = "wifi-config"

    fun controller(ctx: Context, host: WifiHostConfig = WifiHostConfig()): WifiController = synchronized(this) {
        val have = controller
        if (have != null && !(onFallback && have.idle() && System.currentTimeMillis() - lastTry > 5_000)) return have
        val app = ctx.applicationContext
        lastTry = System.currentTimeMillis()
        val served = FleetData.get(app, CONFIG_DATASET) { text -> text.also { org.json.JSONObject(it) } }
        val servedPlans = FleetData.get(app, ChannelPlans.DATASET) { text -> runCatching { ChannelPlans.parse(text) }.getOrNull() }
        if (have != null && (served == null || servedPlans == null)) return have
        val cfg = WifiConfig.parse(served ?: "{}", host.overridesJson)
        val plans = servedPlans ?: ChannelPlans.DEFAULT
        // #1547 the registry is a downloadable pack: fetched on first use, cached, answered offline from the cache; empty (no vendors) if never fetched
        onFallback = served == null || servedPlans == null
        WifiController(app, cfg, plans) { OuiTable.parse(FleetPack.text(app, "wifi-oui").orEmpty()) }.also { controller = it }
    }
}

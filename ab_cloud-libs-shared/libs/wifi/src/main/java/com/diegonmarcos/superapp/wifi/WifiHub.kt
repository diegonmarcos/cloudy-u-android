package com.diegonmarcos.superapp.wifi

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetPack

/**
 * The process-wide analyzer (#1263): one [WifiController] per process, so the five tabs (and any host that embeds the panel)
 * share one scan history, one throttle window and the running tools. The host's [WifiHostConfig] is read on first use.
 */
object WifiHub {
    @Volatile private var controller: WifiController? = null

    fun controller(ctx: Context, host: WifiHostConfig = WifiHostConfig()): WifiController = controller ?: synchronized(this) {
        controller ?: run {
            val app = ctx.applicationContext
            fun asset(n: String) = app.assets.open("wifi/$n").bufferedReader().use { it.readText() }
            val cfg = WifiConfig.parse(asset("wifi-config.json"), host.overridesJson)
            val plans = ChannelPlans.parse(asset("channel-plans.json"))
            // #1547 the registry is a downloadable pack: fetched on first use, cached, answered offline from the cache; empty (no vendors) if never fetched
            WifiController(app, cfg, plans) { OuiTable.parse(FleetPack.text(app, "wifi-oui").orEmpty()) }.also { controller = it }
        }
    }
}

package com.diegonmarcos.cloudnav.tracker

import org.json.JSONObject

/**
 * #1690 Cloud Nav is the fleet's ONE location tracker owner. `tracker/location` on the fleet IPC door answers the
 * current / last location so the SuperApp's location views (Weather card, status strip GPS dots, network popup GPS
 * section) read it instead of tracking themselves. Contract v1, exact field names (the SuperApp's NavLocation parses
 * them): v, owner, fix, and with a fix lat, lon, accuracy_m, ts, age_ms, source, predicted; without one a reason.
 * Pure: the provider passes the tracker's fixes ([StepsRepository.fixes]), nothing here reads a store.
 */
object NavLocationWire {
    /** How far back the newest fix is looked for. */
    const val WINDOW_MS = 7L * 86_400_000L

    fun toJson(fixes: List<PlaceFix>, nowMs: Long): JSONObject {
        val valid = fixes.filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 &&
            !(it.lat == 0.0 && it.lon == 0.0) && it.ts > 0 }
        // A measured fix wins over a predicted (dead-reckoned) one; predicted is used only when nothing was measured.
        val f = valid.filter { !it.predicted }.maxByOrNull { it.ts } ?: valid.maxByOrNull { it.ts }
        val o = JSONObject().put("v", 1).put("owner", "cloud-nav")
        if (f == null) return o.put("fix", false).put("reason", "Cloud Nav has no location in the last 7 days: turn its Tracker on")
        return o.put("fix", true).put("lat", f.lat).put("lon", f.lon).put("accuracy_m", f.accuracyM)
            .put("ts", f.ts).put("age_ms", (nowMs - f.ts).coerceAtLeast(0L))
            .put("source", f.source.ifBlank { "unknown" }).put("predicted", f.predicted)
    }
}

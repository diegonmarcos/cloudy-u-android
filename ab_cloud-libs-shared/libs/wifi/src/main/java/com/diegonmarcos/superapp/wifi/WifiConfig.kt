package com.diegonmarcos.superapp.wifi

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a host injects (#1263): a JSON object deep-merged over the served `wifi-config` dataset (#1547; the parse defaults while it is not served). The SuperApp passes build.json
 * `wifi_analyzer` (baked into BuildConfig); Cloud Calc passes its own. The lib knows no host.
 */
data class WifiHostConfig(val overridesJson: String = "{}", val appLabel: String = "")

/** The resolved analyzer configuration: thresholds, smoothing, throttle, tool bounds and endpoints. Pure: JVM-tested. */
data class WifiConfig(
    val tabs: List<String>,
    val rssi: RssiThresholds,
    val alpha: Double,
    val historyMax: Int,
    val staleMs: Long,
    val dropMs: Long,
    val autoIntervalMs: Long,
    val throttleLimit: Int,
    val throttleWindowMs: Long,
    val dfsPenalty: Double,
    val nonOverlap24: List<Int>,
    val pingCount: Int,
    val pingIntervalS: Double,
    val pingTimeoutS: Int,
    val pingDefaultHost: String,
    val traceMaxHops: Int,
    val traceTimeoutS: Int,
    val dnsDefaultName: String,
    val portMax: Int,
    val portConcurrency: Int,
    val portTimeoutMs: Int,
    val portDefaultSpec: String,
    val sweepConcurrency: Int,
    val sweepTimeoutMs: Int,
    val sweepPorts: List<Int>,
    val ssdpMs: Long,
    val nsdMs: Long,
    val netbiosMs: Int,
    val nsdTypes: List<String>,
    val downloadUrl: String,
    val uploadUrl: String,
    val downloadBytes: Long,
    val uploadBytes: Long,
    val throughputMaxS: Int,
    val locatorMinMs: Long,
    val locatorMaxMs: Long,
    val toneMs: Int,
    val heatRows: Int,
    val heatCols: Int,
    val heatSamples: Int,
    val portNames: Map<Int, String>,
) {
    fun downloadUrlFor(bytes: Long = downloadBytes): String = downloadUrl.replace("{bytes}", bytes.toString())

    companion object {
        fun parse(defaults: String, overrides: String = "{}"): WifiConfig {
            val o = merge(JSONObject(defaults), runCatching { JSONObject(overrides.ifBlank { "{}" }) }.getOrDefault(JSONObject()))
            fun obj(k: String) = o.optJSONObject(k) ?: JSONObject()
            fun ints(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getInt(it) }
            fun strs(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            val r = obj("rssi"); val sm = obj("smoothing"); val sc = obj("scan"); val ra = obj("rating"); val pi = obj("ping")
            val tr = obj("traceroute"); val dn = obj("dns"); val ps = obj("port_scan"); val sw = obj("sweep"); val tp = obj("throughput")
            val lo = obj("locator"); val hm = obj("heatmap"); val pn = obj("ports")
            return WifiConfig(
                tabs = strs(o.optJSONArray("tabs")).ifEmpty { listOf("scan", "channels", "link", "tools", "log") },
                rssi = RssiThresholds(r.optInt("excellent", -55), r.optInt("good", -67), r.optInt("fair", -75), r.optInt("poor", -85), r.optInt("noise_floor_dbm", -95)),
                alpha = sm.optDouble("alpha", 0.35).coerceIn(0.05, 1.0), historyMax = sm.optInt("history", 90).coerceIn(10, 1000),
                staleMs = sm.optLong("stale_ms", 45_000), dropMs = sm.optLong("drop_ms", 600_000),
                autoIntervalMs = sc.optLong("auto_interval_ms", 30_000).coerceAtLeast(5_000),
                throttleLimit = sc.optInt("throttle_limit", 4), throttleWindowMs = sc.optLong("throttle_window_ms", 120_000),
                dfsPenalty = ra.optDouble("dfs_penalty", 0.5), nonOverlap24 = ints(ra.optJSONArray("non_overlap_24")).ifEmpty { listOf(1, 6, 11) },
                pingCount = pi.optInt("count", 20).coerceIn(1, 1000), pingIntervalS = pi.optDouble("interval_s", 0.2).coerceAtLeast(0.2),
                pingTimeoutS = pi.optInt("timeout_s", 2), pingDefaultHost = pi.optString("default_host", "example.com"),
                traceMaxHops = tr.optInt("max_hops", 30).coerceIn(1, 64), traceTimeoutS = tr.optInt("timeout_s", 1),
                dnsDefaultName = dn.optString("default_name", "example.com"),
                portMax = ps.optInt("max_ports", 1024).coerceIn(1, 65535), portConcurrency = ps.optInt("concurrency", 64).coerceIn(1, 256),
                portTimeoutMs = ps.optInt("timeout_ms", 400), portDefaultSpec = ps.optString("default_spec", "22,80,443"),
                sweepConcurrency = sw.optInt("concurrency", 48).coerceIn(1, 128), sweepTimeoutMs = sw.optInt("timeout_ms", 700),
                sweepPorts = ints(sw.optJSONArray("probe_ports")).ifEmpty { listOf(80, 443) }, ssdpMs = sw.optLong("ssdp_ms", 3000),
                nsdMs = sw.optLong("nsd_ms", 6000), netbiosMs = sw.optInt("netbios_ms", 600),
                nsdTypes = strs(o.optJSONArray("nsd_types")),
                downloadUrl = tp.optString("download_url"), uploadUrl = tp.optString("upload_url"),
                downloadBytes = tp.optLong("bytes", 25_000_000), uploadBytes = tp.optLong("upload_bytes", 10_000_000),
                throughputMaxS = tp.optInt("max_seconds", 15),
                locatorMinMs = lo.optLong("min_interval_ms", 80), locatorMaxMs = lo.optLong("max_interval_ms", 1400), toneMs = lo.optInt("tone_ms", 60),
                heatRows = hm.optInt("rows", 6).coerceIn(2, 20), heatCols = hm.optInt("cols", 6).coerceIn(2, 20), heatSamples = hm.optInt("samples", 5).coerceIn(1, 50),
                portNames = pn.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to pn.getString(k) } }.toMap(),
            )
        }

        /** [over] deep-merged into [base] (objects merge, everything else replaces). Keys starting `_` (docs) are skipped. */
        fun merge(base: JSONObject, over: JSONObject): JSONObject {
            for (k in over.keys()) {
                if (k.startsWith("_")) continue
                val v = over.get(k); val b = base.opt(k)
                base.put(k, if (v is JSONObject && b is JSONObject) merge(b, v) else v)
            }
            return base
        }
    }
}

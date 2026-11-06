package com.diegonmarcos.capitalfinterm

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1539 the in-app refresh of the CB snapshot: on demand (never polled), against the same APIs and by the same rules as scripts/gen-cb-snapshot.py. It reads only the
 * declarations (CbDecl: countries, indicators, their ordered source rules) and writes the snapshot schema of data/cb-snapshot.json, so CbSnapshot.parse reads it. A
 * country an indicator has no answering rule for is simply absent (the page shows 'n/a (no free source)'); nothing is typed or estimated here.
 */
class CbRefresh(private val http: Http, private val decl: CbDecl, private val today: String, private val currentYear: Int) {
    private val memo = ConcurrentHashMap<String, String>()   // "" = the source did not answer
    private val histFrom = currentYear - decl.historyYears
    private val fcTo = currentYear + decl.forecastYears - 1
    private val byId = decl.countries.associateBy { it.id }
    private val bisAreas = decl.countries.map { it.bis }.distinct().sorted().joinToString("+")
    private val oecdAreas = decl.countries.mapNotNull { it.oecd }.joinToString("+")
    private val oecdMeasures = decl.indicators.values.flatMap { it.rules }.filter { it.src == "oecd_eo" }.map { it.series }.distinct().sorted().joinToString("+")

    private fun text(url: String, ua: String = ""): String? {
        memo[url]?.let { return it.ifEmpty { null } }
        var body = ""
        for (attempt in 0 until 2) {
            body = try { http.send(url, if (ua.isEmpty()) emptyMap() else mapOf("User-Agent" to ua), null) } catch (e: Exception) { "" }
            if (body.isNotEmpty()) break
        }
        memo[url] = body
        return body.ifEmpty { null }
    }

    private fun src(id: String): CbSource = decl.sources.getValue(id)
    private fun imfUrl(series: String) = src("imf").url.replace("{series}", series)
    private fun from() = (histFrom - 1).toString()
    private fun fredUrl(series: String) = src("fred").url.replace("{series}", series).replace("{from}", from())
    private fun wbUrl(series: String) = src("wb").url.replace("{codes}", decl.countries.map { it.wb }.distinct().sorted().joinToString(";")).replace("{series}", series).replace("{from}", from()).replace("{to}", currentYear.toString())
    private fun bisUrl(series: String, key: String) = src("bis").url.replace("{series}", series).replace("{key}", key.replace("{bis}", bisAreas)).replace("{from}", from())
    private fun oecdUrl() = src("oecd_eo").url.replace("{areas}", oecdAreas).replace("{series}", oecdMeasures).replace("{from}", from()).replace("{to}", fcTo.toString())
    private fun ecbVintages(): List<String> {
        val yy = currentYear % 100
        return listOf("S", "G", "W").map { java.lang.String.format(java.util.Locale.ROOT, "%s%02d", it, yy) } + java.lang.String.format(java.util.Locale.ROOT, "S%02d", yy - 1)
    }
    private fun ecbUrl(series: String, vintage: String) = src("ecb_mpd").url.replace("{series}", series).replace("{vintage}", vintage)

    /** The URLs the first rule pass needs, so they can be fetched in parallel before the (sequential) resolution reads them from the memo. */
    private fun urls(rule: CbRule, c: CbCountry): List<Pair<String, String>> = when (rule.src) {
        "imf" -> listOf(imfUrl(rule.series) to src("imf").userAgent)
        "oecd_eo" -> listOf(oecdUrl() to "")
        "wb" -> listOf(wbUrl(rule.series) to "")
        "bis" -> listOf(bisUrl(rule.series, rule.key.ifEmpty { "M.{bis}" }) to "")
        "fred" -> listOf(fredUrl(rule.series.replace("{iso2}", c.id)) to "")
        "ecb_mpd" -> ecbVintages().map { ecbUrl(rule.series, it) to "" }.take(1)
        else -> emptyList()
    }

    private class Got(val annual: Map<Int, Double>, val ytd: CbYtd?, val release: String)

    private fun scaled(a: Map<Int, Double>, s: Double) = if (s == 1.0) a else a.mapValues { it.value * s }

    private fun fetch(rule: CbRule, c: CbCountry): Got? {
        val tr = rule.transform == "yoy"
        when (rule.src) {
            "imf" -> {
                val v = text(imfUrl(rule.series), src("imf").userAgent)?.let { CbSources.imf(it, rule.series)[c.iso3] } ?: return null
                if (v.isEmpty()) return null
                return Got(scaled(if (tr) CbSources.yoy(v) else v, rule.scale), null, "IMF World Economic Outlook, retrieved $today")
            }
            "oecd_eo" -> {
                val area = c.oecd ?: return null
                val (data, rel) = text(oecdUrl())?.let { CbSources.oecd(it) } ?: return null
                val v = data[area to rule.series] ?: return null
                return Got(scaled(if (tr) CbSources.yoy(v) else v, rule.scale), null, "${rel.ifEmpty { "OECD Economic Outlook" }}, retrieved $today")
            }
            "wb" -> {
                val (data, upd) = text(wbUrl(rule.series))?.let { CbSources.worldBank(it) } ?: return null
                val v = data[if (c.id == "EA") "EMU" else c.iso3] ?: return null
                return Got(scaled(if (tr) CbSources.yoy(v) else v, rule.scale), null, "World Bank, last updated ${upd.ifEmpty { "n/a" }}, retrieved $today")
            }
            "bis" -> {
                val tgt = if (rule.ref.isNotEmpty()) byId.getValue(rule.ref) else c
                val data = text(bisUrl(rule.series, rule.key.ifEmpty { "M.{bis}" }))?.let { CbSources.bis(it) }?.get(tgt.bis) ?: return null
                val (a, ytd) = CbSources.annualise(data.mapValues { listOf(it.value) }, rule.agg, rule.transform)
                return Got(scaled(a, rule.scale), ytd?.let { it.copy(value = it.value * rule.scale) }, "BIS ${rule.series}, retrieved $today")
            }
            "fred" -> {
                val series = rule.series.replace("{iso2}", c.id)
                val data = text(fredUrl(series))?.let { CbSources.fred(it) }?.takeIf { it.isNotEmpty() } ?: return null
                val (a, ytd) = CbSources.annualise(data, rule.agg, rule.transform)
                val last = data.keys.maxWithOrNull(compareBy({ it.first }, { it.second }))!!
                return Got(scaled(a, rule.scale), ytd?.let { it.copy(value = it.value * rule.scale) }, "FRED $series, last observation ${java.lang.String.format(java.util.Locale.ROOT, "%04d-%02d", last.first, last.second)}, retrieved $today")
            }
            "ecb_mpd" -> {
                for (v in ecbVintages()) {
                    val (vals, title) = text(ecbUrl(rule.series, v))?.takeIf { it.startsWith("KEY") }?.let { CbSources.ecb(it) } ?: continue
                    if (vals.isNotEmpty()) return Got(vals, null, "${title.ifEmpty { "ECB staff macroeconomic projections (MPD vintage $v)" }}, retrieved $today")
                }
                return null
            }
        }
        return null
    }

    private fun years(m: Map<Int, Double>): JSONObject = JSONObject().also { o -> m.toSortedMap().forEach { (y, v) -> o.put(y.toString(), Math.round(v * 10000.0) / 10000.0) } }

    /** Fetches every declared source and returns the snapshot JSON (the data/cb-snapshot.json schema). */
    fun run(): String {
        val pairs = decl.countries.flatMap { c -> decl.indicators.values.flatMap { i -> i.rules.filter { it.covers(c.id) }.flatMap { r -> urls(r, c) } } }.distinctBy { it.first }
        val pool = Executors.newFixedThreadPool(6)
        pairs.forEach { (u, ua) -> pool.execute { text(u, ua) } }
        pool.shutdown(); pool.awaitTermination(10, TimeUnit.MINUTES)
        val countries = JSONObject()
        for (c in decl.countries) {
            val series = JSONObject()
            for (ind in decl.indicators.values) {
                var main: JSONObject? = null
                val proj = JSONArray()
                for ((n, r) in ind.rules.withIndex()) {
                    if (!r.covers(c.id)) continue
                    val got = fetch(r, c) ?: continue
                    if (r.projection) {
                        val fc = got.annual.filterKeys { it in currentYear..fcTo }
                        if (fc.isNotEmpty()) proj.put(JSONObject().put("source", r.label.ifEmpty { decl.sources.getValue(r.src).label }).put("release", got.release).put("vintage", today).put("label", r.label.ifEmpty { decl.sources.getValue(r.src).label }).put("rule", n).put("fc", years(fc)))
                        continue
                    }
                    if (main != null) continue
                    val hist = got.annual.filterKeys { it in histFrom until currentYear }
                    val fc = got.annual.filterKeys { it in currentYear..fcTo }
                    if (hist.isEmpty() && fc.isEmpty() && got.ytd == null) continue
                    main = JSONObject().put("source", decl.sources.getValue(r.src).label).put("release", got.release).put("vintage", today).put("rule", n).put("unit", r.unit.ifEmpty { ind.unit }).put("hist", years(hist)).put("fc", years(fc))
                    if (r.note.isNotEmpty()) main.put("note", r.note)
                    val y = got.ytd
                    if (y != null && y.year >= currentYear && !fc.containsKey(currentYear)) main.put("ytd", JSONObject().put("year", y.year).put("value", Math.round(y.value * 10000.0) / 10000.0).put("through", y.through))
                }
                if (main != null) { if (proj.length() > 0) main.put("cb_projection", proj); series.put(ind.id, main) }
            }
            countries.put(c.id, JSONObject().put("series", series))
        }
        val weights = JSONObject()
        for (opt in decl.weights) {
            val data = text(imfUrl(opt.series), src("imf").userAgent)?.let { CbSources.imf(it, opt.series) }.orEmpty()
            val w = JSONObject()
            for (c in decl.countries) {
                if (c.weightMembers.isNotEmpty()) {
                    val ys = c.weightMembers.flatMap { m -> data[m].orEmpty().keys }.filter { it in histFrom..fcTo }.distinct()
                    w.put(c.id, years(ys.associateWith { y -> c.weightMembers.sumOf { m -> data[m]?.get(y) ?: 0.0 } }))
                } else data[c.iso3]?.let { d -> w.put(c.id, years(d.filterKeys { it in histFrom..fcTo })) }
            }
            weights.put(opt.id, w)
        }
        return JSONObject().put("generated", today).put("current_year", currentYear).put("history_from", histFrom).put("forecast_to", fcTo).put("weights", weights).put("countries", countries).toString()
    }
}

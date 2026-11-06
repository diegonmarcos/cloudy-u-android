package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject

/** #1539 one country of the CB page (data/cb-countries.json): Kotlin names none. [aggregate] false = a euro-area member already inside the Euro Area. */
data class CbCountry(
    val id: String, val name: String, val group: String, val aggregate: Boolean, val explicit: Boolean,
    val iso3: String, val wb: String, val bis: String, val oecd: String?, val weightMembers: List<String>, val target: Double?, val note: String,
)

/** One source rule of an indicator (data/cb-indicators.json `sources`): the first rule with data for a country supplies it; [role] cb_projection adds the bank's own projection. */
data class CbRule(
    val src: String, val series: String, val countries: List<String>?, val transform: String, val agg: String, val scale: Double,
    val note: String, val role: String, val label: String, val ref: String, val key: String, val unit: String,
) {
    fun covers(country: String): Boolean = countries == null || country in countries
    val projection: Boolean get() = role == "cb_projection"
}

data class CbIndicator(val id: String, val group: String, val name: String, val unit: String, val frequency: String, val modelOnly: Boolean, val rules: List<CbRule>)
data class CbGroup(val id: String, val label: String, val items: List<CbIndicator>)
data class CbSimpleItem(val id: String, val name: String, val refs: List<String>)
data class CbSimpleGroup(val id: String, val label: String, val items: List<CbSimpleItem>)
data class CbWeight(val id: String, val label: String, val series: String)
data class CbSource(val id: String, val label: String, val url: String, val userAgent: String)

/** The Taylor-rule parameters of data/cb-countries.json `model.taylor` and the page's own words (`ui`). */
data class CbModelDecl(val inflationWeight: Double, val gapWeight: Double, val holdBandPp: Double, val neutral: String, val inflation: String, val gap: String, val policy: String, val predictions: List<String>)

/** Everything the CB page declares (cb-countries.json + cb-indicators.json), decoded once. */
data class CbDecl(
    val countries: List<CbCountry>, val weights: List<CbWeight>, val defaultWeight: String, val historyYears: Int, val forecastYears: Int,
    val simple: List<CbSimpleGroup>, val full: List<CbGroup>, val sources: Map<String, CbSource>, val model: CbModelDecl, val ui: Map<String, String>,
) {
    val indicators: Map<String, CbIndicator> by lazy { full.flatMap { it.items }.associateBy { it.id } }
    fun word(key: String): String = ui[key].orEmpty()

    companion object {
        private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

        fun parse(countriesJson: String, indicatorsJson: String): CbDecl {
            val c = JSONObject(countriesJson)
            val i = JSONObject(indicatorsJson)
            val cs = c.getJSONArray("countries")
            val countries = (0 until cs.length()).map { cs.getJSONObject(it) }.map { o ->
                CbCountry(
                    o.getString("id"), o.getString("name"), o.getString("group"), o.optBoolean("aggregate", true), o.optBoolean("explicit", false),
                    o.getString("iso3"), o.getString("wb"), o.getString("bis"), o.optString("oecd").takeIf { it.isNotEmpty() && !o.isNull("oecd") },
                    strs(o.optJSONArray("weight_members")), if (o.has("inflation_target")) o.getDouble("inflation_target") else null, o.optString("note"),
                )
            }
            val wo = c.getJSONObject("weights")
            val wa = wo.getJSONArray("options")
            val weights = (0 until wa.length()).map { wa.getJSONObject(it) }.map { CbWeight(it.getString("id"), it.getString("label"), it.getString("series")) }
            val so = i.getJSONObject("sources")
            val sources = so.keys().asSequence().associateWith { k -> so.getJSONObject(k).let { CbSource(k, it.getString("label"), it.optString("url"), it.optString("user_agent")) } }
            val fg = i.getJSONObject("full").getJSONArray("groups")
            val full = (0 until fg.length()).map { fg.getJSONObject(it) }.map { g ->
                val items = g.getJSONArray("items")
                CbGroup(g.getString("id"), g.getString("label"), (0 until items.length()).map { items.getJSONObject(it) }.map { o ->
                    val ra = o.getJSONArray("sources")
                    CbIndicator(o.getString("id"), g.getString("id"), o.getString("name"), o.optString("unit"), o.optString("frequency"), o.optBoolean("model_only", false),
                        (0 until ra.length()).map { ra.getJSONObject(it) }.map { r ->
                            CbRule(r.getString("src"), r.getString("series"), r.opt("countries").let { cc -> if (cc is JSONArray) strs(cc) else null },
                                r.optString("transform"), r.optString("agg", "avg"), r.optDouble("scale", 1.0), r.optString("note"), r.optString("role"), r.optString("label"),
                                r.optString("ref"), r.optString("key"), r.optString("unit"))
                        })
                })
            }
            val sg = i.getJSONObject("simple").getJSONArray("groups")
            val simple = (0 until sg.length()).map { sg.getJSONObject(it) }.map { g ->
                val items = g.getJSONArray("items")
                CbSimpleGroup(g.getString("id"), g.getString("label"), (0 until items.length()).map { items.getJSONObject(it) }.map { CbSimpleItem(it.getString("id"), it.getString("name"), strs(it.getJSONArray("refs"))) })
            }
            val t = c.getJSONObject("model").getJSONObject("taylor")
            val ui = c.getJSONObject("ui")
            return CbDecl(
                countries, weights, wo.getString("default"), c.getJSONObject("years").getInt("history"), c.getJSONObject("years").getInt("forecast"), simple, full, sources,
                CbModelDecl(t.getDouble("inflation_weight"), t.getDouble("gap_weight"), t.getDouble("hold_band_pp"), t.getString("neutral"), t.getString("inflation"), t.getString("gap"), t.getString("policy"),
                    strs(c.getJSONObject("model").getJSONArray("predictions"))),
                ui.keys().asSequence().associateWith { ui.getString(it) },
            )
        }
    }
}

/** The bank's own projection beside a series (Fed SEP median, ECB staff projections). */
data class CbProjection(val label: String, val release: String, val vintage: String, val fc: Map<Int, Double>)

/** The current-year-to-date value of a sub-annual source (the year is not finished, so it is not a forecast and not an actual). */
data class CbYtd(val year: Int, val value: Double, val through: String)

/** One indicator of one country: its values with the source, release and vintage they carry. */
data class CbSeries(
    val source: String, val release: String, val vintage: String, val unit: String, val note: String,
    val hist: Map<Int, Double>, val fc: Map<Int, Double>, val ytd: CbYtd?, val projections: List<CbProjection>,
) {
    /** The value of [year]: an actual, a forecast, else the year to date when [year] is the year in progress; null = no value (n/a). */
    fun at(year: Int): Double? = hist[year] ?: fc[year] ?: ytd?.takeIf { it.year == year }?.value
    fun isForecast(year: Int): Boolean = year !in hist && year in fc
    /** The latest actual: the year to date, else the last history year. */
    fun latest(): Pair<String, Double>? = ytd?.let { "${it.through}" to it.value } ?: hist.keys.maxOrNull()?.let { it.toString() to hist.getValue(it) }
}

/** The snapshot (data/cb-snapshot.json, or a refreshed copy): series per country and the GDP weights per option. */
data class CbSnapshot(
    val generated: String, val currentYear: Int, val historyFrom: Int, val forecastTo: Int,
    val weights: Map<String, Map<String, Map<Int, Double>>>, val series: Map<String, Map<String, CbSeries>>,
) {
    fun of(country: String, indicator: String): CbSeries? = series[country]?.get(indicator)
    fun weight(option: String, country: String, year: Int): Double? = weights[option]?.get(country)?.get(year)

    companion object {
        private fun years(o: JSONObject?): Map<Int, Double> = if (o == null) emptyMap() else o.keys().asSequence().associate { it.toInt() to o.getDouble(it) }

        fun parse(json: String): CbSnapshot {
            val r = JSONObject(json)
            val w = r.getJSONObject("weights")
            val weights = w.keys().asSequence().filter { !it.endsWith("_members") }.associateWith { opt ->
                val o = w.getJSONObject(opt); o.keys().asSequence().associateWith { years(o.getJSONObject(it)) }
            }
            val cs = r.getJSONObject("countries")
            val series = cs.keys().asSequence().associateWith { c ->
                val s = cs.getJSONObject(c).getJSONObject("series")
                s.keys().asSequence().associateWith { k ->
                    val o = s.getJSONObject(k)
                    val pr = o.optJSONArray("cb_projection")
                    CbSeries(
                        o.getString("source"), o.getString("release"), o.getString("vintage"), o.optString("unit"), o.optString("note"), years(o.optJSONObject("hist")), years(o.optJSONObject("fc")),
                        o.optJSONObject("ytd")?.let { CbYtd(it.getInt("year"), it.getDouble("value"), it.optString("through")) },
                        if (pr == null) emptyList() else (0 until pr.length()).map { pr.getJSONObject(it) }.map { CbProjection(it.getString("label"), it.getString("release"), it.getString("vintage"), years(it.getJSONObject("fc"))) },
                    )
                }
            }
            return CbSnapshot(r.getString("generated"), r.getInt("current_year"), r.getInt("history_from"), r.getInt("forecast_to"), weights, series)
        }
    }
}

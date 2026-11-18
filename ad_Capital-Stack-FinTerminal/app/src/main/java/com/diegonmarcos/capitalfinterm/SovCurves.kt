package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject

/** #1786 one point of a sovereign curve: the yield (null = the route has no figure), the day it is for and who published it. */
data class SovPoint(val value: Double?, val date: String, val source: String)

/** #1786 what the route says about one country's curve: its publisher, the licence, when it was fetched, whether it is stale and which tenors it lacks. */
data class SovMeta(val source: String, val name: String, val licence: String, val fetchedAt: String, val stale: Boolean, val missingTenors: List<String>)

/**
 * #1786 the FinTerm API sovereign curves (data/cb-indicators.json source `finterm_sov`, GET /v1/rates/sovereign-curves?countries=..&tenors=..):
 * {as_of, generated_at, tenors, curves:{CC:{tenor:{value,date,source}}}, unavailable:{CC:reason}, meta:{CC:{source,name,licence,fetched_at,stale,missing_tenors}}}.
 * Tenors are keyed by their years ("2", "10"); "2Y" / "2y" read the same.
 */
data class SovCurves(
    val asOf: String, val generatedAt: String, val tenors: List<String>,
    val curves: Map<String, Map<String, SovPoint>>, val unavailable: Map<String, String>, val meta: Map<String, SovMeta>,
) {
    /** The point of [country] at [tenor], or null when the route has none. */
    fun point(country: String, tenor: String): SovPoint? = curves[country]?.get(SovCurvesApi.tenorKey(tenor))
    fun stale(country: String): Boolean = meta[country]?.stale == true
}

/** #1786 the route's URL, its parser and how a CB Homepage cell reads it. Pure: no network here (SovCurvesStore asks it). */
object SovCurvesApi {
    /** "2Y", "2y", " 2 " -> "2". */
    fun tenorKey(t: String): String = t.trim().trimEnd('y', 'Y')

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }

    /** The live rules of [indicators] (their source is declared `live`), in declaration order. */
    fun liveRules(decl: CbDecl, indicators: List<String>): List<CbRule> =
        indicators.distinct().mapNotNull { decl.indicators[it] }.flatMap { i -> i.rules.filter { decl.sources[it.src]?.live == true } }

    /** The path the route is asked at for [rules] of [source]: its declared `url` with the countries and tenors of the rules, each once, in order. */
    fun path(source: CbSource, rules: List<CbRule>): String {
        val mine = rules.filter { it.src == source.id }
        val countries = mine.flatMap { it.countries.orEmpty() }.distinct()
        val tenors = mine.map { tenorKey(it.series) }.distinct()
        return source.url.replace("{countries}", countries.joinToString(",")).replace("{tenors}", tenors.joinToString(","))
    }

    /** Parse a route body; null when it is not the route's shape (no `curves` and no `unavailable`). A malformed point is skipped, never invented. */
    fun parse(json: String): SovCurves? = runCatching {
        val o = JSONObject(json)
        if (!o.has("curves") && !o.has("unavailable")) return null
        val cv = o.optJSONObject("curves") ?: JSONObject()
        val curves = LinkedHashMap<String, Map<String, SovPoint>>()
        for (c in cv.keys()) {
            val t = cv.optJSONObject(c) ?: continue
            val pts = LinkedHashMap<String, SovPoint>()
            for (k in t.keys()) {
                val p = t.optJSONObject(k) ?: continue
                val v = if (p.isNull("value")) null else p.optDouble("value").takeIf { it.isFinite() }
                pts[tenorKey(k)] = SovPoint(v, if (p.isNull("date")) "" else p.optString("date"), if (p.isNull("source")) "" else p.optString("source"))
            }
            curves[c] = pts
        }
        val un = o.optJSONObject("unavailable") ?: JSONObject()
        val unavailable = un.keys().asSequence().associateWith { un.optString(it) }
        val mo = o.optJSONObject("meta") ?: JSONObject()
        val meta = mo.keys().asSequence().mapNotNull { k -> mo.optJSONObject(k)?.let { m ->
            k to SovMeta(m.optString("source"), m.optString("name"), m.optString("licence"), m.optString("fetched_at"), m.optBoolean("stale", false), strs(m.optJSONArray("missing_tenors")).map(::tenorKey))
        } }.toMap()
        val tn = o.opt("tenors")
        val tenors = if (tn is JSONArray) strs(tn).map(::tenorKey) else emptyList()
        SovCurves(o.optString("as_of"), o.optString("generated_at"), tenors, curves, unavailable, meta)
    }.getOrNull()

    /** The live cell of [country] for [indicator]: the first live rule covering it whose tenor the route answered with a figure; else null (the snapshot rules). */
    fun cell(decl: CbDecl, curves: SovCurves?, country: String, indicator: String): CbCell? {
        curves ?: return null
        val rule = decl.indicators[indicator]?.rules?.firstOrNull { decl.sources[it.src]?.live == true && it.covers(country) } ?: return null
        val p = curves.point(country, rule.series) ?: return null
        val v = p.value ?: return null
        val m = curves.meta[country]
        return CbCell(v, p.date, p.source.ifEmpty { m?.name.orEmpty() }.ifEmpty { m?.source.orEmpty() }, curves.stale(country), live = true)
    }

    /** Whether any live rule of [indicators] covers [country] (its row says what the route answered). */
    fun covers(decl: CbDecl, indicators: List<String>, country: String): Boolean = liveRules(decl, indicators).any { it.covers(country) }

    /**
     * The status line of a live-covered country: the reason the route gives when it is unavailable, "stale" with the fetch time when its curve is stale,
     * the tenors it lacks; [unreached] (the route did not answer: off the mesh, an error) when there is no answer at all; null = nothing to say.
     */
    fun status(curves: SovCurves?, country: String, unreached: String, staleWord: String, missingWord: String): String? {
        if (curves == null) return unreached.ifEmpty { null }
        curves.unavailable[country]?.let { return it.ifEmpty { unreached } }
        val m = curves.meta[country]
        val parts = mutableListOf<String>()
        if (m?.stale == true) parts += staleWord + (m.fetchedAt.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "")
        val missing = m?.missingTenors.orEmpty().ifEmpty { curves.tenors.filter { curves.point(country, it)?.value == null } }
        if (missing.isNotEmpty()) parts += missingWord + " " + missing.joinToString(", ") { it + "Y" }
        return parts.joinToString("  ·  ").ifEmpty { null }
    }
}

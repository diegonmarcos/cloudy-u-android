package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject

/** (year, month) of a sub-annual observation. */
typealias YM = Pair<Int, Int>

/**
 * #1539 the parsers of the CB page's sources, one per API shape (the response fixtures of app/src/test/resources/cb are their tests), and the annualisation of a
 * sub-annual series. Pure: no network, no clock. scripts/gen-cb-snapshot.py holds the same logic for the baked snapshot.
 */
object CbSources {
    /** A CSV text as records keyed by the header (quoted fields with commas and doubled quotes are honoured). */
    fun records(text: String): List<Map<String, String>> {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        val head = split(lines.first())
        return lines.drop(1).map { l -> val f = split(l); head.indices.associate { head[it] to f.getOrElse(it) { "" } } }
    }

    fun split(line: String): List<String> {
        val out = ArrayList<String>(); val b = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { b.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += b.toString(); b.setLength(0) }
                else -> b.append(c)
            }
            i++
        }
        out += b.toString()
        return out
    }

    /** '2025-03' | '2025-Q4' | '2025-03-31' | '2025' -> (year, month). */
    fun period(p: String): YM {
        val y = p.substring(0, 4).toInt()
        val rest = if (p.length > 5) p.substring(5) else ""
        return when {
            rest.isEmpty() -> y to 12
            rest.startsWith("Q") -> y to rest.substring(1, 2).toInt() * 3
            else -> y to rest.substring(0, 2).toInt()
        }
    }

    /** IMF DataMapper {"values": {SERIES: {ISO3: {"2024": 1.2}}}}: ISO3 -> year -> value. */
    fun imf(json: String, series: String): Map<String, Map<Int, Double>> {
        val v = JSONObject(json).optJSONObject("values")?.optJSONObject(series) ?: return emptyMap()
        return v.keys().asSequence().mapNotNull { c ->
            val ys = v.optJSONObject(c) ?: return@mapNotNull null
            c to ys.keys().asSequence().filter { !ys.isNull(it) }.associate { it.toInt() to ys.getDouble(it) }
        }.toMap()
    }

    /** OECD Economic Outlook SDMX csvfilewithlabels: (area, measure) -> year -> value, and the release (STRUCTURE_NAME). */
    fun oecd(text: String): Pair<Map<Pair<String, String>, Map<Int, Double>>, String> {
        val out = HashMap<Pair<String, String>, MutableMap<Int, Double>>(); var rel = ""
        for (r in records(text)) {
            val v = r["OBS_VALUE"]?.toDoubleOrNull() ?: continue
            rel = r["STRUCTURE_NAME"].orEmpty().ifEmpty { rel }
            out.getOrPut(r.getValue("REF_AREA") to r.getValue("MEASURE")) { HashMap() }[r.getValue("TIME_PERIOD").substring(0, 4).toInt()] = v
        }
        return out to rel
    }

    /** World Bank v2 JSON [meta, rows]: ISO3 -> year -> value, and meta.lastupdated. */
    fun worldBank(text: String): Pair<Map<String, Map<Int, Double>>, String> {
        val a = runCatching { JSONArray(text) }.getOrNull() ?: return emptyMap<String, Map<Int, Double>>() to ""
        if (a.length() < 2 || a.isNull(1)) return emptyMap<String, Map<Int, Double>>() to ""
        val out = HashMap<String, MutableMap<Int, Double>>()
        val rows = a.getJSONArray(1)
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            if (o.isNull("value")) continue
            out.getOrPut(o.getString("countryiso3code")) { HashMap() }[o.getString("date").toInt()] = o.getDouble("value")
        }
        return out to a.getJSONObject(0).optString("lastupdated")
    }

    /** BIS SDMX csv: REF_AREA -> (year, month) -> value (WS_CBPOL monthly, WS_TC quarterly). */
    fun bis(text: String): Map<String, Map<YM, Double>> {
        val out = HashMap<String, MutableMap<YM, Double>>()
        for (r in records(text)) {
            val v = r["OBS_VALUE"]?.toDoubleOrNull() ?: continue
            val area = r["REF_AREA"] ?: continue
            out.getOrPut(area) { HashMap() }[period(r.getValue("TIME_PERIOD"))] = v
        }
        return out
    }

    /** fredgraph.csv: (year, month) -> observations of the month; '.' (a missing day) and anything that is not a FRED csv are skipped. */
    fun fred(text: String): Map<YM, List<Double>> {
        val lines = text.lines().filter { it.isNotEmpty() }
        if (lines.isEmpty() || !(lines.first().startsWith("observation_date") || lines.first().startsWith("DATE"))) return emptyMap()
        val out = HashMap<YM, MutableList<Double>>()
        for (l in lines.drop(1)) {
            val f = l.split(',')
            val v = f.getOrNull(1)?.toDoubleOrNull() ?: continue
            out.getOrPut(period(f[0].take(10))) { ArrayList() }.add(v)
        }
        return out
    }

    /** ECB data-api csvdata (staff projections): year -> value, and the series TITLE when the answer carries one. */
    fun ecb(text: String): Pair<Map<Int, Double>, String> {
        val out = HashMap<Int, Double>(); var title = ""
        for (r in records(text)) {
            val v = r["OBS_VALUE"]?.toDoubleOrNull() ?: continue
            out[r.getValue("TIME_PERIOD").substring(0, 4).toInt()] = v
            title = r["TITLE"].orEmpty().ifEmpty { title }
        }
        return out to title
    }

    /** Percent change of an annual series over the year before (null where either year is missing or the base is 0). */
    fun yoy(a: Map<Int, Double>): Map<Int, Double> =
        a.keys.sorted().mapNotNull { y -> val p = a[y - 1]; if (p != null && p != 0.0) y to (a.getValue(y) / p - 1.0) * 100.0 else null }.toMap()

    /**
     * A sub-annual series as years: the average ([agg] avg) or last observation ([agg] last) of each COMPLETE year (every year before the latest observed one), percent change when
     * [transform] is yoy; and the year in progress as a year-to-date value (the same months of the year before for yoy).
     */
    fun annualise(months: Map<YM, List<Double>>, agg: String, transform: String): Pair<Map<Int, Double>, CbYtd?> {
        if (months.isEmpty()) return emptyMap<Int, Double>() to null
        val last = months.keys.maxWithOrNull(compareBy({ it.first }, { it.second }))!!
        val byYear = months.entries.groupBy({ it.key.first }, { it.key.second to it.value })
        fun level(y: Int, ms: Set<Int>): Double? {
            val ym = byYear[y]?.filter { it.first in ms } ?: return null
            val vals = ym.flatMap { it.second }
            if (vals.isEmpty()) return null
            return if (agg == "last") ym.maxByOrNull { it.first }!!.second.last() else vals.average()
        }
        val all = (1..12).toSet()
        var annual = byYear.keys.sorted().filter { it < last.first }.mapNotNull { y -> level(y, all)?.let { y to it } }.toMap()
        if (transform == "yoy") annual = yoy(annual)
        val ms = byYear.getValue(last.first).map { it.first }.toSet()
        var v = level(last.first, ms)
        if (v != null && transform == "yoy") { val p = level(last.first - 1, ms); v = if (p != null && p != 0.0) (v / p - 1.0) * 100.0 else null }
        return annual to v?.let { CbYtd(last.first, it, String.format(java.util.Locale.ROOT, "%04d-%02d", last.first, last.second)) }
    }
}

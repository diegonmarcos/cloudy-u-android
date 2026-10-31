package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject

/** One DBnomics series: its code, name and aligned period / value lists (a value that is not a number, such as "NA", is null). */
data class SeriesData(val code: String, val name: String, val periods: List<String>, val values: List<Double?>) {
    /** The last period that has a value, with that value and the one before it (null when there is none). */
    fun latest(): Triple<String, Double, Double?>? {
        val idx = values.indices.filter { values[it] != null }
        val last = idx.lastOrNull() ?: return null
        val prev = idx.getOrNull(idx.size - 2)?.let { values[it] }
        return Triple(periods.getOrElse(last) { "" }, values[last] ?: return null, prev)
    }
}

/** A value for a country in a year (World Bank, IMF). */
data class CountryValue(val code: String, val name: String, val year: String, val value: Double)

/** A news article (GDELT). */
data class Article(val title: String, val url: String, val domain: String, val seen: String)

/** The parsers of the live providers' answers. Each answers an empty list for anything it does not recognise. */
object Providers {
    /** The connectors Kotlin implements: data/sources.json `connector` names one of these or is empty. From nyfed_rates on they are parsed by Feeds.kt into the normalised model. */
    val CONNECTORS: Set<String> = setOf("fred_series", "dbnomics_series", "imf_datamapper", "worldbank_indicator", "gdelt_artlist", "fincept_topic", "nyfed_rates", "treasury_xml", "sdmx_json", "jsonstat", "bls_series", "ics_calendar", "ff_calendar", "declared_events", "polymarket_events", "frankfurter_rates", "coinbase_exchange", "cftc_cot", "cboe_quotes", "sec_submissions", "sec_concept", "nasdaq_earnings", "finterm_v1")

    /** DBnomics `series/<provider>/<dataset>` answer: series.docs[] of series_code, series_name, period[], value[]. */
    fun dbnomics(json: String): List<SeriesData> {
        val docs = runCatching { JSONObject(json).getJSONObject("series").getJSONArray("docs") }.getOrNull() ?: return emptyList()
        return (0 until docs.length()).mapNotNull { docs.optJSONObject(it) }.map { d ->
            val periods = strings(d.optJSONArray("period"))
            val vals = d.optJSONArray("value")
            SeriesData(Catalog.str(d, "series_code"), Catalog.str(d, "series_name"), periods,
                (0 until (vals?.length() ?: 0)).map { i -> (vals?.opt(i) as? Number)?.toDouble()?.takeIf { it.isFinite() } })
        }
    }

    /** World Bank indicator answer: [meta, [{countryiso3code, country{value}, date, value}]]; rows without a value are dropped. */
    fun worldBank(json: String): List<CountryValue> {
        val rows = runCatching { JSONArray(json).getJSONArray(1) }.getOrNull() ?: return emptyList()
        return (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }.mapNotNull { r ->
            if (r.isNull("value")) return@mapNotNull null
            val v = r.optDouble("value", Double.NaN)
            if (!v.isFinite()) return@mapNotNull null
            CountryValue(Catalog.str(r, "countryiso3code"), r.optJSONObject("country")?.let { Catalog.str(it, "value") }.orEmpty(), Catalog.str(r, "date"), v)
        }
    }

    /** IMF DataMapper answer: values.<indicator>.<country>.<year>; the latest year with a value, for each of [countries] in order. */
    fun imf(json: String, indicator: String, countries: List<String>, names: Map<String, String>): List<CountryValue> {
        val byCountry = runCatching { JSONObject(json).getJSONObject("values").getJSONObject(indicator) }.getOrNull() ?: return emptyList()
        return countries.mapNotNull { c ->
            val years = byCountry.optJSONObject(c) ?: return@mapNotNull null
            val best = years.keys().asSequence().filter { (years.opt(it) as? Number) != null }.maxOrNull() ?: return@mapNotNull null
            CountryValue(c, names[c] ?: c, best, (years.opt(best) as Number).toDouble())
        }
    }

    /** GDELT DOC artlist answer: articles[] of title, url, domain, seendate (20261030T120000Z). */
    fun gdelt(json: String): List<Article> {
        val arr = runCatching { JSONObject(json).getJSONArray("articles") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { a ->
            Article(Catalog.str(a, "title"), Catalog.str(a, "url"), Catalog.str(a, "domain"), seen(Catalog.str(a, "seendate")))
        }.filter { it.title.isNotBlank() }
    }

    /** A DataHub topic of the Fincept backend's FRED connector: {series_id, observations:[{date, value}]}; a null value is dropped. */
    fun finceptSeries(json: String): List<Obs> {
        val arr = runCatching { JSONObject(json).getJSONArray("observations") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val v = (o.opt("value") as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            Obs(Catalog.str(o, "date"), v)
        }
    }

    /** 20261030T120000Z to "2026-10-30 12:00"; anything else is kept as it came. */
    fun seen(raw: String): String {
        val m = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})\d{2}Z$""").find(raw) ?: return raw
        val (y, mo, d, h, mi) = m.destructured
        return "$y-$mo-$d $h:$mi"
    }

    private fun strings(arr: JSONArray?): List<String> = if (arr == null) emptyList() else (0 until arr.length()).map { arr.optString(it) }
}

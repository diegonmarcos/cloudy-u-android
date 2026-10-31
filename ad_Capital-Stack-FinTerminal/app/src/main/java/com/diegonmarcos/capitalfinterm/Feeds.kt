package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One value of a series, a quote or a curve tenor: [key] is what the source calls it, [x] its place on a curve, [prev] the value before. */
/** [hist]: recent values, oldest first, for a row sparkline (empty: none). */
data class Pt(val key: String, val label: String, val x: Double, val value: Double, val prev: Double?, val date: String, val hist: List<Double> = emptyList())

/**
 * One calendar event (economic release, earnings report, central bank decision). [ts] is UTC: "YYYY-MM-DDTHH:MMZ", or "YYYY-MM-DD" for an
 * all-day event. Values stay the text the source published (units differ per event).
 */
data class Ev(
    val ts: String, val country: String, val title: String, val importance: Int,
    val consensus: String = "", val actual: String = "", val previous: String = "", val kind: String = "economic",
)

/** One daily bar. */
data class Bar(val date: String, val o: Double, val h: Double, val l: Double, val c: Double)

/** What a connector's answer becomes: the normalised model (design 3.2) every derive renders. */
data class Got(
    val points: List<Pt> = emptyList(),
    val events: List<Ev> = emptyList(),
    val bars: List<Bar> = emptyList(),
    val kv: List<Pair<String, String>> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val links: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = points.isEmpty() && events.isEmpty() && bars.isEmpty() && kv.isEmpty() && rows.isEmpty()

    operator fun plus(o: Got): Got = Got(points + o.points, events + o.events, bars + o.bars, kv + o.kv, rows + o.rows, links + o.links)
}

/**
 * The parsers of the model connectors (data/sources.json `connector`). Each is pure: an answer's text and the panel's declared options in,
 * a [Got] out; anything it does not recognise parses to an empty [Got]. [each] is the item a per-item request was made for.
 */
object Feeds {
    /** Options a parser reads from the panel's fetch (and its route): key_dim, scale, last, forms, importance; [paths] are the source's declared paths. */
    class Opt(val spec: JSONObject, val each: String = "", val paths: Map<String, String> = emptyMap())

    fun parse(connector: String, body: String, o: Opt): Got = runCatching {
        when (connector) {
            "nyfed_rates" -> nyfed(body)
            "treasury_xml" -> treasury(body)
            "sdmx_json" -> sdmx(body, Catalog.str(o.spec, "key_dim"))
            "jsonstat" -> jsonstat(body, Catalog.str(o.spec, "key_dim"))
            "bls_series" -> bls(body)
            "ics_calendar" -> ics(body, o.spec.optJSONObject("importance"))
            "ff_calendar" -> ff(body)
            "declared_events" -> declared(body)
            "polymarket_events" -> polymarket(body, o.spec.optInt("last", 12))
            "frankfurter_rates" -> frankfurter(body)
            "coinbase_exchange" -> coinbase(body, o.each)
            "cboe_quotes" -> cboe(body, o.each, o.spec.optInt("last", 252))
            "sec_submissions" -> sec(body, o)
            "sec_concept" -> secConcept(body, o.spec.optDouble("scale", 1.0), o.spec.optInt("last", 6))
            "nasdaq_earnings" -> nasdaq(body, o.each)
            "finterm_v1" -> v1(body, Catalog.str(o.spec, "concept"))
            else -> Got()
        }
    }.getOrDefault(Got())

    // ------------------------------------------------------------------ rates and curves

    /** NY Fed `rates/all/latest.json`: refRates[] of type + percentRate; SOFRAI carries the 30/90/180-day averages and the index. */
    fun nyfed(body: String): Got {
        val arr = JSONObject(body).getJSONArray("refRates")
        val out = ArrayList<Pt>()
        objects(arr).forEach { r ->
            val type = Catalog.str(r, "type")
            val date = Catalog.str(r, "effectiveDate")
            if (type == "SOFRAI") {
                listOf("SOFRAI_30D" to "average30day", "SOFRAI_90D" to "average90day", "SOFRAI_180D" to "average180day", "SOFRAI_INDEX" to "index").forEach { (k, f) ->
                    num(r, f)?.let { out += Pt(k, k, out.size.toDouble(), it, null, date) }
                }
            } else {
                num(r, "percentRate")?.let { out += Pt(type, type, out.size.toDouble(), it, null, date) }
            }
        }
        return Got(points = out)
    }

    /** Treasury par yield curve XML (OData feed): the last two days of the month; one point per BC_* tenor, the day before as prev. */
    fun treasury(body: String): Got {
        val days = Regex("<m:properties>(.*?)</m:properties>", RegexOption.DOT_MATCHES_ALL).findAll(body).mapNotNull { m ->
            val b = m.groupValues[1]
            val date = Regex("<d:NEW_DATE[^>]*>(\\d{4}-\\d{2}-\\d{2})").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
            val vals = LinkedHashMap<String, Double>()
            Regex("<d:(BC_[0-9A-Z_]+) [^>]*>(-?[0-9.]+)</d:").findAll(b).forEach { v -> v.groupValues[2].toDoubleOrNull()?.let { vals[v.groupValues[1]] = it } }
            if (vals.isEmpty()) null else date to vals
        }.sortedBy { it.first }.toList()
        val last = days.lastOrNull() ?: return Got()
        val prev = days.getOrNull(days.size - 2)?.second
        return Got(points = last.second.entries.mapIndexed { i, (k, v) -> Pt(k, k, i.toDouble(), v, prev?.get(k), last.first) })
    }

    /** SDMX-JSON 1.0 (ECB Data Portal, OECD): one point per series, named by the id of its [keyDim] dimension; last and previous observation. */
    fun sdmx(body: String, keyDim: String): Got {
        val top = JSONObject(body)
        val root = top.optJSONObject("data") ?: top
        val dims = root.getJSONObject("structure").getJSONObject("dimensions")
        val seriesDims = dims.getJSONArray("series")
        val k = (0 until seriesDims.length()).firstOrNull { Catalog.str(seriesDims.getJSONObject(it), "id") == keyDim } ?: return Got()
        val keyValues = seriesDims.getJSONObject(k).getJSONArray("values")
        val times = dims.getJSONArray("observation").getJSONObject(0).getJSONArray("values")
        val series = root.getJSONArray("dataSets").getJSONObject(0).getJSONObject("series")
        val out = ArrayList<Pt>()
        series.keys().asSequence().sorted().forEach { sk ->
            val idx = sk.split(":").getOrNull(k)?.toIntOrNull() ?: return@forEach
            val id = Catalog.str(keyValues.getJSONObject(idx), "id")
            val obs = series.getJSONObject(sk).getJSONObject("observations")
            val pairs = obs.keys().asSequence().mapNotNull { oi ->
                val t = times.optJSONObject(oi.toInt())?.let { Catalog.str(it, "id") } ?: return@mapNotNull null
                val v = (obs.getJSONArray(oi).opt(0) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
                t to v
            }.sortedBy { it.first }.toList()
            val last = pairs.lastOrNull() ?: return@forEach
            out += Pt(id, id, out.size.toDouble(), last.second, pairs.getOrNull(pairs.size - 2)?.second, last.first)
        }
        return Got(points = out)
    }

    /** JSON-stat 2.0 (Eurostat): one point per value of [keyDim], over the `time` dimension; last and previous. */
    fun jsonstat(body: String, keyDim: String): Got {
        val o = JSONObject(body)
        val ids = strings(o.getJSONArray("id"))
        val size = o.getJSONArray("size").let { a -> (0 until a.length()).map { a.getInt(it) } }
        val dim = o.getJSONObject("dimension")
        fun posToId(d: String): Map<Int, String> {
            val idx = dim.getJSONObject(d).getJSONObject("category").opt("index")
            val m = HashMap<Int, String>()
            when (idx) {
                is JSONObject -> idx.keys().forEach { m[idx.getInt(it)] = it }
                is JSONArray -> (0 until idx.length()).forEach { m[it] = idx.getString(it) }
            }
            return m
        }
        val ki = ids.indexOf(keyDim)
        val ti = ids.indexOf("time")
        if (ki < 0 || ti < 0) return Got()
        val keyIds = posToId(keyDim)
        val timeIds = posToId("time")
        val values = o.opt("value")
        val flat = HashMap<Int, Double>()
        when (values) {
            is JSONObject -> values.keys().forEach { k -> (values.opt(k) as? Number)?.toDouble()?.let { flat[k.toInt()] = it } }
            is JSONArray -> (0 until values.length()).forEach { i -> (values.opt(i) as? Number)?.toDouble()?.let { flat[i] = it } }
        }
        val byKey = LinkedHashMap<String, MutableList<Pair<String, Double>>>()
        keyIds.keys.sorted().forEach { byKey[keyIds.getValue(it)] = ArrayList() }
        flat.forEach { (flatIndex, v) ->
            var rest = flatIndex
            val coord = IntArray(size.size)
            for (d in size.indices.reversed()) {
                coord[d] = rest % size[d]
                rest /= size[d]
            }
            val key = keyIds[coord[ki]] ?: return@forEach
            val t = timeIds[coord[ti]] ?: return@forEach
            byKey.getOrPut(key) { ArrayList() } += t to v
        }
        val out = ArrayList<Pt>()
        byKey.forEach { (key, list) ->
            val s = list.sortedBy { it.first }
            val last = s.lastOrNull() ?: return@forEach
            out += Pt(key, key, out.size.toDouble(), last.second, s.getOrNull(s.size - 2)?.second, last.first)
        }
        return Got(points = out)
    }

    /** BLS v2: Results.series[] of seriesID + data[] newest first (year, period M01..M12, value as text). */
    fun bls(body: String): Got {
        val series = JSONObject(body).getJSONObject("Results").getJSONArray("series")
        val out = ArrayList<Pt>()
        objects(series).forEach { s ->
            val data = objects(s.optJSONArray("data")).mapNotNull { d ->
                val v = Catalog.str(d, "value").toDoubleOrNull() ?: return@mapNotNull null
                val p = Catalog.str(d, "period")
                (Catalog.str(d, "year") + if (p.startsWith("M") && p != "M13") "-" + p.drop(1) else "") to v
            }.sortedByDescending { it.first }
            val last = data.firstOrNull() ?: return@forEach
            out += Pt(Catalog.str(s, "seriesID"), Catalog.str(s, "seriesID"), out.size.toDouble(), last.second, data.getOrNull(1)?.second, last.first)
        }
        return Got(points = out)
    }

    /** Frankfurter: `latest` ({date, rates:{EUR:..}}) or a range ({rates:{date:{EUR:..}}}); per currency the last and previous fix. */
    fun frankfurter(body: String): Got {
        val o = JSONObject(body)
        val rates = o.getJSONObject("rates")
        val keys = rates.keys().asSequence().toList()
        if (keys.isEmpty()) return Got()
        if (rates.opt(keys.first()) is JSONObject) {
            val dates = keys.sorted()
            val last = rates.getJSONObject(dates.last())
            val prev = dates.getOrNull(dates.size - 2)?.let { rates.getJSONObject(it) }
            return Got(points = last.keys().asSequence().sorted().mapIndexed { i, c ->
                Pt(c, c, i.toDouble(), last.getDouble(c), prev?.optDouble(c)?.takeIf { it.isFinite() }, dates.last())
            }.toList())
        }
        val date = Catalog.str(o, "date")
        return Got(points = keys.sorted().mapIndexed { i, c -> Pt(c, c, i.toDouble(), rates.getDouble(c), null, date) })
    }

    // ------------------------------------------------------------------ quotes and bars

    /** Coinbase Exchange: `products/{id}/stats` ({open, last}: the 24h change) or `candles` ([[time, low, high, open, close, volume]], newest first). */
    fun coinbase(body: String, each: String): Got {
        val t = body.trimStart()
        if (t.startsWith("[")) {
            val a = JSONArray(t)
            val bars = (0 until a.length()).mapNotNull { a.optJSONArray(it) }.mapNotNull { c ->
                if (c.length() < 5) return@mapNotNull null
                val day = Instant.ofEpochSecond(c.getLong(0)).atZone(ZoneOffset.UTC).toLocalDate().toString()
                Bar(day, c.getDouble(3), c.getDouble(2), c.getDouble(1), c.getDouble(4))
            }.sortedBy { it.date }
            // per item (a quotes row): the last daily close, the one before, and the last 7 closes as the row's sparkline (spec 9 crypto top-20)
            if (each.isEmpty() || bars.isEmpty()) return Got(bars = bars)
            val n = bars.size
            return Got(bars = bars, points = listOf(Pt(each, each, 0.0, bars[n - 1].c, if (n > 1) bars[n - 2].c else null, bars[n - 1].date, bars.takeLast(7).map { it.c })))
        }
        val o = JSONObject(t)
        val last = Catalog.str(o, "last").toDoubleOrNull() ?: return Got()
        return Got(points = listOf(Pt(each, each, 0.0, last, Catalog.str(o, "open").toDoubleOrNull(), "24h")))
    }

    /** Cboe delayed quotes: a quote ({data:{current_price, prev_day_close, last_trade_time}}) or a daily history ({data:[{date, open, high, low, close}]}). */
    fun cboe(body: String, each: String, last: Int): Got {
        val data = JSONObject(body).opt("data")
        if (data is JSONArray) {
            val bars = objects(data).mapNotNull { d ->
                val c = Catalog.str(d, "close").toDoubleOrNull() ?: return@mapNotNull null
                Bar(Catalog.str(d, "date"), Catalog.str(d, "open").toDoubleOrNull() ?: c, Catalog.str(d, "high").toDoubleOrNull() ?: c, Catalog.str(d, "low").toDoubleOrNull() ?: c, c)
            }.sortedBy { it.date }
            return Got(bars = bars.takeLast(maxOf(1, last)))
        }
        val d = data as? JSONObject ?: return Got()
        val price = num(d, "current_price") ?: return Got()
        val key = each.ifEmpty { Catalog.str(d, "symbol") }
        return Got(points = listOf(Pt(key, key, 0.0, price, cboePrev(price, num(d, "price_change"), num(d, "prev_day_close")), Catalog.str(d, "last_trade_time").take(16).replace('T', ' '))))
    }

    /** Previous close of a Cboe quote: price minus Cboe's absolute price_change. After the close Cboe rolls prev_day_close to the
     *  current price, so prev_day_close is only a fallback, and one equal to the price is dropped (unknown, not a fake 0 change). */
    fun cboePrev(price: Double, change: Double?, prevClose: Double?): Double? = when {
        change != null -> price - change
        prevClose != null && prevClose != price -> prevClose
        else -> null
    }

    // ------------------------------------------------------------------ calendars

    /** Forex Factory weekly feed: [{title, country (currency), date (ISO with offset), impact, forecast, previous, actual?}]. */
    fun ff(body: String): Got = Got(events = objects(JSONArray(body)).mapNotNull { e ->
        val ts = utc(Catalog.str(e, "date")) ?: return@mapNotNull null
        Ev(ts, country(Catalog.str(e, "country")), Catalog.str(e, "title"), importance(Catalog.str(e, "impact")),
            Catalog.str(e, "forecast"), Catalog.str(e, "actual"), Catalog.str(e, "previous"))
    })

    /** Forex Factory currency code to the country (area) it is published for. */
    fun country(ccy: String): String = when (ccy.uppercase(Locale.ROOT)) {
        "USD" -> "US"; "EUR" -> "EA"; "GBP" -> "GB"; "JPY" -> "JP"; "CNY" -> "CN"; "CAD" -> "CA"; "AUD" -> "AU"; "NZD" -> "NZ"; "CHF" -> "CH"
        else -> ccy.uppercase(Locale.ROOT)
    }

    /** Forex Factory impact to importance: High 3, Medium 2, Low 1, anything else (Holiday, Non-Economic) 0. */
    fun importance(impact: String): Int = when (impact.lowercase(Locale.ROOT)) {
        "high" -> 3; "medium" -> 2; "low" -> 1; else -> 0
    }

    /** An ISO date-time with an offset ("2026-10-30T08:30:00-04:00") to UTC "2026-10-30T12:30Z"; a bare date stays a date; else null. */
    fun utc(raw: String): String? {
        if (Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(raw)) return raw
        return runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneOffset.UTC).format(UTC_MIN) }.getOrNull()
    }

    private val UTC_MIN: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'", Locale.ROOT)

    /** iCalendar (BLS release schedule): each VEVENT's DTSTART (with its TZID) to UTC and SUMMARY; importance by the declared title map, else 1. */
    fun ics(body: String, imp: JSONObject?): Got {
        val lines = body.replace("\r\n", "\n").replace("\n ", "").split("\n")
        val out = ArrayList<Ev>()
        var start = ""
        var summary = ""
        for (l in lines) {
            when {
                l.startsWith("BEGIN:VEVENT") -> { start = ""; summary = "" }
                l.startsWith("DTSTART") -> start = l
                l.startsWith("SUMMARY:") -> summary = l.removePrefix("SUMMARY:").trim()
                l.startsWith("END:VEVENT") -> icsTs(start)?.let { ts ->
                    val n = if (imp == null) 1 else imp.keys().asSequence().filter { summary.contains(it, ignoreCase = true) }.map { imp.optInt(it, 1) }.maxOrNull() ?: 1
                    out += Ev(ts, "US", summary, n, kind = "release")
                }
            }
        }
        return Got(events = out)
    }

    /** DTSTART;TZID=US-Eastern:20261110T083000 (or ...Z, or VALUE=DATE:20261110) to UTC. */
    fun icsTs(line: String): String? {
        val m = Regex("^DTSTART(;[^:]*)?:(\\d{8})(T(\\d{6})(Z?))?$").find(line.trim()) ?: return null
        val d = LocalDate.parse(m.groupValues[2], DateTimeFormatter.BASIC_ISO_DATE)
        if (m.groupValues[3].isEmpty()) return d.toString()
        val t = m.groupValues[4]
        val local = LocalDateTime.of(d.year, d.month, d.dayOfMonth, t.substring(0, 2).toInt(), t.substring(2, 4).toInt())
        val params = m.groupValues[1]
        val zone = when {
            m.groupValues[5] == "Z" -> ZoneOffset.UTC
            params.contains("Eastern", ignoreCase = true) || params.contains("New_York") -> ZoneId.of("America/New_York")
            else -> Regex("TZID=([^;:]+)").find(params)?.groupValues?.get(1)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneOffset.UTC
        }
        return local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).format(UTC_MIN)
    }

    /** data/central-banks.json: banks[].events[] of {ts, title}; every decision is importance 3. */
    fun declared(body: String): Got = Got(events = objects(JSONObject(body).getJSONArray("banks")).flatMap { b ->
        objects(b.optJSONArray("events")).map { e -> Ev(Catalog.str(e, "ts"), Catalog.str(b, "country"), Catalog.str(e, "title"), 3, kind = "central_bank") }
    })

    /** Nasdaq earnings by day: data.rows[] of symbol, name, time, epsForecast, lastYearEPS, marketCap; importance by market cap. */
    fun nasdaq(body: String, day: String): Got {
        val rows = JSONObject(body).optJSONObject("data")?.optJSONArray("rows") ?: return Got()
        return Got(events = objects(rows).map { r ->
            val cap = Catalog.str(r, "marketCap").filter { it.isDigit() }.toDoubleOrNull() ?: 0.0
            val time = when (Catalog.str(r, "time")) { "time-pre-market" -> " (before open)"; "time-after-hours" -> " (after close)"; else -> "" }
            Ev(day, "US", Catalog.str(r, "symbol") + " " + Catalog.str(r, "name") + time, if (cap >= 1e11) 3 else if (cap >= 1e10) 2 else 1,
                Catalog.str(r, "epsForecast"), "", Catalog.str(r, "lastYearEPS"), "earnings")
        })
    }

    /** Polymarket Gamma events: per open market its label (groupItemTitle, else the question) and the first outcome's price as a percent. */
    fun polymarket(body: String, last: Int): Got {
        val out = ArrayList<Pt>()
        objects(JSONArray(body)).forEach { e ->
            objects(e.optJSONArray("markets")).forEach { m ->
                if (m.optBoolean("closed", false)) return@forEach
                val prices = runCatching { JSONArray(Catalog.str(m, "outcomePrices")) }.getOrNull() ?: return@forEach
                val p = prices.optString(0).toDoubleOrNull() ?: return@forEach
                val label = Catalog.str(m, "groupItemTitle").ifEmpty { Catalog.str(m, "question") }
                out += Pt(Catalog.str(m, "slug").ifEmpty { label }, Catalog.str(e, "title") + ": " + label, out.size.toDouble(), p * 100.0, null, Catalog.str(e, "endDate").take(10))
            }
        }
        return Got(points = out.take(maxOf(1, last)))
    }

    // ------------------------------------------------------------------ SEC EDGAR

    /** SEC submissions: the profile as key/value rows and the recent filings (declared forms, newest first, declared count) with their links. */
    fun sec(body: String, o: Opt): Got {
        val s = JSONObject(body)
        val fy = Catalog.str(s, "fiscalYearEnd")
        val kv = listOf(
            "Name" to Catalog.str(s, "name"),
            "Tickers" to strings(s.optJSONArray("tickers")).joinToString(", "),
            "Exchanges" to strings(s.optJSONArray("exchanges")).joinToString(", "),
            "Industry" to Catalog.str(s, "sicDescription"),
            "Filer category" to Catalog.str(s, "category"),
            "Fiscal year end" to if (fy.length == 4) fy.substring(0, 2) + "-" + fy.substring(2) else fy,
            "State of incorporation" to Catalog.str(s, "stateOfIncorporation"),
            "CIK" to Catalog.str(s, "cik"),
        ).filter { it.second.isNotBlank() }
        val r = s.optJSONObject("filings")?.optJSONObject("recent") ?: return Got(kv = kv)
        val forms = strings(r.optJSONArray("form"))
        val want = strings(o.spec.optJSONArray("forms")).toSet()
        val cik = Catalog.str(s, "cik").trimStart('0')
        val template = o.paths["filing"].orEmpty()
        val rows = ArrayList<List<String>>()
        val links = ArrayList<String>()
        for (i in forms.indices) {
            if (want.isNotEmpty() && forms[i] !in want) continue
            val acc = r.optJSONArray("accessionNumber")?.optString(i).orEmpty()
            val doc = r.optJSONArray("primaryDocument")?.optString(i).orEmpty()
            val desc = r.optJSONArray("primaryDocDescription")?.optString(i).orEmpty().ifBlank { forms[i] }
            rows += listOf(desc, forms[i], r.optJSONArray("filingDate")?.optString(i).orEmpty())
            links += if (template.isEmpty()) "" else template.replace("{cik}", cik).replace("{accession}", acc.replace("-", "")).replace("{document}", doc)
            if (rows.size >= o.spec.optInt("last", 12)) break
        }
        return Got(kv = kv, rows = rows, links = links)
    }

    /** SEC companyconcept: the full-year values (frame CYyyyy, last filed wins), scaled, the last [last] years, each with the year before. */
    fun secConcept(body: String, scale: Double, last: Int): Got {
        val units = JSONObject(body).getJSONObject("units")
        val unit = units.keys().asSequence().sorted().firstOrNull() ?: return Got()
        val byYear = sortedMapOf<String, Pair<String, Double>>()
        objects(units.getJSONArray(unit)).forEach { f ->
            val frame = Catalog.str(f, "frame")
            if (!Regex("^CY\\d{4}$").matches(frame)) return@forEach
            val v = num(f, "val") ?: return@forEach
            byYear[frame.drop(2)] = Catalog.str(f, "end") to v * scale
        }
        val years = byYear.keys.toList()
        val pts = years.mapIndexed { i, y -> Pt(y, y, i.toDouble(), byYear.getValue(y).second, years.getOrNull(i - 1)?.let { byYear.getValue(it).second }, byYear.getValue(y).first) }
        return Got(points = pts.takeLast(maxOf(1, last)).mapIndexed { i, p -> p.copy(x = i.toDouble()) })
    }

    // ------------------------------------------------------------------ the container's /v1 answers

    /**
     * The FinTerm container's normalised answers (design 3.2): {series:[{id, title, obs:[{date, value}]}]}, {quotes:[{symbol, name, price,
     * prev_close, ts}]}, {points:[{label, tenor_years, value}], prev:[...], asof}, {rates:{..}, prev:{..}, asof}, {events:[Event]}, {bars:[{t,o,h,l,c}]},
     * {profile:{..}, filings:[{title, form, filed, url}], facts:{<concept>:[{fy, end, val}]}}, {implied:[{label, prob, ends}]}. Every key present is read.
     */
    fun v1(body: String, concept: String): Got {
        val o = JSONObject(body)
        val pts = ArrayList<Pt>()
        objects(o.optJSONArray("series")).forEach { s ->
            val obs = objects(s.optJSONArray("obs")).mapNotNull { x -> num(x, "value")?.let { Catalog.str(x, "date") to it } }.sortedBy { it.first }
            val last = obs.lastOrNull() ?: return@forEach
            val id = Catalog.str(s, "id")
            pts += Pt(id, Catalog.str(s, "title").ifEmpty { id }, pts.size.toDouble(), last.second, obs.getOrNull(obs.size - 2)?.second, last.first)
        }
        objects(o.optJSONArray("quotes")).forEach { q ->
            val p = num(q, "price") ?: return@forEach
            val sym = Catalog.str(q, "symbol")
            pts += Pt(sym, Catalog.str(q, "name").ifEmpty { sym }, pts.size.toDouble(), p, cboePrev(p, num(q, "change"), num(q, "prev_close")), Catalog.str(q, "ts").take(16).replace('T', ' '))
        }
        val asof = Catalog.str(o, "asof")
        val prev = HashMap<String, Double>()
        objects(o.optJSONArray("prev")).forEach { p -> num(p, "value")?.let { prev[Catalog.str(p, "label")] = it } }
        objects(o.optJSONArray("points")).forEach { p ->
            val v = num(p, "value") ?: return@forEach
            val l = Catalog.str(p, "label")
            pts += Pt(Catalog.str(p, "key").ifEmpty { l }, l, p.optDouble("tenor_years", pts.size.toDouble()), v, prev[l], asof)
        }
        // /v1/curves/{CC} (finterm-store): {tenors, curve:{country, gdp_weight, tenors:[{tenor, value|null, date, source, method}]}};
        // a null value is a cell no free source covers (its `method` says why) and is left out
        objects(o.optJSONObject("curve")?.optJSONArray("tenors")).forEach { t ->
            val v = num(t, "value") ?: return@forEach
            val k = Catalog.str(t, "tenor")
            tenorYears(k)?.let { y -> pts += Pt(k, k, y, v, null, Catalog.str(t, "date")) }
        }
        o.optJSONObject("rates")?.let { r ->
            val pr = o.optJSONObject("prev")
            r.keys().asSequence().sorted().forEach { c -> pts += Pt(c, c, pts.size.toDouble(), r.getDouble(c), pr?.optDouble(c)?.takeIf { it.isFinite() }, asof) }
        }
        objects(o.optJSONArray("implied")).forEach { m -> num(m, "prob")?.let { pts += Pt(Catalog.str(m, "label"), Catalog.str(m, "label"), pts.size.toDouble(), it, null, Catalog.str(m, "ends")) } }
        o.optJSONObject("facts")?.optJSONArray(concept)?.let { arr ->
            val f = objects(arr).mapNotNull { x -> num(x, "val")?.let { Triple(Catalog.str(x, "fy"), Catalog.str(x, "end"), it) } }.sortedBy { it.first }
            f.forEachIndexed { i, (fy, end, v) -> pts += Pt(fy, fy, i.toDouble(), v, f.getOrNull(i - 1)?.third, end) }
        }
        val events = objects(o.optJSONArray("events")).map { e ->
            Ev(Catalog.str(e, "ts"), Catalog.str(e, "country"), Catalog.str(e, "title"), e.optInt("importance", 0),
                Catalog.str(e, "consensus"), Catalog.str(e, "actual"), Catalog.str(e, "previous"), Catalog.str(e, "kind").ifEmpty { "economic" })
        }
        val bars = objects(o.optJSONArray("bars")).mapNotNull { b ->
            val c = num(b, "c") ?: num(b, "close") ?: return@mapNotNull null
            Bar(Catalog.str(b, "t").ifEmpty { Catalog.str(b, "date") }.take(10), num(b, "o") ?: num(b, "open") ?: c, num(b, "h") ?: num(b, "high") ?: c, num(b, "l") ?: num(b, "low") ?: c, c)
        }.sortedBy { it.date }
        val kv = o.optJSONObject("profile")?.let { p -> p.keys().asSequence().sorted().map { it to Catalog.str(p, it) }.toList() }.orEmpty()
        val filings = objects(o.optJSONArray("filings"))
        return Got(pts, events, bars, kv, filings.map { listOf(Catalog.str(it, "title").ifEmpty { Catalog.str(it, "form") }, Catalog.str(it, "form"), Catalog.str(it, "filed")) },
            filings.map { Catalog.str(it, "url") })
    }

    // ------------------------------------------------------------------ helpers

    /** A tenor label in years: "3M" 0.25, "10Y" 10.0, "2W" 2/52; null for anything else. */
    fun tenorYears(t: String): Double? {
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*([WMY])$").find(t.trim().uppercase()) ?: return null
        val n = m.groupValues[1].toDouble()
        return when (m.groupValues[2]) { "Y" -> n; "M" -> n / 12.0; else -> n / 52.0 }
    }

    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    /** A number field, also when it is published as text; null when absent or not a finite number. */
    private fun num(o: JSONObject, key: String): Double? = when (val v = o.opt(key)) {
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }
}

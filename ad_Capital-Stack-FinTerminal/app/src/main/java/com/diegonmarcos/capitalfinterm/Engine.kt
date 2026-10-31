package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/** A point of a chart: [label] names it, [x] is its position on a line chart, [y] its value. */
data class ChartPoint(val label: String, val x: Double, val y: Double)

/** What a panel shows: a table (columns, rows, optional link per row), a chart, the date of the data and a note. */
data class PanelData(
    val columns: List<String>,
    val rows: List<List<String>>,
    val links: List<String>,
    val chart: List<ChartPoint>,
    val asOf: String,
    val note: String,
    /** The name of the source that served it when that is not the panel's own (the primary), else empty. */
    val servedBy: String = "",
    /** The user's alert rules that hold on this panel's data (Watchlist & Alerts), one line each. */
    val alerts: List<String> = emptyList(),
    /** Per row, the full name the one-line name cell shortens (shown in the row's detail view); empty when the cell is the whole name. */
    val details: List<String> = emptyList(),
)

/** What a model panel (Feeds.kt) needs besides its own source: the catalog (fallback routes), its dashboard, the container route, the user's lists and now (UTC). */
data class ModelCtx(
    val sources: List<Source>,
    val dash: Dashboard?,
    val api: ApiRoute? = null,
    val user: UserLists = UserLists(),
    val nowUtc: String = "",
)

/**
 * Loads a live panel: builds its requests from the source's declared endpoint and the panel's declared fetch, reads the answers through
 * [Http], and derives the rows and the chart named by the panel's `derive`. Pure apart from the [Http] it is given.
 */
object Engine {
    /** Fetches and derives [panel] from [source]. Throws IOException when nothing came back. */
    fun load(panel: Panel, source: Source, http: Http, today: LocalDate, route: PrimaryRoute? = null, fallback: Source? = null, model: ModelCtx? = null): PanelData {
        val f = panel.fetch ?: throw IOException("panel ${panel.id} has no fetch")
        val data = when (panel.derive) {
            in MODEL_DERIVES -> model(panel, source, f, http, today, model ?: ModelCtx(emptyList(), null))
            "latest_curve", "forward_grid", "level_change", "fx_spot", "fx_cross" -> fredPanel(panel, source, f, http, today, route, fallback)
            "latest_per_series" -> perSeries(panel, source, f, http)
            "imf_latest" -> imf(panel, source, f, http)
            "worldbank_latest" -> worldBank(panel, source, f, http)
            "articles" -> articles(panel, source, f, http)
            else -> throw IOException("unknown derive ${panel.derive}")
        }
        if (data.rows.isEmpty() && data.chart.isEmpty() && data.note.isEmpty()) throw IOException("no data")
        return data
    }

    /** The static allocation of a model portfolio: its legs as bars and rows. */
    fun alloc(panel: Panel, legs: List<Pair<String, Int>>): PanelData = PanelData(
        panel.columns, legs.map { listOf(it.first, "${it.second}%") }, emptyList(),
        legs.mapIndexed { i, l -> ChartPoint(l.first, i.toDouble(), l.second.toDouble()) }, "", "",
    )

    /** The derives of the normalised model (Feeds.kt): points, events, candles, kv, filings. */
    val MODEL_DERIVES: Set<String> = setOf("points", "events", "candles", "kv", "filings") + Tables.DERIVES

    // ------------------------------------------------------------------ model panels

    /**
     * A model panel: the container's /v1 answer first (fetch.primary.api, when the mesh answers); else the panel's own source, then each declared
     * fallback in order (a fallback must feed the entry). merge = every route that answers is combined (calendars); else the first that answers wins.
     */
    private fun model(panel: Panel, source: Source, f: JSONObject, http: Http, today: LocalDate, c: ModelCtx): PanelData {
        if (panel.derive in Tables.DERIVES) return Tables.load(panel, source, f, http, today, c)
        var got = Got()
        var by = ""
        var answered = false
        val p = f.optJSONObject("primary")
        if (c.api != null && p != null && Catalog.str(p, "api").isNotEmpty()) {
            try {
                val items = eachOf(f, today, c.user)
                val sl = slots(f, today, items)
                val url = Requests.url(c.api.base, Catalog.str(p, "api").let { a -> sl.entries.fold(a) { s, (k, v) -> s.replace("{$k}", v) } }, params(p).map { it.first to fill(it.second, sl) })
                val g = Feeds.parse(Catalog.str(p, "parse").ifEmpty { "finterm_v1" }, http.get(url), Feeds.Opt(f))
                if (!g.isEmpty) {
                    got = g
                    by = c.api.source.name
                }
            } catch (e: IOException) {
                got = Got()
            }
        }
        if (got.isEmpty) {
            val merge = f.optBoolean("merge", false)
            val routes = ArrayList<Pair<Source, JSONObject>>()
            routes += source to f
            val fbs = f.optJSONArray("fallbacks")
            if (fbs != null) for (i in 0 until fbs.length()) {
                val fb = fbs.optJSONObject(i) ?: continue
                val s = Catalog.byId(c.sources, Catalog.str(fb, "source")) ?: continue
                if (c.dash != null && !s.feeds(c.dash.tab, c.dash.entry)) continue
                routes += s to route(f, fb)
            }
            val names = ArrayList<String>()
            for ((s, spec) in routes) {
                val g = try {
                    fetchRoute(s, spec, http, today, c.user).also { answered = true }
                } catch (e: IOException) {
                    Got()
                }
                if (g.isEmpty) continue
                got += g
                names += s.name
                if (!merge) break
            }
            by = if (names.distinct() == listOf(source.name)) "" else names.distinct().joinToString(" + ")
        }
        // a calendar whose sources answered with nothing in this window is an empty window, not a failure
        if (got.isEmpty && !(answered && panel.derive == "events")) throw IOException("no data")
        return render(panel, f, got, today, c).copy(servedBy = by)
    }

    /** A fallback route's spec: the panel's options (labels, years, key_dim, filter) with the route's own path, params, each and provider. */
    fun route(f: JSONObject, fb: JSONObject): JSONObject {
        val o = JSONObject(f.toString())
        listOf("path", "params", "provider", "fallbacks", "primary", "each", "each_days", "each_user").forEach { o.remove(it) }
        fb.keys().forEach { o.put(it, fb.get(it)) }
        return o
    }

    /** One route: one request, or one per item when the path or a parameter carries {each}; an item that fails is skipped, all failing is an error. */
    private fun fetchRoute(s: Source, spec: JSONObject, http: Http, today: LocalDate, user: UserLists): Got {
        val items = eachOf(spec, today, user)
        val path = Catalog.str(spec, "path")
        val ps = params(spec)
        val perItem = items.isNotEmpty() && (path.contains("{each}") || ps.any { it.second.contains("{each}") })
        val list = if (perItem) items else listOf("")
        val conn = Catalog.str(spec, "provider").ifEmpty { s.connector }
        var got = Got()
        var failed = 0
        for (item in list) {
            val sl = slots(spec, today, items) + ("each" to item)
            try {
                got += Feeds.parse(conn, http.get(Requests.url(s.endpoint, path, ps.map { it.first to fill(it.second, sl) }, sl)), Feeds.Opt(spec, item, s.paths))
            } catch (e: IOException) {
                failed++
            }
        }
        if (failed == list.size) throw IOException("${s.name}: no answer")
        return got
    }

    /** The items of a per-item fetch: the user's list when the panel takes one (each_user) and it is set, else the next each_days dates, else each. */
    fun eachOf(f: JSONObject, today: LocalDate, user: UserLists): List<String> {
        if (Catalog.str(f, "each_user") == "watchlist" && user.watchlist.isNotEmpty()) return user.watchlist
        val days = f.optInt("each_days", 0)
        if (days > 0) return (0 until days).map { today.plusDays(it.toLong()).toString() }
        return strings(f.optJSONArray("each"))
    }

    /** The slots a path or parameter may carry: {today} {from} (today - lookback_days, default 7) {yyyymm} {prev_yyyymm} {cik} {each_csv}. */
    fun slots(f: JSONObject, today: LocalDate, items: List<String>): Map<String, String> = mapOf(
        "today" to today.toString(),
        "from" to today.minusDays(f.optInt("lookback_days", 7).toLong()).toString(),
        "yyyymm" to String.format(Locale.ROOT, "%04d%02d", today.year, today.monthValue),
        "prev_yyyymm" to today.minusMonths(1).let { String.format(Locale.ROOT, "%04d%02d", it.year, it.monthValue) },
        "cik" to Catalog.str(f, "cik"),
        "each_csv" to items.joinToString(","),
    )

    private fun fill(v: String, slots: Map<String, String>): String = slots.entries.fold(v) { s, (k, x) -> s.replace("{$k}", x) }

    /** The model rendered by the panel's derive and view. */
    private fun render(panel: Panel, f: JSONObject, got: Got, today: LocalDate, c: ModelCtx): PanelData = when (panel.derive) {
        "events" -> {
            val ev = events(got.events, f.optJSONObject("filter"), today)
            val rows = ev.map { listOf(it.ts.replace('T', ' ').removeSuffix("Z"), it.country, it.title, dots(it.importance), it.consensus, it.actual, it.previous) }
            val alerts = if (panel.raw.optString("alerts") == "event") Alerts.eventHits(c.user.alerts, ev, c.nowUtc) else emptyList()
            PanelData(panel.columns, rows, emptyList(), emptyList(), today.toString(), if (ev.isEmpty()) "No event in this window." else "", alerts = alerts)
        }
        "candles" -> {
            val bars = got.bars.distinctBy { it.date }.sortedBy { it.date }
            val chart = bars.mapIndexed { i, b -> ChartPoint(b.date, i.toDouble(), b.c) }
            val rows = bars.takeLast(5).reversed().map { listOf(it.date, sig(it.o), sig(it.h), sig(it.l), sig(it.c)) }
            PanelData(panel.columns, rows, emptyList(), chart, bars.lastOrNull()?.date.orEmpty(), "")
        }
        "kv" -> PanelData(panel.columns, got.kv.map { listOf(it.first, it.second) }, emptyList(), emptyList(), "", "")
        "filings" -> PanelData(panel.columns, got.rows, got.links, emptyList(), got.rows.maxOfOrNull { it.getOrElse(2) { "" } }.orEmpty(), "")
        else -> points(panel, f, got.points, c)
    }

    /** Points: labels rename and select (in the answer's order), years place them on a curve (sorted), then rows and a chart per view. */
    private fun points(panel: Panel, f: JSONObject, all: List<Pt>, c: ModelCtx): PanelData {
        val labels = f.optJSONObject("labels")
        val years = f.optJSONObject("years")
        fun keyIn(o: JSONObject, k: String): String? = if (o.has(k)) k else k.substringAfterLast(':').takeIf { o.has(it) }
        var pts = all.distinctBy { it.key }
        if (labels != null && labels.length() > 0) pts = pts.mapNotNull { p -> keyIn(labels, p.key)?.let { p.copy(label = labels.getString(it)) } }
        if (years != null && years.length() > 0) pts = pts.mapNotNull { p -> keyIn(years, p.key)?.let { p.copy(x = years.getDouble(it)) } }.sortedBy { it.x }
        val bp = panel.raw.optString("change_unit") == "bp"
        val pctUnit = panel.unit == "%"
        fun v(x: Double) = if (pctUnit) num(x, 2) else if (Math.abs(x) >= 1000.0) group(x) else sig(x)
        fun plain(x: Double) = if (pctUnit) num(x, 2) else if (Math.abs(x) >= 10000.0) String.format(Locale.ROOT, "%,.0f", x)
            else java.math.BigDecimal(String.format(Locale.ROOT, "%.4f", x)).stripTrailingZeros().toPlainString()
        fun chg(p: Pt): String = p.prev?.let { if (bp) String.format(Locale.ROOT, "%+.1f", (p.value - it) * 100.0) else FinFormat.signed(p.value - it, decimals(p.value)) } ?: FinFormat.DASH
        fun pct(p: Pt): Double? = p.prev?.takeIf { it != 0.0 }?.let { (p.value / it - 1.0) * 100.0 }
        fun pctText(p: Pt): String = pct(p)?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: FinFormat.DASH
        // A quotes table whose feed carries no previous value (NY Fed latest) shows no change columns instead of a column of dashes.
        val noChange = panel.view == Dashboards.VIEW_QUOTES && pts.isNotEmpty() && pts.all { it.prev == null }
        val rows = pts.map { p ->
            when {
                noChange -> listOf(p.label, v(p.value), p.date)
                panel.raw.optString("value_columns") == "actual_previous" -> listOf(p.label, plain(p.value), p.prev?.let { plain(it) } ?: FinFormat.DASH, p.date)
                panel.view == Dashboards.VIEW_QUOTES -> listOf(p.label, v(p.value), chg(p), if (bp) FinFormat.DASH else pctText(p), p.date)
                else -> listOf(p.label, v(p.value), if (bp) chg(p) else pctText(p), p.date)
            }
        }
        val chart = when (panel.view) {
            Dashboards.VIEW_CURVE -> pts.map { ChartPoint(it.label, it.x, it.value) }
            Dashboards.VIEW_BARS -> pts.mapIndexed { i, p -> ChartPoint(p.label, i.toDouble(), if (panel.raw.optString("chart_value") == "change_pct") pct(p) ?: 0.0 else p.value) }
            Dashboards.VIEW_QUOTES -> pts.mapIndexed { i, p -> ChartPoint(p.label, i.toDouble(), if (bp) (p.prev?.let { (p.value - it) * 100.0 } ?: 0.0) else pct(p) ?: 0.0) }
            else -> emptyList()
        }
        val alerts = if (panel.raw.optString("alerts") == "price") Alerts.priceHits(c.user.alerts, pts) else emptyList()
        val cols = if (noChange) panel.columns.filterNot { it.startsWith("Chg") } else panel.columns
        return PanelData(cols, rows, emptyList(), chart, pts.maxOfOrNull { it.date }.orEmpty(), "", alerts = alerts)
    }

    /** Events in the declared window (days_back before today to days_ahead after), at or above importance_min, once each, in time order. */
    fun events(all: List<Ev>, filter: JSONObject?, today: LocalDate): List<Ev> {
        val from = today.minusDays(filter?.optLong("days_back", 1) ?: 1).toString()
        val to = today.plusDays(filter?.optLong("days_ahead", 7) ?: 7).toString()
        val min = filter?.optInt("importance_min", 0) ?: 0
        return all.filter { it.ts.length >= 10 && it.ts.take(10) in from..to && it.importance >= min }
            .distinctBy { it.ts.take(13) + "|" + it.country + "|" + it.title.lowercase(Locale.ROOT) }
            .sortedWith(compareBy({ it.ts.take(10) }, { if (it.ts.length == 10) "" else it.ts }, { -it.importance }))
    }

    /** Importance as three dots: 0 to 3 filled. */
    fun dots(n: Int): String = "\u25CF".repeat(n.coerceIn(0, 3)) + "\u25CB".repeat(3 - n.coerceIn(0, 3))

    /** The instrument's display decimals: 2 for a level at or above 1 (indices, prices), 4 below (FX crosses, fractions). */
    fun decimals(level: Double): Int = if (Math.abs(level) >= 1.0) 2 else 4


    // ------------------------------------------------------------------ FRED

    private class FredSeries(val spec: JSONObject, val obs: List<Obs>, val viaPrimary: Boolean = false, val viaFallback: Boolean = false) {
        val label: String get() = Catalog.str(spec, "label")
        val last: Obs get() = obs.last()
        val prev: Obs? get() = obs.getOrNull(obs.size - 2)
    }

    private fun fredPanel(panel: Panel, source: Source, f: JSONObject, http: Http, today: LocalDate, route: PrimaryRoute?, fallback: Source?): PanelData {
        val series = fredSeries(source, f, http, today, route, fallback)
        val names = listOfNotNull(
            route?.source?.name?.takeIf { series.any { it.viaPrimary } },
            source.name.takeIf { series.any { !it.viaPrimary && !it.viaFallback } },
            fallback?.name?.takeIf { series.any { it.viaFallback } },
        )
        val by = if (names == listOf(source.name)) "" else names.joinToString(" + ")
        val asOf = series.maxOf { it.last.date }
        val missing = (f.getJSONArray("series").length() - series.size).let { if (it > 0) "$it series unavailable" else "" }
        return derived(panel, f, series, asOf, missing).copy(servedBy = by)
    }

    private fun derived(panel: Panel, f: JSONObject, series: List<FredSeries>, asOf: String, missing: String): PanelData {
        return when (panel.derive) {
            "latest_curve" -> {
                val pts = series.map { ChartPoint(it.label, it.spec.optDouble("years"), it.last.value) }.sortedBy { it.x }
                val rows = series.map { listOf(it.label, num(it.last.value, 2), change(it.last, it.prev, 100.0, 1), it.last.date) }
                PanelData(panel.columns, rows, emptyList(), pts, asOf, missing)
            }
            "forward_grid" -> forwardGrid(panel, f, series, asOf, missing)
            "level_change" -> {
                val bp = panel.raw.optString("change_unit") == "bp"
                val byLevel = panel.raw.optString("chart_value") == "level"
                val rows = series.map { s ->
                    val chg = if (bp) change(s.last, s.prev, 100.0, 0) else pctChange(s.last, s.prev)
                    listOf(s.label, group(s.last.value), chg, s.last.date)
                }
                val chart = series.mapIndexed { i, s -> ChartPoint(s.label, i.toDouble(), if (byLevel) s.last.value else pct(s.last, s.prev) ?: 0.0) }
                PanelData(panel.columns, rows, emptyList(), chart, asOf, missing)
            }
            "fx_spot" -> {
                val rows = series.map { listOf(Catalog.str(it.spec, "pair"), sig(it.last.value), pctChange(it.last, it.prev), it.last.date) }
                PanelData(panel.columns, rows, emptyList(), emptyList(), asOf, missing)
            }
            else -> fxCross(panel, series, asOf, missing)
        }
    }

    private fun fredSeries(source: Source, f: JSONObject, http: Http, today: LocalDate, route: PrimaryRoute?, fallback: Source?): List<FredSeries> {
        val specs = f.getJSONArray("series")
        val lookback = f.optInt("lookback_days", 30)
        val tasks = (0 until specs.length()).map { i ->
            val spec = specs.getJSONObject(i)
            Callable<FredSeries?> {
                val id = spec.getString("id")
                val from = today.minusDays(lookback.toLong()).toString()
                val primary = if (route == null) emptyList() else try {
                    val url = Requests.url(route.base, route.peekPath, emptyList(), mapOf("topic" to route.topic.replace("{id}", id)))
                    Providers.finceptSeries(http.get(url)).filter { it.date >= from }
                } catch (e: IOException) {
                    emptyList()
                }
                if (primary.isNotEmpty()) return@Callable FredSeries(spec, primary, true)
                val obs = try {
                    FredCsv.parse(http.get(Requests.fred(source.endpoint, id, today, lookback)))[id].orEmpty()
                } catch (e: IOException) {
                    emptyList()
                }
                if (obs.isNotEmpty()) return@Callable FredSeries(spec, obs)
                val alt = if (fallback == null) emptyList() else try {
                    dbnomicsObs(fallback, spec, http).filter { it.date >= from }
                } catch (e: IOException) {
                    emptyList()
                }
                if (alt.isEmpty()) null else FredSeries(spec, alt, viaFallback = true)
            }
        }
        val pool = Executors.newFixedThreadPool(minOf(6, maxOf(1, tasks.size)))
        try {
            val got = pool.invokeAll(tasks).mapNotNull { it.get() }
            if (got.isEmpty()) throw IOException("no data")
            return got
        } finally {
            pool.shutdown()
        }
    }

    /**
     * The second-priority source of a FRED series (the Fed H.15 / H.10 mirror on DBnomics, which allows browser requests): the series declares
     * its DBnomics code as `dbnomics` ("<provider>/<dataset>/<series>"); a series without one has no second source. Missing values ("NA") are dropped.
     */
    fun dbnomicsObs(src: Source, spec: JSONObject, http: Http): List<Obs> {
        val code = Catalog.str(spec, "dbnomics")
        if (code.isEmpty()) return emptyList()
        val s = Providers.dbnomics(http.get(Requests.url(src.endpoint, "/series/$code", listOf("observations" to "1", "metadata" to "0")))).firstOrNull() ?: return emptyList()
        return s.periods.indices.mapNotNull { i -> s.values.getOrNull(i)?.let { Obs(s.periods[i], it) } }
    }

    private fun forwardGrid(panel: Panel, f: JSONObject, series: List<FredSeries>, asOf: String, note: String): PanelData {
        val fw = f.getJSONObject("forward")
        val curve = series.map { it.spec.optDouble("years") to it.last.value }.sortedBy { it.first }
        // Spec 9: TENOR | SPOT | FWD | DIFF (bp) when the panel declares table_start; else the declared [start, length] grid.
        val rows = if (fw.has("table_start")) Forward.table(curve, fw.getDouble("table_start")).map { (t, y, r) ->
            listOf("${years(t)}Y", num(y, 2), num(r, 2), if (r.isFinite()) FinFormat.signed((r - y) * 100, 0) else FinFormat.DASH)
        } else {
            val grid = fw.getJSONArray("grid")
            (0 until grid.length()).map { grid.getJSONArray(it) }.map { g ->
                listOf("${years(g.getDouble(0))}Y x ${years(g.getDouble(1))}Y", num(Forward.rate(curve, g.getDouble(0), g.getDouble(1)), 2))
            }
        }
        val pts = Forward.curve(curve, fw.optDouble("curve_length", 1.0), fw.optInt("curve_max_start", 29))
            .map { ChartPoint("${years(it.first)}Y", it.first, it.second) }
        return PanelData(panel.columns, rows, emptyList(), pts, asOf, note)
    }

    private fun fxCross(panel: Panel, series: List<FredSeries>, asOf: String, note: String): PanelData {
        val usd = LinkedHashMap<String, Double>()
        usd["USD"] = 1.0
        series.forEach { s ->
            val v = s.last.value
            if (v > 0.0) usd[Catalog.str(s.spec, "ccy")] = if (Catalog.str(s.spec, "quote") == "usd_per") v else 1.0 / v
        }
        val names = usd.keys.toList()
        val rows = names.map { i -> listOf(i) + names.map { j -> if (i == j) FinFormat.DASH else sig(usd.getValue(i) / usd.getValue(j)) } }
        return PanelData(panel.columns + names, rows, emptyList(), emptyList(), asOf, note)
    }

    // -------------------------------------------------------------- DBnomics

    private fun perSeries(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val body = http.get(Requests.url(source.endpoint, f.optString("path"), params(f)))
        val labels = f.optJSONObject("labels")
        val found = Providers.dbnomics(body).mapNotNull { s ->
            val (period, value, _) = s.latest() ?: return@mapNotNull null
            Triple(labels?.optString(s.code)?.takeIf { it.isNotBlank() } ?: s.name, value, period)
        }.sortedByDescending { it.second }
        val rows = found.map { listOf(it.first, num(it.second, 2), it.third) }
        val chart = if (panel.view == Dashboards.VIEW_BARS) found.mapIndexed { i, t -> ChartPoint(t.first, i.toDouble(), t.second) } else emptyList()
        return PanelData(panel.columns, rows, emptyList(), chart, found.maxOfOrNull { it.third }.orEmpty(), "")
    }

    // ------------------------------------------------------------- IMF, World Bank

    private fun imf(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val body = http.get(Requests.url(source.endpoint, f.optString("path"), params(f)))
        val names = f.optJSONObject("country_names")
        val nameMap = HashMap<String, String>()
        if (names != null) names.keys().forEach { nameMap[it] = names.optString(it) }
        val got = Providers.imf(body, f.optString("path").trim('/'), strings(f.optJSONArray("countries")), nameMap).sortedByDescending { it.value }
        return PanelData(panel.columns, got.map { listOf(it.name, num(it.value, 1), it.year) }, emptyList(), emptyList(), got.maxOfOrNull { it.year }.orEmpty(), "")
    }

    private fun worldBank(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val url = Requests.url(source.endpoint, f.optString("path"), params(f), mapOf("countries" to strings(f.optJSONArray("countries")).joinToString(";")))
        val got = Providers.worldBank(http.get(url)).sortedByDescending { it.value }
        return PanelData(panel.columns, got.map { listOf(it.name, num(it.value, 1), it.year) }, emptyList(), emptyList(), got.maxOfOrNull { it.year }.orEmpty(), "")
    }

    // ----------------------------------------------------------------- GDELT

    private fun articles(panel: Panel, source: Source, f: JSONObject, http: Http): PanelData {
        val got = Providers.gdelt(http.get(Requests.url(source.endpoint, f.optString("path"), params(f))))
        return PanelData(panel.columns, got.map { listOf(it.title, it.domain, it.seen) }, got.map { it.url }, emptyList(), got.maxOfOrNull { it.seen }.orEmpty(), "")
    }

    // --------------------------------------------------------------- helpers

    /** The declared `params` as ordered pairs; an object or array value is sent as its JSON text. */
    fun params(f: JSONObject): List<Pair<String, String>> {
        val p = f.optJSONObject("params") ?: return emptyList()
        return p.keys().asSequence().flatMap { k ->
            when (val v = p.opt(k)) {
                is JSONArray -> (0 until v.length()).map { k to v.optString(it) }.asSequence()
                else -> sequenceOf(k to v?.toString().orEmpty())
            }
        }.toList()
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }

    private fun years(v: Double): String = if (v == Math.floor(v)) v.toInt().toString() else num(v, 1)

    fun num(v: Double, digits: Int): String = if (v.isFinite()) String.format(Locale.ROOT, "%.${digits}f", v) else FinFormat.DASH

    fun group(v: Double): String = String.format(Locale.ROOT, "%,.2f", v)

    /** Four significant-ish digits: 156.87, 1.1199, 0.00638. */
    fun sig(v: Double): String = when {
        !v.isFinite() -> FinFormat.DASH
        Math.abs(v) >= 100.0 -> String.format(Locale.ROOT, "%.2f", v)
        Math.abs(v) >= 1.0 -> String.format(Locale.ROOT, "%.4f", v)
        else -> String.format(Locale.ROOT, "%.5f", v)
    }

    private fun change(last: Obs, prev: Obs?, scale: Double, digits: Int): String =
        if (prev == null) FinFormat.DASH else String.format(Locale.ROOT, "%+.${digits}f", (last.value - prev.value) * scale)

    private fun pct(last: Obs, prev: Obs?): Double? = if (prev == null || prev.value == 0.0) null else (last.value / prev.value - 1.0) * 100.0

    private fun pctChange(last: Obs, prev: Obs?): String = pct(last, prev)?.let { String.format(Locale.ROOT, "%+.2f", it) } ?: FinFormat.DASH
}

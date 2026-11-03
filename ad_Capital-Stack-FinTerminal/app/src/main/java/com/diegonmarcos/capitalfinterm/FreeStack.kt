package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/** A keyed source's key: the fleet secret the container reads ([env]), the phone's Config > Sources slot ([slot]), the request header. */
data class FreeKey(val env: String, val slot: String, val header: String, val signup: String, val reason: String)

/** A flagged source: off unless the flag is on, and then only after the [after] sources found nothing. */
data class FreeFlag(val env: String, val default: Boolean, val after: List<String>)

/** One source of universe.json `free_stack.sources` (#1416). */
data class FreeSource(
    val id: String, val name: String, val coverage: String, val licence: String, val terms: String,
    val endpoint: String, val path: String, val method: String, val params: Map<String, String>,
    val format: String, val formats: Map<String, String>, val perMin: Int, val perDay: Int?,
    val key: FreeKey?, val flag: FreeFlag?, val items: List<String>, val jobs: List<JSONObject>,
    val forms: List<String>, val concepts: List<String>, val keep: Int, val userAgent: Boolean, val groups: Map<String, List<String>>,
)

data class FreeStackDecl(val contact: String, val route: String, val sources: List<FreeSource>)

/** One parsed value: `fred:<ID>`, `px:<SYM>`, `div:<SYM>`, `split:<SYM>`, `ratio:<SYM>:<field>` or `fact:<CIK>:<concept>`. */
data class FreeObs(val id: String, val date: String, val value: Double)

data class Figi(val query: String, val figi: String?, val name: String?, val ticker: String?, val exch: String?, val sector: String?, val type: String?, val error: String?)

data class Filing(val form: String, val date: String, val accession: String, val document: String)

enum class FreeState { READY, NEEDS_KEY, FLAG_OFF }

/** The key a keyed source needs is missing: carries the declared reason, never a key. */
class NeedsKey(reason: String) : IOException(reason)

/**
 * #1416 the free, non-real-time stack (FRED, OpenFIGI, FMP, SEC EDGAR, Yahoo flagged), read from universe.json `free_stack`: the parsers
 * of its store formats (the same set as the container's store.rs STORE_FORMATS), the pacing gate, the key / flag state shown in
 * About > Data sources, and the direct fetch the APK uses when the container is out of reach. Kotlin names no address.
 */
object FreeStack {
    val FORMATS = listOf("fred_csv", "openfigi_map", "fmp_ratios", "sec_filings", "sec_facts", "yahoo_chart")

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun map(o: JSONObject?): Map<String, String> = o?.keys()?.asSequence()?.associateWith { o.optString(it) } ?: emptyMap()

    fun parse(universe: String): FreeStackDecl {
        val fs = JSONObject(universe).optJSONObject("free_stack") ?: return FreeStackDecl("", "", emptyList())
        val arr = fs.optJSONArray("sources") ?: JSONArray()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            val k = o.optJSONObject("key")
            val f = o.optJSONObject("flag")
            val g = o.optJSONObject("groups")
            val jobs = o.optJSONArray("jobs")
            FreeSource(
                id = o.getString("id"), name = o.optString("name"), coverage = o.optString("coverage"), licence = o.optString("licence"),
                terms = o.optString("terms"), endpoint = o.optString("endpoint"), path = o.optString("path"), method = o.optString("method", "GET"),
                params = map(o.optJSONObject("params")), format = o.optString("format"), formats = map(o.optJSONObject("formats")),
                perMin = o.optInt("per_min", 1), perDay = if (o.has("per_day")) o.getInt("per_day") else null,
                key = k?.let { FreeKey(it.optString("env"), it.optString("slot"), it.optString("header"), it.optString("signup"), it.optString("reason")) },
                flag = f?.let { FreeFlag(it.optString("env"), it.optBoolean("default", false), strs(it.optJSONArray("after"))) },
                items = strs(o.optJSONArray("items")),
                jobs = if (jobs == null) emptyList() else (0 until jobs.length()).map { jobs.getJSONObject(it) },
                forms = strs(o.optJSONArray("forms")), concepts = strs(o.optJSONArray("concepts")), keep = o.optInt("filings_keep", 40),
                userAgent = o.optBoolean("user_agent", false),
                groups = g?.keys()?.asSequence()?.associateWith { strs(g.optJSONArray(it)) } ?: emptyMap(),
            )
        }
        return FreeStackDecl(fs.optString("contact"), fs.optString("route"), list)
    }

    // ------------------------------------------------------------------ state shown in About > Data sources

    /** A keyed source without its key needs one; a flagged source is off unless its flag is on; else ready. */
    fun state(s: FreeSource, hasKey: Boolean, flagOn: Boolean = s.flag?.default ?: false): FreeState = when {
        s.key != null && !hasKey -> FreeState.NEEDS_KEY
        s.flag != null && !flagOn -> FreeState.FLAG_OFF
        else -> FreeState.READY
    }

    fun statusText(s: FreeSource, st: FreeState): String = when (st) {
        FreeState.READY -> "ready"
        FreeState.NEEDS_KEY -> "needs key: " + (s.key?.reason ?: "")
        FreeState.FLAG_OFF -> "off: feature flag (" + (s.flag?.env ?: "") + "), last fallback after " + (s.flag?.after ?: emptyList()).joinToString(", ")
    }

    fun rateText(s: FreeSource): String =
        (if (s.perMin % 60 == 0) "${s.perMin / 60}/s" else "${s.perMin}/min") + (s.perDay?.let { ", $it/day" } ?: "")

    fun keyText(s: FreeSource): String = if (s.key == null) "no key" else "free key (${s.key.signup})"

    // ------------------------------------------------------------------ pacing

    /** The minimum gap between two requests to one source: 60000 / per_min ms (SEC's 10 a second is 100 ms). */
    fun gapMs(perMin: Int): Long = 60_000L / maxOf(1, perMin)

    /** Spaces calls to one source by its declared budget: [acquire] waits until the gap since the previous call has passed. */
    class RateGate(perMin: Int, private val clock: () -> Long = System::currentTimeMillis, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {
        private val gap = gapMs(perMin)
        private var last: Long? = null

        @Synchronized fun acquire() {
            val prev = last
            if (prev != null) {
                val wait = prev + gap - clock()
                if (wait > 0) sleep(wait)
            }
            last = clock()
        }
    }

    // ------------------------------------------------------------------ the EOD chain

    /** Yahoo is the last fallback: the first non-empty answer of the flag's `after` sources wins, Yahoo only when all are empty and on. */
    fun eod(yahoo: FreeSource, answers: Map<String, List<FreeObs>>, flagOn: Boolean, fetchYahoo: () -> List<FreeObs>): Pair<String, List<FreeObs>>? {
        val order = yahoo.flag?.after ?: emptyList()
        for (id in order) answers[id]?.takeIf { it.isNotEmpty() }?.let { return id to it }
        if (state(yahoo, hasKey = true, flagOn = flagOn) != FreeState.READY) return null
        return fetchYahoo().takeIf { it.isNotEmpty() }?.let { yahoo.id to it }
    }

    // ------------------------------------------------------------------ parsers (one per store format)

    /** fredgraph.csv: `observation_date,<ID>` then `YYYY-MM-DD,value`; "." is a missing day. */
    fun fredCsv(body: String, id: String): List<FreeObs> = body.lineSequence().drop(1).mapNotNull { l ->
        val c = l.trim().split(',')
        if (c.size < 2 || !Regex("""\d{4}-\d{2}-\d{2}""").matches(c[0].trim())) return@mapNotNull null
        c[1].trim().toDoubleOrNull()?.takeIf { it.isFinite() }?.let { FreeObs("fred:$id", c[0].trim(), it) }
    }.toList()

    /** OpenFIGI /v3/mapping: one answer per job, in job order: `{data:[...]}` or `{warning|error}`. */
    fun openFigi(body: String, jobs: List<JSONObject>): List<Figi> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return jobs.indices.mapNotNull { i ->
            val q = jobs[i].optString("idType") + ":" + jobs[i].optString("idValue")
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val d = a.optJSONArray("data")?.optJSONObject(0)
            if (d == null) Figi(q, null, null, null, null, null, null, a.optString("warning").ifEmpty { a.optString("error").ifEmpty { "no match" } })
            else Figi(q, d.optString("figi").ifEmpty { null }, d.optString("name").ifEmpty { null }, d.optString("ticker").ifEmpty { null },
                d.optString("exchCode").ifEmpty { null }, d.optString("marketSector").ifEmpty { null }, d.optString("securityType").ifEmpty { null }, null)
        }
    }

    /** FMP /stable/ratios: `[{symbol, date, <ratio>: number, ...}]`; every finite number becomes `ratio:<SYM>:<field>`. */
    fun fmpRatios(body: String, symbol: String): List<FreeObs> {
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<FreeObs>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val date = r.optString("date").take(10)
            if (date.length != 10) continue
            for (k in r.keys()) {
                val v = r.opt(k)
                if (v is Number && v.toDouble().isFinite()) out += FreeObs("ratio:$symbol:$k", date, v.toDouble())
            }
        }
        return out
    }

    /** EDGAR submissions: `filings.recent` column arrays; only the declared forms, newest first, at most [keep]. */
    fun secFilings(body: String, forms: List<String>, keep: Int): List<Filing> {
        val r = runCatching { JSONObject(body).getJSONObject("filings").getJSONObject("recent") }.getOrNull() ?: return emptyList()
        val f = r.optJSONArray("form") ?: return emptyList()
        val d = r.optJSONArray("filingDate"); val a = r.optJSONArray("accessionNumber"); val p = r.optJSONArray("primaryDocument")
        return (0 until f.length()).filter { f.optString(it) in forms }
            .map { Filing(f.optString(it), d?.optString(it).orEmpty(), a?.optString(it).orEmpty(), p?.optString(it).orEmpty()) }
            .sortedByDescending { it.date }.take(keep)
    }

    /** EDGAR XBRL companyfacts: `facts.us-gaap.<concept>.units.<unit>[{end, val, form}]`, declared concepts and forms only. */
    fun secFacts(body: String, cik: String, concepts: List<String>, forms: List<String>): List<FreeObs> {
        val g = runCatching { JSONObject(body).getJSONObject("facts").getJSONObject("us-gaap") }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<Pair<String, String>, FreeObs>()
        for (c in concepts) {
            val units = g.optJSONObject(c)?.optJSONObject("units") ?: continue
            for (u in units.keys()) {
                val rows = units.optJSONArray(u) ?: continue
                for (i in 0 until rows.length()) {
                    val x = rows.optJSONObject(i) ?: continue
                    if (x.optString("form") !in forms) continue
                    val end = x.optString("end")
                    val v = x.optDouble("val")
                    if (end.length == 10 && v.isFinite()) out[c to end] = FreeObs("fact:$cik:$c", end, v)
                }
            }
        }
        return out.values.toList()
    }

    /** Yahoo v8 chart: closes per timestamp (`px:`), `events.dividends` (`div:`) and `events.splits` (`split:` = numerator / denominator). */
    fun yahooChart(body: String, symbol: String): List<FreeObs> {
        val r = runCatching { JSONObject(body).getJSONObject("chart").getJSONArray("result").getJSONObject(0) }.getOrNull() ?: return emptyList()
        fun day(ts: Long) = java.time.Instant.ofEpochSecond(ts).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val out = ArrayList<FreeObs>()
        val t = r.optJSONArray("timestamp")
        val close = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        if (t != null && close != null) for (i in 0 until t.length()) {
            val c = close.optDouble(i)
            if (c.isFinite() && c > 0) out += FreeObs("px:$symbol", day(t.getLong(i)), c)
        }
        val ev = r.optJSONObject("events")
        val dv = ev?.optJSONObject("dividends")
        if (dv != null) for (k in dv.keys()) {
            val x = dv.optJSONObject(k) ?: continue
            val a = x.optDouble("amount")
            if (a.isFinite()) out += FreeObs("div:$symbol", day(x.optLong("date")), a)
        }
        val sp = ev?.optJSONObject("splits")
        if (sp != null) for (k in sp.keys()) {
            val x = sp.optJSONObject(k) ?: continue
            val n = x.optDouble("numerator"); val dd = x.optDouble("denominator")
            if (n.isFinite() && dd.isFinite() && dd != 0.0) out += FreeObs("split:$symbol", day(x.optLong("date")), n / dd)
        }
        return out
    }

    // ------------------------------------------------------------------ direct fetch (the APK's own path)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** endpoint + path + params with `{code}` / `{cik}` filled and every parameter percent-encoded. */
    fun url(s: FreeSource, path: String, slots: Map<String, String>): String {
        fun fill(x: String) = slots.entries.fold(x) { acc, (k, v) -> acc.replace("{$k}", v) }
        val base = s.endpoint.trimEnd('/') + fill(path)
        val q = s.params.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(fill(v)) }
        return if (q.isEmpty()) base else "$base?$q"
    }

    /** The headers of one request: the honest fleet User-Agent with its contact (never a browser's), and a keyed source's key header. */
    fun headers(s: FreeSource, contact: String, key: String?): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        h["User-Agent"] = UrlHttp.AGENT + (if (contact.isNotEmpty()) " ($contact)" else "")
        if (s.key != null) h[s.key.header] = key ?: throw NeedsKey(s.key.reason)
        return h
    }

    /** One direct request for [item] (a FRED id, ticker or CIK; ignored for OpenFIGI, which posts its declared jobs) in [format]. */
    fun fetch(s: FreeSource, format: String, item: String, http: Http, gate: RateGate, contact: String, key: String?): String {
        val h = headers(s, contact, key)
        val path = s.formats[format] ?: s.path
        val u = url(s, path, mapOf("code" to item, "cik" to item))
        gate.acquire()
        return if (s.method == "POST") http.send(u, h + ("Content-Type" to "application/json"), JSONArray(s.jobs.take(maxOf(1, s.jobs.size))).toString())
        else http.send(u, h, null)
    }
}

package com.diegonmarcos.capitalfinterm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToLong
import org.json.JSONObject

/** #1539 one futures root of data/quotes.json: its spec (multiplier, tick, months, listed expiries, expiry rule) and the free-source symbols (null = no free source). */
data class FutSpec(
    val id: String, val root: String, val yahooRoot: String, val name: String, val exchange: String, val suffix: String?, val front: String?, val months: String, val listed: Int,
    val multiplier: Double, val tick: Double, val unit: String, val currency: String, val expiry: String, val stir: Boolean, val face: Double?, val sizeUnit: String, val hedge: String, val tenor: String, val cash: Boolean,
)
data class FutClass(val id: String, val label: String, val roots: List<FutSpec>)
data class Ccy(val id: String, val name: String, val symbol: String?, val usdPer: Boolean)
data class CommodityDecl(val id: String, val name: String, val group: String, val root: String)
data class ExpiryRule(val kind: String, val weekday: Int, val n: Int, val bdaysBefore: Int, val monthOffset: Int, val count: Int, val day: Int, val days: Int, val note: String)
data class QuoteSource(val label: String, val url: String, val userAgent: String, val delay: String)

data class QuotesDecl(
    val source: QuoteSource, val monthCodes: Map<Char, Int>, val rules: Map<String, ExpiryRule>, val ccys: List<Ccy>, val defaultBase: String, val commodities: List<CommodityDecl>,
    val classes: List<FutClass>, val heat: Map<String, Double>, val ttlSeconds: Long, val defaultRatio: Double, val ui: Map<String, String>,
) {
    fun word(k: String): String = ui[k].orEmpty()
    fun spec(root: String): FutSpec? = classes.flatMap { it.roots }.firstOrNull { it.id == root }

    companion object {
        fun parse(json: String): QuotesDecl {
            val o = JSONObject(json)
            val s = o.getJSONObject("source")
            val mc = o.getJSONObject("month_codes")
            val er = o.getJSONObject("expiry_rules")
            val cur = o.getJSONObject("fx").getJSONArray("currencies")
            val com = o.getJSONArray("commodities")
            val cl = o.getJSONObject("futures").getJSONArray("classes")
            val heat = o.getJSONObject("heat")
            val ui = o.getJSONObject("ui")
            return QuotesDecl(
                QuoteSource(s.getString("label"), s.getString("url"), s.getString("user_agent"), s.getString("delay")),
                mc.keys().asSequence().associate { it[0] to mc.getInt(it) },
                er.keys().asSequence().associateWith { k -> er.getJSONObject(k).let { ExpiryRule(it.getString("kind"), it.optInt("weekday"), it.optInt("n"), it.optInt("bdays_before"), it.optInt("month_offset"), it.optInt("count"), it.optInt("day"), it.optInt("days"), it.optString("note")) } },
                (0 until cur.length()).map { cur.getJSONObject(it) }.map { Ccy(it.getString("id"), it.getString("name"), it.optString("symbol").ifEmpty { null }, it.optString("quote") == "usd_per") },
                o.getJSONObject("fx").getString("default_base"),
                (0 until com.length()).map { com.getJSONObject(it) }.map { CommodityDecl(it.getString("id"), it.getString("name"), it.getString("group"), it.getString("root")) },
                (0 until cl.length()).map { cl.getJSONObject(it) }.map { c ->
                    val rs = c.getJSONArray("roots")
                    FutClass(c.getString("id"), c.getString("label"), (0 until rs.length()).map { rs.getJSONObject(it) }.map { r ->
                        FutSpec(r.getString("id"), r.getString("root"), r.optString("yahoo_root").ifEmpty { r.getString("root") }, r.getString("name"), r.getString("exchange"),
                            if (r.isNull("suffix")) null else r.getString("suffix").ifEmpty { null }, if (r.isNull("front")) null else r.getString("front").ifEmpty { null }, r.getString("months"), r.getInt("listed"),
                            r.getDouble("multiplier"), r.getDouble("tick"), r.getString("unit"), r.getString("currency"), r.getString("expiry"), r.optBoolean("stir"),
                            if (r.has("face")) r.getDouble("face") else null, r.optString("size_unit"), r.optString("hedge"), r.optString("tenor"), r.optBoolean("cash"))
                    })
                },
                heat.keys().asSequence().filter { !it.startsWith("_") }.associateWith { heat.getDouble(it) }, o.getLong("refresh_ttl_s"), o.getJSONObject("hedge").getDouble("default_ratio"),
                ui.keys().asSequence().associateWith { ui.getString(it) },
            )
        }
    }
}

/** One delayed quote with the source's own timestamp ([asof], epoch seconds). */
data class Quote(
    val last: Double, val asof: Long, val exchange: String, val currency: String, val name: String,
    val prev: Double?, val chg: Double?, val pct: Double?, val w1: Double?, val m1: Double?, val ytd: Double?, val vol: Long?,
)

data class QuotesSnapshot(val generated: String, val source: String, val delay: String, val quotes: Map<String, Quote>) {
    /** Newer answers (a refresh) over this snapshot: the quote with the later timestamp wins. */
    fun overlay(live: Map<String, Quote>): QuotesSnapshot = copy(quotes = quotes + live.filter { (k, v) -> v.asof >= (quotes[k]?.asof ?: 0L) })

    companion object {
        private fun d(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
        fun quote(o: JSONObject): Quote = Quote(o.getDouble("last"), o.getLong("asof"), o.optString("exchange"), o.optString("currency"), o.optString("name"), d(o, "prev"), d(o, "chg"), d(o, "pct"), d(o, "w1"), d(o, "m1"), d(o, "ytd"), if (o.has("vol")) o.getLong("vol") else null)
        fun parse(json: String): QuotesSnapshot {
            val r = JSONObject(json); val q = r.getJSONObject("quotes")
            return QuotesSnapshot(r.getString("generated"), r.getString("source"), r.getString("delay"), q.keys().asSequence().associateWith { quote(q.getJSONObject(it)) })
        }
    }
}

/** The expiry of a contract by its declared rule, on a weekday calendar (exchange holidays are not applied). */
object Expiry {
    fun isBday(d: LocalDate): Boolean = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    fun addBdays(from: LocalDate, n: Int): LocalDate {
        var d = from; var left = abs(n); val step = if (n < 0) -1L else 1L
        while (left > 0) { d = d.plusDays(step); if (isBday(d)) left-- }
        return d
    }

    private fun lastBday(ym: YearMonth): LocalDate { var d = ym.atEndOfMonth(); while (!isBday(d)) d = d.minusDays(1); return d }
    private fun nthWeekday(ym: YearMonth, dow: DayOfWeek, n: Int): LocalDate { var d = ym.atDay(1); while (d.dayOfWeek != dow) d = d.plusDays(1); return d.plusWeeks((n - 1).toLong()) }
    private fun lastWeekday(ym: YearMonth, dow: DayOfWeek): LocalDate { var d = ym.atEndOfMonth(); while (d.dayOfWeek != dow) d = d.minusDays(1); return d }

    fun of(rule: ExpiryRule, year: Int, month: Int): LocalDate {
        val ym = YearMonth.of(year, month).plusMonths(rule.monthOffset.toLong())
        val dow = DayOfWeek.of(rule.weekday + 1)
        return when (rule.kind) {
            "nth_weekday" -> nthWeekday(ym, dow, rule.n).let { if (rule.bdaysBefore > 0) addBdays(it, -rule.bdaysBefore) else it }
            "last_weekday" -> lastWeekday(ym, dow)
            "last_bday" -> lastBday(ym)
            "bdays_before_last_bday" -> addBdays(lastBday(ym), -rule.count)
            "bdays_before_first" -> addBdays(ym.atDay(1), -rule.count)
            "bdays_before_day" -> { var b = ym.atDay(rule.day); while (!isBday(b)) b = b.minusDays(1); addBdays(b, -rule.count) }
            "nth_bday" -> addBdays(ym.atDay(1).minusDays(1), rule.n)
            "days_before_nth_weekday" -> nthWeekday(ym, dow, rule.n).minusDays(rule.days.toLong())
            else -> ym.atEndOfMonth()
        }
    }
}

/** A listed contract: its month code and year, the source symbol and its expiry by the declared rule. */
data class Contract(val code: Char, val year: Int, val month: Int, val symbol: String, val expiry: LocalDate) {
    /** e.g. CLZ26 */
    fun label(root: String): String = "$root$code${String.format(Locale.ROOT, "%02d", year % 100)}"
}

object Quotes {
    /** The root's next [FutSpec.listed] contracts that have not expired by [today] (month codes of the spec, symbol = root + code + yy + '.' + suffix); empty for a root with no free source. */
    fun contracts(decl: QuotesDecl, spec: FutSpec, today: LocalDate): List<Contract> {
        val suffix = spec.suffix ?: return emptyList()
        if (spec.listed <= 0) return emptyList()
        val rule = decl.rules[spec.expiry] ?: return emptyList()
        val inv = decl.monthCodes.entries.associate { it.value to it.key }
        val out = ArrayList<Contract>()
        var ym = YearMonth.from(today)
        var guard = 0
        while (out.size < spec.listed && guard++ < 12 * 6) {
            val code = inv.getValue(ym.monthValue)
            if (code in spec.months) {
                val e = Expiry.of(rule, ym.year, ym.monthValue)
                if (!e.isBefore(today)) out += Contract(code, ym.year, ym.monthValue, "${spec.yahooRoot}$code${String.format(Locale.ROOT, "%02d", ym.year % 100)}.$suffix", e)
            }
            ym = ym.plusMonths(1)
        }
        return out
    }

    /** The symbols a view needs (what a refresh asks the source for): the FX legs, or a class's fronts and contracts. */
    fun symbolsFx(decl: QuotesDecl): List<String> = decl.ccys.mapNotNull { it.symbol }
    fun symbolsClass(decl: QuotesDecl, cls: FutClass, today: LocalDate): List<String> =
        cls.roots.flatMap { r -> listOfNotNull(r.front) + contracts(decl, r, today).map { it.symbol } }.distinct()
    fun symbolsCommodities(decl: QuotesDecl): List<String> = decl.commodities.mapNotNull { decl.spec(it.root)?.front }

    private fun utc(t: Long): LocalDate = Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).toLocalDate()

    /** Yahoo chart answer -> the quote: last = the source's market price, prev = the previous daily close, 1W / 1M / YTD = % change of the daily close over 5 / 21 trading days and since the year's last close. */
    fun parseChart(text: String): Quote? {
        val r = runCatching { JSONObject(text).optJSONObject("chart")?.optJSONArray("result")?.optJSONObject(0) }.getOrNull() ?: return null
        val m = r.optJSONObject("meta") ?: return null
        val ts = r.optJSONArray("timestamp")
        val cs = r.optJSONObject("indicators")?.optJSONArray("quote")?.optJSONObject(0)?.optJSONArray("close")
        val cl = if (ts == null || cs == null) emptyList() else (0 until minOf(ts.length(), cs.length())).filter { !cs.isNull(it) }.map { ts.getLong(it) to cs.getDouble(it) }
        val last = if (m.has("regularMarketPrice") && !m.isNull("regularMarketPrice")) m.getDouble("regularMarketPrice") else cl.lastOrNull()?.second ?: return null
        val asof = if (m.has("regularMarketTime") && !m.isNull("regularMarketTime")) m.getLong("regularMarketTime") else cl.lastOrNull()?.first ?: return null
        val same = cl.isNotEmpty() && utc(cl.last().first) == utc(asof)
        val base = if (same) cl.dropLast(1) else cl
        val prev = base.lastOrNull()?.second?.takeIf { it != 0.0 }
        fun over(ago: Int): Double? = base.getOrNull(base.size - ago)?.second?.takeIf { it != 0.0 }?.let { (last / it - 1.0) * 100.0 }
        val year = utc(asof).year
        val ytdBase = base.filter { utc(it.first).year < year }.lastOrNull()?.second?.takeIf { it != 0.0 }
        fun r6(v: Double) = (v * 1e6).roundToLong() / 1e6
        return Quote(
            r6(last), asof, m.optString("fullExchangeName").ifEmpty { m.optString("exchangeName") }, m.optString("currency"), m.optString("longName").ifEmpty { m.optString("shortName") },
            prev?.let { r6(it) }, prev?.let { r6(last - it) }, prev?.let { (last / it - 1.0) * 100.0 }, over(5), over(21), ytdBase?.let { (last / it - 1.0) * 100.0 },
            if (m.has("regularMarketVolume") && !m.isNull("regularMarketVolume")) m.getLong("regularMarketVolume").takeIf { it > 0 } else null,
        )
    }

    // ---- formatting -------------------------------------------------------------------------------------------------
    fun decimals(tick: Double): Int = if (tick <= 0.0) 2 else max(0, minOf(6, ceil(-log10(tick) - 1e-9).toInt()))
    fun price(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)
    fun signed(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%+.${decimals}f", v)
    fun fmtPct(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%+.2f", it) }
    fun date(d: LocalDate): String = d.toString()
    fun time(epoch: Long): String = Instant.ofEpochSecond(epoch).toString().replace("T", " ").removeSuffix("Z").take(16) + " UTC"
    private fun tint(decl: QuotesDecl, key: String, v: Double?): Float? = v?.let { (it / (decl.heat[key] ?: 1.0)).coerceIn(-1.0, 1.0).toFloat() }

    // ---- the FX table and matrix (every currency is read through its USD leg: one source, one set of numbers) --------------
    /** Units of currency [c] per 1 USD, from its USD leg; null when the leg has no quote. USD itself is 1. */
    fun perUsd(decl: QuotesDecl, snap: QuotesSnapshot, c: String): Double? {
        val ccy = decl.ccys.firstOrNull { it.id == c } ?: return null
        if (ccy.symbol == null) return if (c == "USD") 1.0 else null
        val q = snap.quotes[ccy.symbol] ?: return null
        return if (ccy.usdPer) 1.0 / q.last else q.last
    }

    /** The % change of a currency's perUsd over a window, from its leg's own % change (a 1/x leg moves by 1/(1+p)-1). */
    private fun legChange(decl: QuotesDecl, snap: QuotesSnapshot, c: String, pick: (Quote) -> Double?): Double? {
        val ccy = decl.ccys.firstOrNull { it.id == c } ?: return null
        if (ccy.symbol == null) return if (c == "USD") 0.0 else null
        val p = pick(snap.quotes[ccy.symbol] ?: return null) ?: return null
        return if (ccy.usdPer) ((1.0 / (1.0 + p / 100.0)) - 1.0) * 100.0 else p
    }

    /** Units of [quote] per 1 [base]. */
    fun cross(decl: QuotesDecl, snap: QuotesSnapshot, base: String, quote: String): Double? {
        val b = perUsd(decl, snap, base) ?: return null
        val q = perUsd(decl, snap, quote) ?: return null
        return q / b
    }

    /** % change of the cross [quote] per [base] over a window: the ratio of the two legs' factors. */
    fun crossChange(decl: QuotesDecl, snap: QuotesSnapshot, base: String, quote: String, pick: (Quote) -> Double?): Double? {
        val b = legChange(decl, snap, base, pick) ?: return null
        val q = legChange(decl, snap, quote, pick) ?: return null
        return ((1.0 + q / 100.0) / (1.0 + b / 100.0) - 1.0) * 100.0
    }

    private fun fxDec(v: Double): Int = if (v >= 100) 2 else if (v >= 10) 3 else if (v >= 1) 4 else 5

    /** The list: every other currency against [base]: spot (units per 1 base), change and %, 1W / 1M / YTD %. */
    fun fxTable(decl: QuotesDecl, snap: QuotesSnapshot, base: String): CbTable {
        val na = decl.word("na")
        val header = listOf("", decl.word("spot"), decl.word("chg"), decl.word("pct"), decl.word("w1"), decl.word("m1"), decl.word("ytd"))
        val rows = ArrayList<List<String>>(); val heat = ArrayList<List<Float?>>(); val ids = ArrayList<String>()
        for (c in decl.ccys.filter { it.id != base }) {
            val s = cross(decl, snap, base, c.id)
            val p = crossChange(decl, snap, base, c.id) { it.pct }
            val pctv = listOf(p, crossChange(decl, snap, base, c.id) { it.w1 }, crossChange(decl, snap, base, c.id) { it.m1 }, crossChange(decl, snap, base, c.id) { it.ytd })
            val chg = if (s != null && p != null) s - s / (1.0 + p / 100.0) else null
            rows += listOf(c.id + " " + c.name, s?.let { price(it, fxDec(it)) } ?: na, chg?.let { signed(it, fxDec(s!!)) } ?: na) + pctv.map { fmtPct(it) ?: na }
            heat += listOf<Float?>(null, null, null, tint(decl, "pct", pctv[0]), tint(decl, "w1", pctv[1]), tint(decl, "m1", pctv[2]), tint(decl, "ytd", pctv[3])); ids += c.id
        }
        return CbTable(header, rows, heat, ids, 0)
    }

    /** The matrix: base (rows) x quote (columns), units of quote per 1 base; the diagonal is 1. */
    fun fxMatrix(decl: QuotesDecl, snap: QuotesSnapshot): CbTable {
        val na = decl.word("na")
        val ids = decl.ccys.map { it.id }
        val rows = ids.map { b -> listOf(b) + ids.map { q -> cross(decl, snap, b, q)?.let { v -> price(v, fxDec(v)) } ?: na } }
        return CbTable(listOf("") + ids, rows, rows.map { emptyList() }, ids, 0)
    }

    // ---- commodity prices and the futures board --------------------------------------------------------------------------
    fun commodityTable(decl: QuotesDecl, snap: QuotesSnapshot): CbTable {
        val na = decl.word("na")
        val header = listOf("", decl.word("last"), decl.word("chg"), decl.word("pct"), decl.word("w1"), decl.word("m1"), decl.word("ytd"), decl.word("unit"), decl.word("ccy"))
        val rows = ArrayList<List<String>>(); val heat = ArrayList<List<Float?>>(); val ids = ArrayList<String>()
        for (c in decl.commodities) {
            val spec = decl.spec(c.root); val q = spec?.front?.let { snap.quotes[it] }
            val dec = decimals(spec?.tick ?: 0.01)
            rows += listOf(c.name, q?.let { price(it.last, dec) } ?: na, q?.chg?.let { signed(it, dec) } ?: na, fmtPct(q?.pct) ?: na, fmtPct(q?.w1) ?: na, fmtPct(q?.m1) ?: na, fmtPct(q?.ytd) ?: na, spec?.unit ?: na, spec?.currency ?: na)
            heat += listOf<Float?>(null, null, null, tint(decl, "pct", q?.pct), tint(decl, "w1", q?.w1), tint(decl, "m1", q?.m1), tint(decl, "ytd", q?.ytd), null, null); ids += c.id
        }
        return CbTable(header, rows, heat, ids, 0)
    }

    /** The front contract a root's board row shows: the nearest listed contract that has a quote, else the continuous front symbol. */
    fun front(decl: QuotesDecl, snap: QuotesSnapshot, spec: FutSpec, today: LocalDate): Pair<String, Quote?>? {
        val c = contracts(decl, spec, today).firstOrNull { snap.quotes.containsKey(it.symbol) }
        if (c != null) return c.label(spec.yahooRoot) + " " + Quotes.date(c.expiry) to snap.quotes[c.symbol]
        val f = spec.front ?: return null
        return f to snap.quotes[f]
    }

    /** One class of the futures board: a row per root (its front contract: root + month code + year + expiry), the exchange, last, change, %, volume, open interest and settlement (n/a: no free source). */
    fun board(decl: QuotesDecl, snap: QuotesSnapshot, cls: FutClass, today: LocalDate): CbTable {
        val na = decl.word("na")
        val header = listOf("", decl.word("exchange"), decl.word("last"), decl.word("chg"), decl.word("pct"), decl.word("vol"), decl.word("oi"), decl.word("settle"))
        val rows = ArrayList<List<String>>(); val heat = ArrayList<List<Float?>>(); val ids = ArrayList<String>()
        for (r in cls.roots) {
            val f = front(decl, snap, r, today); val q = f?.second; val dec = decimals(r.tick)
            val name = r.name + (if (f != null && q != null) "  " + f.first else "")
            rows += listOf(name, r.exchange, q?.let { price(it.last, dec) } ?: na, q?.chg?.let { signed(it, dec) } ?: na, fmtPct(q?.pct) ?: na, q?.vol?.toString() ?: na, na, na)
            heat += listOf<Float?>(null, null, null, null, tint(decl, "pct", q?.pct), null, null, null); ids += r.id
        }
        return CbTable(header, rows, heat, ids, 0)
    }

    /** The shape of a term structure: contango (the last listed contract above the front), backwardation (below) or flat (within 0.1 %). */
    fun shape(prices: List<Double>): String? {
        if (prices.size < 2 || prices.first() == 0.0) return null
        val d = (prices.last() / prices.first() - 1.0) * 100.0
        return if (d > 0.1) "contango" else if (d < -0.1) "backwardation" else "flat"
    }

    /** The implied rate of a rate future: 100 minus the price. */
    fun implied(price: Double): Double = 100.0 - price

    data class Term(val rows: List<List<String>>, val prices: List<Double>, val labels: List<String>, val shape: String?, val header: List<String>)

    /** All listed expiries of a root that have a quote: contract, expiry, last, the calendar spread to the previous contract, the implied rate (rate futures), volume. */
    fun term(decl: QuotesDecl, snap: QuotesSnapshot, spec: FutSpec, today: LocalDate): Term {
        val na = decl.word("na"); val dec = decimals(spec.tick)
        val cs = contracts(decl, spec, today).filter { snap.quotes.containsKey(it.symbol) }
        val header = listOf(decl.word("contract"), decl.word("expiry"), decl.word("last"), decl.word("spread"), if (spec.stir) decl.word("implied") else decl.word("pct"), decl.word("vol"))
        val rows = ArrayList<List<String>>(); val prices = ArrayList<Double>()
        for ((i, c) in cs.withIndex()) {
            val q = snap.quotes.getValue(c.symbol)
            val spread = if (i > 0) q.last - snap.quotes.getValue(cs[i - 1].symbol).last else null
            rows += listOf(c.label(spec.yahooRoot), date(c.expiry), price(q.last, dec), spread?.let { signed(it, dec) } ?: na, if (spec.stir) String.format(Locale.ROOT, "%.3f", implied(q.last)) else fmtPct(q.pct) ?: na, q.vol?.toString() ?: na)
            prices += q.last
        }
        return Term(rows, prices, cs.map { it.label(spec.yahooRoot) }, shape(prices), header)
    }

    // ---- the hedge calculator --------------------------------------------------------------------------------------------
    /** The notional of one contract: the contract size for an FX future (in its size unit), the face of a rate future, else multiplier x price. */
    fun notionalPerContract(spec: FutSpec, price: Double): Double = if (spec.sizeUnit.isNotEmpty()) spec.multiplier else spec.face ?: (spec.multiplier * price)

    data class Hedge(val exact: Double, val contracts: Long, val hedged: Double, val residual: Double, val tickValue: Double)

    /** Contracts = exposure x ratio% / notional per contract, rounded to a whole contract; the hedged notional is contracts x notional, the residual what stays open. */
    fun hedge(spec: FutSpec, price: Double, exposure: Double, ratioPct: Double): Hedge {
        val n = notionalPerContract(spec, price)
        val target = exposure * ratioPct / 100.0
        val exact = if (n > 0.0) target / n else 0.0
        val c = exact.roundToLong()
        return Hedge(exact, c, c * n, target - c * n, spec.multiplier * spec.tick)
    }
}

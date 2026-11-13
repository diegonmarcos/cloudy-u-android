package com.diegonmarcos.capitalfinterm

import java.net.URLEncoder
import java.util.TreeMap
import kotlin.math.pow
import org.json.JSONObject

/**
 * #1727 where a country without a listed bond fund gets its government bond return (data/portfolios.json `sovereign`): the fleet container's route
 * first ([primarySource], [api]), else the same arithmetic on public 10-year yields ([fallbackSource], [path], one series per country by [series] and
 * [a3]). [periods] maps a page period to the window it answers (1M to m1, YTD to ytd).
 */
data class SovDecl(
    val primarySource: String, val api: String, val fallbackSource: String, val path: String, val series: String, val params: Map<String, String>,
    val tenorYears: Double, val a3: Map<String, String>, val periods: Map<String, String>, val label: String,
)

/** One country's local-currency government bond returns in % by window (m1, ytd, y1), the month they run to and who answered. */
data class SovRow(val country: String, val asOf: String, val local: Map<String, Double>, val source: String)

/**
 * #1727 sovereign bond returns from yields. Pure rules (no network): the declaration, the yield and duration arithmetic of universe.json
 * `sovereign_returns.method` (the container computes the same), the parsers of the container's answer and of the public yield series.
 */
object SovereignReturns {
    const val M1 = "m1"
    const val YTD = "ytd"
    const val Y1 = "y1"

    fun parseDecl(portfoliosJson: String): SovDecl? = runCatching {
        val s = JSONObject(portfoliosJson).getJSONObject("sovereign")
        val p = s.getJSONObject("primary"); val f = s.getJSONObject("fallback")
        fun map(o: JSONObject?): Map<String, String> = if (o == null) emptyMap() else o.keys().asSequence().associateWith { o.optString(it) }
        SovDecl(p.getString("source"), p.getString("api"), f.getString("source"), f.getString("path"), f.getString("series"), map(f.optJSONObject("params")),
            s.optDouble("tenor_years", 10.0), map(s.optJSONObject("a3")), map(s.optJSONObject("periods")), s.optString("label"))
    }.getOrNull()

    /** The modified duration of a par bond of [years] with semiannual coupons at the yield [y] (a fraction): (1 - (1 + y/2)^(-2n)) / y; [years] at a zero yield. */
    fun parDuration(y: Double, years: Double): Double = if (kotlin.math.abs(y) < 1e-9) years else (1.0 - (1.0 + y / 2.0).pow(-2.0 * years)) / y

    fun nextMonth(m: String): String {
        val y = m.take(4).toIntOrNull() ?: return m
        val mo = m.drop(5).take(2).toIntOrNull() ?: return m
        return if (mo == 12) String.format(java.util.Locale.ROOT, "%04d-01", y + 1) else String.format(java.util.Locale.ROOT, "%04d-%02d", y, mo + 1)
    }

    /**
     * The monthly local-currency returns (fractions) of a constant-maturity bond from its yields (`YYYY-MM` to %): r(t) = y(t-1)/12 - D(t-1) x (y(t) - y(t-1)),
     * keyed by the month t; only consecutive months.
     */
    fun monthly(yields: Map<String, Double>, years: Double): TreeMap<String, Double> {
        val out = TreeMap<String, Double>()
        for ((m, y0) in yields) {
            val n = nextMonth(m)
            val y1 = yields[n] ?: continue
            val a = y0 / 100.0; val b = y1 / 100.0
            out[n] = a / 12.0 - parDuration(a, years) * (b - a)
        }
        return out
    }

    private fun prevMonth(m: String): String {
        val y = m.take(4).toIntOrNull() ?: return m
        val mo = m.drop(5).take(2).toIntOrNull() ?: return m
        return if (mo == 1) String.format(java.util.Locale.ROOT, "%04d-12", y - 1) else String.format(java.util.Locale.ROOT, "%04d-%02d", y, mo - 1)
    }

    /** The compounded return in % over the [months] ending at the newest month; null when a month of the window is missing. */
    fun window(monthly: TreeMap<String, Double>, months: Int): Double? {
        if (monthly.isEmpty() || months <= 0) return null
        var m = monthly.lastKey()
        var growth = 1.0
        repeat(months) {
            growth *= 1.0 + (monthly[m] ?: return null)
            m = prevMonth(m)
        }
        return (growth - 1.0) * 100.0
    }

    /** The windows of a country: the last month, the year to date (January to the newest month of its year) and the last 12 months, each when complete. */
    fun windows(monthly: TreeMap<String, Double>): Map<String, Double> {
        if (monthly.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Double>()
        window(monthly, 1)?.let { out[M1] = it }
        val months = monthly.lastKey().drop(5).take(2).toIntOrNull() ?: 0
        window(monthly, months)?.let { out[YTD] = it }
        window(monthly, 12)?.let { out[Y1] = it }
        return out
    }

    /** The request of the public yield series: the declared path with every country's series id and the declared parameters. */
    fun fallbackUrl(decl: SovDecl, endpoint: String): String {
        val ids = decl.a3.values.joinToString(",") { decl.series.replace("{a3}", it) }
        val q = (listOf("series_ids" to ids) + decl.params.toList()).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        return endpoint.trimEnd('/') + decl.path + "?" + q
    }

    /** The public yield answer (DBnomics `series.docs[]`: `series_code`, `period[]`, `value[]`) as returns by country; a country without two consecutive months is left out. */
    fun parseYields(json: String, decl: SovDecl, source: String): Map<String, SovRow> {
        val docs = runCatching { JSONObject(json).getJSONObject("series").getJSONArray("docs") }.getOrNull() ?: return emptyMap()
        val byA3 = decl.a3.entries.associate { it.value to it.key }
        val out = LinkedHashMap<String, SovRow>()
        for (i in 0 until docs.length()) {
            val d = docs.optJSONObject(i) ?: continue
            val cc = byA3[d.optString("series_code").substringBefore('.')] ?: continue
            val ps = d.optJSONArray("period") ?: continue
            val vs = d.optJSONArray("value") ?: continue
            val yields = TreeMap<String, Double>()
            for (k in 0 until minOf(ps.length(), vs.length())) {
                val v = vs.optDouble(k, Double.NaN)
                val m = ps.optString(k)
                if (v.isFinite() && m.length >= 7) yields[m.take(7)] = v
            }
            val mo = monthly(yields, decl.tenorYears)
            val w = windows(mo)
            if (w.isNotEmpty()) out[cc] = SovRow(cc, mo.lastKey(), w, source)
        }
        return out
    }

    /** The container's answer (`rows[]`: `country`, `last_month`, `local` {m1, ytd, y1} as fractions) as returns in % by country. */
    fun parseApi(json: String, source: String): Map<String, SovRow> {
        val rows = runCatching { JSONObject(json).getJSONArray("rows") }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, SovRow>()
        for (i in 0 until rows.length()) {
            val r = rows.optJSONObject(i) ?: continue
            val local = r.optJSONObject("local") ?: continue
            val w = LinkedHashMap<String, Double>()
            for (k in local.keys()) if (!local.isNull(k)) local.optDouble(k, Double.NaN).takeIf { it.isFinite() }?.let { w[k] = it * 100.0 }
            val cc = r.optString("country")
            if (cc.isNotBlank() && w.isNotEmpty()) out[cc] = SovRow(cc, r.optString("last_month"), w, source)
        }
        return out
    }

    /** What the performance pages read: country to page period to the local-currency bond return in %, by the declared period map. */
    fun byPeriod(rows: Map<String, SovRow>, decl: SovDecl): Map<String, Map<String, Double>> =
        rows.mapValues { (_, r) -> decl.periods.mapNotNull { (period, key) -> r.local[key]?.let { period to it } }.toMap() }
}

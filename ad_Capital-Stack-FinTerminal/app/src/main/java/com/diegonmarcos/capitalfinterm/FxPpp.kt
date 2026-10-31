package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import java.time.LocalDate

/**
 * FX vs PPP (#1374), the same maths as the container's /v1/fx/ppp (store.rs fx_ppp_row), for the direct-fetch fallback.
 * Rates are local currency per USD; series are (date or year, value), oldest first.
 */
object FxPpp {
    /** PPP carried from its base year by relative inflation: ppp0 * (home CPI growth / US CPI growth). */
    fun pppToday(ppp0: Double, home0: Double, home1: Double, us0: Double, us1: Double): Double = ppp0 * (home1 / home0) / (us1 / us0)

    /** A year-ago rate in today's prices: the rate that leaves the real exchange rate unchanged after a year of home vs US inflation. */
    fun realAdjust(rate: Double, home0: Double, home1: Double, us0: Double, us1: Double): Double = rate * (home1 / home0) / (us1 / us0)

    fun pct(a: Double, b: Double): Double = (a / b - 1.0) * 100.0

    /** Mean over the [days] calendar days ending at [end]; null when the window holds no observation. */
    fun windowAvg(s: List<Pair<String, Double>>, end: LocalDate, days: Long): Double? {
        val a = end.minusDays(maxOf(days - 1, 0)).toString()
        val b = end.toString()
        val v = s.filter { it.first in a..b }.map { it.second }
        return if (v.isEmpty()) null else v.sum() / v.size
    }

    private fun atYear(s: List<Pair<String, Double>>, y: Int) = s.firstOrNull { it.first == y.toString() }?.second
    private fun year(s: List<Pair<String, Double>>) = s.lastOrNull()?.first?.take(4)?.toIntOrNull()

    /** One row in the route's shape (country, currency, label, spot, ppp, over_under_pct, avg_window, ..., null_reason). */
    fun row(r: JSONObject, fx: List<Pair<String, Double>>, ppp: List<Pair<String, Double>>, cpi: List<Pair<String, Double>>, usCpi: List<Pair<String, Double>>, win: Long): JSONObject {
        val why = JSONObject()
        val spot = fx.lastOrNull()
        if (spot == null) why.put("spot", r.optString("no_fx").ifEmpty { "no free official daily USD rate for this currency (ECB reference rates)" })
        val y0 = year(ppp)
        val y1 = year(cpi)?.let { a -> year(usCpi)?.let { b -> minOf(a, b) } }
        val base = y0?.let { atYear(ppp, it) }
        var pppNow: Double? = null
        if (y0 == null || base == null) why.put("ppp", "no World Bank PPP conversion factor (PA.NUS.PPP) for this country")
        else if (y1 != null && y1 >= y0) {
            val h0 = atYear(cpi, y0); val h1 = atYear(cpi, y1); val u0 = atYear(usCpi, y0); val u1 = atYear(usCpi, y1)
            if (h0 != null && h1 != null && u0 != null && u1 != null) pppNow = pppToday(base, h0, h1, u0, u1)
            else why.put("ppp", "no World Bank CPI (FP.CPI.TOTL) for the PPP base year")
        } else pppNow = base
        val over = if (pppNow != null && spot != null) pct(pppNow, spot.second) else null
        val end = spot?.let { runCatching { LocalDate.parse(it.first) }.getOrNull() }
        val now = end?.let { windowAvg(fx, it, win) }
        val ago = end?.let { windowAvg(fx, it.minusDays(365), win) }
        if (spot != null && ago == null) why.put("year_ago", "less than a year of FX history")
        val real = y1?.let { y -> listOf(atYear(cpi, y - 1), atYear(cpi, y), atYear(usCpi, y - 1), atYear(usCpi, y)).takeIf { l -> l.all { it != null } }?.map { it!! } }
        if (ago != null && real == null) why.put("real", "no World Bank CPI for the last two years on both sides")
        val agoReal = if (ago != null && real != null) realAdjust(ago, real[0], real[1], real[2], real[3]) else null
        val change = if (agoReal != null && now != null) pct(agoReal, now) else null
        return JSONObject().put("country", r.optString("cc")).put("currency", r.optString("ccy")).put("label", r.optString("label")).put("group", r.optString("group"))
            .put("spot", spot?.second).put("spot_date", spot?.first).put("ppp", pppNow).put("ppp_base_year", y0).put("cpi_year", y1)
            .put("over_under_pct", over).put("avg_window", now).put("avg_window_year_ago", ago).put("avg_window_year_ago_real", agoReal)
            .put("real_change_pct", change).put("window_days", win).put("null_reason", why)
    }
}

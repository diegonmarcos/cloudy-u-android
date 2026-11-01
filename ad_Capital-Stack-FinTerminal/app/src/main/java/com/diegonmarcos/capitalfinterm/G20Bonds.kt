package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import java.time.LocalDate

/**
 * G20 Sovereign 10Y Bonds (#1397), the same maths as the container's /v1/bonds/g20-real (finterm/g20bonds.rs), for the direct-fetch fallback.
 * Yields in %; a yield series mixes daily (YYYY-MM-DD) and monthly (YYYY-MM, OECD) points; FX is local currency per USD, daily.
 */
object G20Bonds {
    /** The 30-day moving average ending at [end]: the daily points in the window when there are at least [minDaily], else the monthly
     *  average of the month the window centres on (or up to two months before it); null when neither exists. Second: the basis. */
    fun ma(s: List<Pair<String, Double>>, end: LocalDate, win: Long, minDaily: Int): Pair<Double, String>? {
        val a = end.minusDays(win - 1).toString()
        val b = end.toString()
        val d = s.filter { it.first.length == 10 && it.first >= a && it.first <= b }.map { it.second }
        if (d.isNotEmpty() && d.size >= minDaily) return d.sum() / d.size to "daily"
        val mid = end.minusDays(win / 2)
        val hi = mid.toString().take(7)
        val lo = mid.minusDays(62).toString().take(7)
        val m = s.filter { it.first.length == 7 && it.first <= hi && it.first >= lo }.maxByOrNull { it.first } ?: return null
        return m.second to "monthly ${m.first}"
    }

    /** Local currency depreciation vs USD over [years], annualised, in %: (today / past)^(1/years) - 1. */
    fun dep(today: Double, past: Double, years: Int): Double = (Math.pow(today / past, 1.0 / years) - 1.0) * 100.0

    /** Net USD yield: nominal minus the FX depreciation. */
    fun net(y: Double, fx: Double): Double = y - fx

    /** Real net USD return: net minus US CPI. */
    fun real(net: Double, cpi: Double): Double = net - cpi

    /** Trailing 12-month CPI change in % from a monthly index (dates YYYY-MM-DD or YYYY-MM): the last point over the same month a year before. */
    fun cpiYoY(s: List<Pair<String, Double>>): Pair<Double, String>? {
        val (d, v) = s.maxByOrNull { it.first } ?: return null
        val y = d.take(4).toIntOrNull() ?: return null
        val k = "${y - 1}${d.drop(4)}"
        val v0 = s.firstOrNull { it.first == k }?.second ?: return null
        return (v / v0 - 1.0) * 100.0 to d.take(7)
    }

    val KEYS = listOf("y0", "y1", "y3", "fx1", "fx3", "net1", "net3", "cpi", "real1", "real3")

    /** One country row in the route's shape; every empty cell carries its reason in null_reason, every monthly yield its basis. */
    fun row(r: JSONObject, y: List<Pair<String, Double>>, fx: List<Pair<String, Double>>, cpi: Pair<Double, String>?, end: LocalDate, win: Long, minDaily: Int): JSONObject {
        val why = JSONObject()
        val basis = JSONObject()
        val o = JSONObject()
        for (k in listOf("cc", "ccy", "flag", "name", "bench", "seg")) o.put(k, r.optString(k))
        val v = HashMap<String, Double?>()
        for ((k, n) in listOf("y0" to 0L, "y1" to 1L, "y3" to 3L)) {
            val at = end.minusDays(365L * n)
            val m = ma(y, at, win, minDaily)
            v[k] = m?.first
            if (m == null) why.put(k, "no 10Y yield (daily or OECD monthly) around $at") else if (m.second != "daily") basis.put(k, m.second)
        }
        val ccy = r.optString("ccy")
        if (ccy == "USD") { v["fx1"] = 0.0; v["fx3"] = 0.0 }
        else {
            val f0 = FxPpp.windowAvg(fx, end, win)
            for ((k, n) in listOf("fx1" to 1, "fx3" to 3)) {
                val p = FxPpp.windowAvg(fx, end.minusDays(365L * n), win)
                v[k] = if (f0 != null && p != null) dep(f0, p, n) else null
                if (v[k] == null) why.put(k, when { fx.isEmpty() -> r.optString("no_fx").ifEmpty { "no daily USD rate for $ccy" }; f0 == null -> "no $ccy rate in the last $win days"; else -> "less than ${n}y of daily $ccy history" })
            }
        }
        v["cpi"] = cpi?.first
        if (cpi == null) why.put("cpi", "no US CPI (FRED CPIAUCSL) a year apart") else o.put("cpi_month", cpi.second)
        for (n in listOf(1, 3)) {
            val y0 = v["y0"]; val f = v["fx$n"]
            v["net$n"] = if (y0 != null && f != null) net(y0, f) else null
            if (v["net$n"] == null) why.put("net$n", "needs the 10Y yield today and the ${n}Y FX change")
            val ne = v["net$n"]; val c = v["cpi"]
            v["real$n"] = if (ne != null && c != null) real(ne, c) else null
            if (v["real$n"] == null) why.put("real$n", "needs the ${n}Y net USD yield and US CPI")
        }
        for (k in KEYS) o.put(k, v[k] ?: JSONObject.NULL)
        o.put("basis", basis).put("null_reason", why).put("as_of", end.toString())
        return o
    }
}

package com.diegonmarcos.capitalfinterm

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * G20 FX & PPP Matrix (#1396), the same maths as the container's /v1/fx/ppp-matrix (finterm/fxmatrix.rs), for the direct-fetch fallback.
 * Rates are local currency per USD (LCU/USD); series are (date or year, value), oldest first. Every empty cell carries its reason.
 */
object FxMatrix {
    /** RPPP: a historical rate in today's prices, S_hist * (1 + pi_dom) / (1 + pi_US), pi the cumulative CPI growth over the window. */
    fun rppp(sHist: Double, home0: Double, home1: Double, us0: Double, us1: Double): Double = sHist * (home1 / home0) / (us1 / us0)

    /** The ICE dollar index from LCU/USD legs: constant * prod(rate ^ weight). */
    fun dxy(constant: Double, legs: List<Pair<Double, Double>>): Double = legs.fold(constant) { acc, (w, x) -> acc * Math.pow(x, w) }

    /** Today / x - 1, in percent. */
    fun diff(today: Double, x: Double): Double = (today / x - 1.0) * 100.0

    fun maKey(years: Int) = "ma_${years}y"
    fun keys(lags: List<Int>): List<String> = lags.map { maKey(it) } + listOf("macro_ppp", "retail_ppp")

    private fun atYear(s: List<Pair<String, Double>>, y: Int) = s.firstOrNull { it.first == y.toString() }?.second
    private fun year(s: List<Pair<String, Double>>) = s.lastOrNull()?.first?.take(4)?.toIntOrNull()
    private fun num(o: JSONObject, k: String): Double? = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { it.isFinite() } else null

    /** One currency row: today, the real 30MA at each lag, macro PPP, Big Mac PPP and the four diffs (route shape). */
    fun row(r: JSONObject, fx: List<Pair<String, Double>>, ppp: List<Pair<String, Double>>, cpi: List<Pair<String, Double>>, usCpi: List<Pair<String, Double>>,
            bm: List<Pair<String, Double>>, bmUs: List<Pair<String, Double>>, win: Long, lags: List<Int>): JSONObject {
        val why = JSONObject()
        val ccy = r.optString("ccy")
        val out = JSONObject().put("ccy", ccy).put("cc", r.optString("cc")).put("seg", r.optString("seg")).put("label", r.optString("label"))
        val today = fx.lastOrNull()
        if (today == null) why.put("today", r.optString("no_fx").ifEmpty { "no free official daily USD rate for this currency" })
        val end = today?.let { runCatching { LocalDate.parse(it.first) }.getOrNull() }
        val y1 = year(cpi)?.let { a -> year(usCpi)?.let { b -> minOf(a, b) } }
        for (k in lags) {
            val key = maKey(k)
            val raw = end?.let { FxPpp.windowAvg(fx, it.minusDays(365L * k), win) }
            if (today != null && raw == null) why.put(key, "less than ${k}y of daily FX history")
            var adj: Double? = null
            if (raw != null) {
                val v = y1?.let { y -> listOf(atYear(cpi, y - k), atYear(cpi, y), atYear(usCpi, y - k), atYear(usCpi, y)) }
                if (v == null || v.any { it == null }) why.put(key, "no World Bank CPI (FP.CPI.TOTL) for ${y1?.minus(k) ?: "?"} and ${y1 ?: "?"} on both sides")
                else adj = rppp(raw, v[0]!!, v[1]!!, v[2]!!, v[3]!!)
            }
            out.put(key, adj).put("${key}_raw", raw)
        }
        val p = FxPpp.row(r, fx, ppp, cpi, usCpi, win)
        val macro = num(p, "ppp")
        if (macro == null) why.put("macro_ppp", p.optJSONObject("null_reason")?.optString("ppp")?.ifEmpty { null } ?: "no World Bank PPP")
        out.put("macro_ppp", macro)
        val us = bmUs.lastOrNull()
        var retail: Double? = null
        if (us == null) why.put("retail_ppp", "no US Big Mac price in The Economist's data")
        else {
            val local = bm.firstOrNull { it.first == us.first }?.second
            if (local == null) why.put("retail_ppp", if (bm.isEmpty()) "The Economist's Big Mac index does not cover $ccy" else "no $ccy Big Mac price in the ${us.first} survey (last ${bm.last().first})")
            else { retail = local / us.second; out.put("bigmac_date", us.first) }
        }
        out.put("retail_ppp", retail)
        out.put("today", today?.second).put("today_date", today?.first).put("cpi_year", y1)
        if (today != null && r.optBoolean("market_quote")) out.put("market_quote", 1.0 / today.second)
        diffs(out, lags)
        return out.put("null_reason", why)
    }

    private fun diffs(o: JSONObject, lags: List<Int>) {
        val t = num(o, "today")
        keys(lags).forEach { k -> val x = num(o, k); o.put("diff_$k", if (t != null && x != null) diff(t, x) else null) }
    }

    /** The DXY row: each column is the index of the legs' values in that column; a missing leg names itself. */
    fun dxyRow(d: JSONObject, byCcy: Map<String, JSONObject>, lags: List<Int>): JSONObject {
        val why = JSONObject()
        val c = d.optDouble("constant")
        val legs = d.optJSONArray("legs") ?: JSONArray()
        val out = JSONObject().put("ccy", "DXY").put("seg", "Index").put("label", d.optString("label"))
        for (k in listOf("today") + keys(lags)) {
            val v = (0 until legs.length()).map { legs.getJSONObject(it) }.map { l -> l.optString("ccy") to (l.optDouble("weight") to byCcy[l.optString("ccy")]?.let { num(it, k) }) }
            val miss = v.firstOrNull { it.second.second == null }
            if (miss != null) {
                val leg = byCcy[miss.first]?.optJSONObject("null_reason")?.optString(k).orEmpty()
                why.put(k, "needs the ${miss.first} leg" + if (leg.isEmpty()) "" else " ($leg)")
            } else out.put(k, dxy(c, v.map { it.second.first to it.second.second!! }))
        }
        out.put("today_date", byCcy.values.mapNotNull { it.optString("today_date").takeIf { s -> s.isNotEmpty() && s != "null" } }.maxOrNull())
        diffs(out, lags)
        return out.put("null_reason", why)
    }

    /** The Economist big-mac-full-index.csv: a3 -> [(date, local_price)], oldest first. */
    fun bigMac(csv: String): Map<String, List<Pair<String, Double>>> {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return emptyMap()
        val head = cells(lines[0])
        val (iD, iA, iP) = listOf("date", "iso_a3", "local_price").map { head.indexOf(it) }
        if (iD < 0 || iA < 0 || iP < 0) return emptyMap()
        val out = HashMap<String, MutableList<Pair<String, Double>>>()
        lines.drop(1).forEach { l -> val c = cells(l); c.getOrNull(iP)?.toDoubleOrNull()?.let { p -> out.getOrPut(c[iA]) { ArrayList() } += c[iD] to p } }
        return out.mapValues { (_, v) -> v.sortedBy { it.first } }
    }

    private fun cells(line: String): List<String> {
        val out = ArrayList<String>(); val b = StringBuilder(); var q = false
        for (ch in line) {
            if (ch == '"') q = !q
            else if (ch == ',' && !q) { out += b.toString(); b.setLength(0) }
            else b.append(ch)
        }
        out += b.toString()
        return out.map { it.trim() }
    }

    private val REC = Regex("""<Record Date="(\d{2})\.(\d{2})\.(\d{4})"[^>]*>\s*<Nominal>(\d+)</Nominal>\s*<Value>([\d,.]+)</Value>""")

    /** Bank of Russia XML_dynamic: [(YYYY-MM-DD, roubles per unit)], oldest first. */
    fun cbr(xml: String): List<Pair<String, Double>> = REC.findAll(xml).mapNotNull { m ->
        val (d, mo, y, n, v) = m.destructured
        v.replace(',', '.').toDoubleOrNull()?.let { x -> "$y-$mo-$d" to x / n.toDouble() }
    }.toList().sortedBy { it.first }

    val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
}

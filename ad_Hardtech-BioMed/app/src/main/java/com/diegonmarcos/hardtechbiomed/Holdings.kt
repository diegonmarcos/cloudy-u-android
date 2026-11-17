package com.diegonmarcos.hardtechbiomed

import org.json.JSONArray
import org.json.JSONObject

/** One line of the personal holdings list: what the user typed, nothing fetched (no price, no quote). */
data class Holding(val ticker: String, val quantity: Double, val cost: Double)

/** The simple local holdings list of the `holdings` kind: parse, serialise, add, remove, cost basis. Pure, so the JVM suite covers it. */
object Holdings {
    fun parse(json: String): List<Holding> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ticker = o.optString("ticker").trim()
            if (ticker.isEmpty()) null else Holding(ticker, o.optDouble("quantity", 0.0), o.optDouble("cost", 0.0))
        }
    }

    fun toJson(list: List<Holding>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("ticker", it.ticker).put("quantity", it.quantity).put("cost", it.cost)) }
        return arr.toString()
    }

    /** The list with one more line, or null when the ticker is empty or the quantity / cost is not a finite number (quantity > 0, cost >= 0). */
    fun add(list: List<Holding>, ticker: String, quantity: String, cost: String): List<Holding>? {
        val t = ticker.trim().uppercase()
        val q = quantity.trim().toDoubleOrNull() ?: return null
        val c = cost.trim().toDoubleOrNull() ?: return null
        if (t.isEmpty() || !q.isFinite() || !c.isFinite() || q <= 0.0 || c < 0.0) return null
        return list + Holding(t, q, c)
    }

    fun remove(list: List<Holding>, index: Int): List<Holding> = list.filterIndexed { i, _ -> i != index }

    /** Sum of quantity x unit cost, in whatever currency the user typed. */
    fun costBasis(list: List<Holding>): Double = list.sumOf { it.quantity * it.cost }
}

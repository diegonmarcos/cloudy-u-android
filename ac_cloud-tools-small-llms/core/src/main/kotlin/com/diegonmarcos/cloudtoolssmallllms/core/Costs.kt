package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject

/** A model's price in dollars per 1M tokens, as the shared catalogue publishes it. */
data class Price(val inPerM: Double, val outPerM: Double)

/**
 * Where prices come from: the app adapts libs:model-catalogue (its live OpenRouter pricing, cached,
 * the bundled snapshot offline) to this; the suite adapts the bundled catalogue.json. null = the
 * catalogue does not price that model.
 */
fun interface PriceBook {
    fun price(model: String): Price?
}

/** What OpenRouter's `usage` says a call took; [cost] is its own USD figure when it sends one. */
data class Usage(val promptTokens: Int, val completionTokens: Int, val cost: Double?)

object Costs {
    private const val MILLION = 1_000_000.0

    /** [tokensIn] in and [tokensOut] out at [price]. */
    fun estimate(price: Price, tokensIn: Int, tokensOut: Int): Double = (tokensIn * price.inPerM + tokensOut * price.outPerM) / MILLION

    /**
     * A chat completion's `usage`: prompt_tokens, completion_tokens and OpenRouter's `cost` (USD).
     * Decisions answers name them input_tokens / output_tokens. null when there is no usage at all.
     */
    fun usage(o: JSONObject?): Usage? {
        if (o == null) return null
        val pin = num(o, "prompt_tokens") ?: num(o, "input_tokens")
        val pout = num(o, "completion_tokens") ?: num(o, "output_tokens")
        val cost = num(o, "cost")?.takeIf { it >= 0 }
        if (pin == null && pout == null && cost == null) return null
        return Usage(pin?.toInt() ?: 0, pout?.toInt() ?: 0, cost)
    }

    /** What a call actually cost: OpenRouter's own figure when sent, else its tokens at [price]; null when neither is known. */
    fun actual(u: Usage?, price: Price?): Double? = when {
        u == null -> null
        u.cost != null -> u.cost
        price != null -> estimate(price, u.promptTokens, u.completionTokens)
        else -> null
    }

    /** "$0.000123", "$0.0420", "$1.25": three significant figures under a cent, as the cost chip shows it. */
    fun usd(x: Double?): String {
        if (x == null || x.isNaN() || x < 0) return "—"
        if (x == 0.0) return "$0"
        if (x >= 0.01) return "$" + String.format(java.util.Locale.ROOT, "%.4f", x).trimEnd('0').let { if (it.endsWith('.')) it + "00" else it }
        return "$" + java.math.BigDecimal(x).round(java.math.MathContext(3)).stripTrailingZeros().toPlainString()
    }

    private fun num(o: JSONObject, k: String): Double? = o.opt(k)?.takeIf { it is Number }?.let { (it as Number).toDouble() }
}

package com.diegonmarcos.superapp.decisions.link

import org.json.JSONObject

/**
 * #1801 what one [DecisionsLink.relay] call answered. When [ok], [code] and [body] are the provider's own status code and
 * answer, untouched (a provider's HTTP error is an answer: the app reads it as it did when it made the call itself). Else
 * the engine's [reason] (no_engine, no_key, no_consent, over_budget, network, ...) with its [message] when it has one and
 * [detail] for a screen that wants to say what to do. NO KEY IS IN IT: the engine added it and kept it.
 */
class RelayReply(val ok: Boolean, val code: Int, val body: String, val reason: String, val message: String?, val detail: String, val cost: Double?) {

    /** The sentence a screen shows when the call was not made: the engine's message, else its detail, else the reason. */
    val why: String get() = message ?: detail.ifBlank { null } ?: reason

    companion object {
        /** Read the engine's reply without trusting its shape: it crossed a process. An ok with no body is malformed. */
        fun parse(reply: JSONObject, body: String?): RelayReply {
            val ok = reply.optBoolean("ok", false) && body != null && reply.has("code")
            val reason = if (ok) "" else reply.optString("reason").ifEmpty { Outcome.MALFORMED }
            val cost = (reply.opt("cost") as? Number)?.toDouble()
            return RelayReply(ok, if (ok) reply.optInt("code") else 0, if (ok) body.orEmpty() else "", reason,
                reply.optString("message").ifEmpty { null }, reply.optString("detail"), cost)
        }

        /** The endpoint NAME of a provider URL a consumer used to POST to: the relay takes names, never URLs. */
        fun endpointOf(url: String): String = when {
            url.contains("/alpha/decisions") -> "decisions"
            else -> "chat"
        }
    }
}

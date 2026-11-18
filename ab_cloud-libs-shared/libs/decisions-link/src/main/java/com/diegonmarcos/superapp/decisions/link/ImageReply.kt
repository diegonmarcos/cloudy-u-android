package com.diegonmarcos.superapp.decisions.link

import org.json.JSONObject

/**
 * #1799 what one [DecisionsLink.image] call answered: [picture] when [ok], else the engine's [reason] (no_engine, no_key,
 * no_consent, over_budget, http, ...), with the call's own failure [kind] (OFFLINE, AUTH, LIMIT, MODEL, SERVER, EMPTY) and
 * [message] when the model's API answered one, and [detail] for a screen that wants to say what to do.
 */
class ImageReply(val picture: ByteArray?, val mime: String, val reason: String, val kind: String?, val message: String?, val detail: String, val cost: Double?) {
    val ok: Boolean get() = picture != null

    companion object {
        /** Read the engine's reply without trusting its shape: it crossed a process. An ok with no picture is malformed. */
        fun parse(reply: JSONObject, picture: ByteArray?): ImageReply {
            val ok = reply.optBoolean("ok", false) && picture != null && picture.isNotEmpty()
            val reason = if (ok) "" else reply.optString("reason").ifEmpty { Outcome.MALFORMED }
            val cost = (reply.opt("cost") as? Number)?.toDouble()
            return ImageReply(if (ok) picture else null, reply.optString("mime").ifEmpty { "image/png" }, reason,
                reply.optString("kind").ifEmpty { null }, reply.optString("message").ifEmpty { null }, reply.optString("detail"), cost)
        }
    }
}

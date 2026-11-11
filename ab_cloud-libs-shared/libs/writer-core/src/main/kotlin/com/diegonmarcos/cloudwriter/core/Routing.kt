package com.diegonmarcos.cloudwriter.core

import org.json.JSONObject

/**
 * The two ways Cloud Writer can do speech-to-text or translation (#800, the Camera/Calc #799 shape):
 * a MODEL on OpenRouter with the fleet Account's token, or ON-DEVICE ML (Vosk / ML Kit in the
 * keyboard-engines companion). The id is what the preference stores and what the debug routes
 * print, so it never changes spelling.
 */
enum class Route(val id: String) {
    MODEL("model"),
    ML("ml");

    companion object {
        fun of(id: String?, fallback: Route): Route = values().firstOrNull { it.id == id } ?: fallback
    }
}

/** One engine's reply: [text] non-null = it answered (an empty transcript of silence is an answer), else [error] says why. */
data class Outcome(val text: String?, val error: String?) {
    val ok: Boolean get() = text != null

    companion object {
        fun ok(text: String) = Outcome(text, null)
        fun failed(why: String) = Outcome(null, why)
    }
}

/**
 * What came back, WHICH ROUTE ANSWERED, and — when that is not the route asked for — why the asked
 * one did not. The screen shows [route]; it is never inferred from [requested].
 */
data class Answer(
    val text: String?,
    val route: Route?,
    val requested: Route,
    val fallbackReason: String?,
    val error: String?,
) {
    val ok: Boolean get() = text != null
    val fellBack: Boolean get() = ok && route != requested

    fun toJson(): JSONObject = JSONObject()
        .put("ok", ok)
        .put("text", text ?: JSONObject.NULL)
        .put("route", route?.id ?: JSONObject.NULL)
        .put("requested", requested.id)
        .put("fell_back", fellBack)
        .put("fallback_reason", fallbackReason ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
}

object Routing {
    const val OFFLINE = "offline"
    const val NO_TOKEN = "no OpenRouter token in the fleet Account"
    const val NO_MODEL = "no model chosen"
    const val EMPTY = "no answer"

    /** Why the model route cannot even be tried, or null when it can. Checked before anything is spent. */
    fun modelBlocker(online: Boolean, hasToken: Boolean, model: String?): String? = when {
        !online -> OFFLINE
        !hasToken -> NO_TOKEN
        model.isNullOrBlank() -> NO_MODEL
        else -> null
    }

    /**
     * Run [requested], falling back from MODEL to on-device ML when the model route is blocked
     * ([blocker]) or fails. NEVER the other way: an owner who chose on-device chose not to send
     * the text away, so an on-device failure is reported, not quietly billed to a provider.
     */
    /**
     * The route a function runs on: the one the owner SAVED when there is one (an unknown saved id falls to
     * [default], as it always did), else the decision model's [advice] (#882 writer_route; null = no opinion),
     * else [default]. Advice never overrides a choice, and no advice is exactly the old rule.
     */
    fun chosen(saved: String?, advice: Route?, default: Route): Route =
        if (saved != null) Route.of(saved, default) else advice ?: default

    fun run(requested: Route, blocker: String?, model: () -> Outcome, ml: () -> Outcome): Answer {
        if (requested == Route.ML) {
            val o = ml()
            return if (o.ok) Answer(o.text, Route.ML, requested, null, null)
            else Answer(null, null, requested, null, "on-device: " + (o.error ?: EMPTY))
        }
        val reason: String
        if (blocker != null) {
            reason = blocker
        } else {
            val o = model()
            if (o.ok) return Answer(o.text, Route.MODEL, requested, null, null)
            reason = o.error ?: EMPTY
        }
        val f = ml()
        return if (f.ok) Answer(f.text, Route.ML, requested, reason, null)
        else Answer(null, null, requested, reason, "model: $reason; on-device: " + (f.error ?: EMPTY))
    }
}

package com.diegonmarcos.cloudcanvas.enhance

import android.content.Context
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import com.diegonmarcos.superapp.decisions.link.ImageReply
import org.json.JSONObject

/**
 * #1799 the Draw Enhancer's run through the fleet's decisions engine (Cloud-Lib-Decisions-Engine.apk, contract 2): the
 * picture, the expanded prompt and the model chosen in Configs > AI go to the engine, which holds the fleet Account's
 * OpenRouter key in its own process, applies the image use's consent, budget and hourly limit, journals the call and
 * answers a picture. This app never reads that key. The messages the user reads are the ones the Enhancer showed when it
 * made the call itself (the engine returns them as they were), plus one sentence per new way a run can be refused.
 */
object EngineRoute {
    /** The image use the engine declares for this app (decisions.json::image.uses). */
    const val USE = "canvas_enhance"

    /** One image call; the real one is [link], a test hands in a fake. */
    fun interface Engine {
        fun image(model: String, prompt: String, picture: ByteArray): ImageReply
    }

    fun link(ctx: Context): Engine = Engine { model, prompt, picture -> DecisionsLink.image(ctx, "canvas_enhance", model, prompt, picture) }

    /** One run. Blocking: call off the main thread. */
    fun run(engine: Engine, model: String, prompt: String, picture: ByteArray): EnhanceRequest.Result =
        toResult(runCatching { engine.image(model, prompt, picture) }.getOrElse {
            ImageReply.parse(JSONObject().put("ok", false).put("reason", "no_engine").put("detail", it.message ?: it.javaClass.simpleName), null)
        })

    /** The engine's answer as the Enhancer's own result: the picture, or why there is none in the user's words. */
    fun toResult(r: ImageReply): EnhanceRequest.Result {
        r.picture?.let { return EnhanceRequest.Result.Image(it) }
        val kind = r.kind?.let { k -> EnhanceRequest.Kind.entries.firstOrNull { it.name == k } }
        r.message?.takeIf { it.isNotBlank() }?.let { return EnhanceRequest.Result.Failure(kind ?: EnhanceRequest.Kind.SERVER, it) }
        val (k, text) = refusal(r.reason, r.detail)
        return EnhanceRequest.Result.Failure(kind ?: k, text)
    }

    /** Every refusal the engine can answer, with the sentence the Enhancer shows for it. */
    fun refusal(reason: String, detail: String): Pair<EnhanceRequest.Kind, String> = when (reason) {
        "no_key" -> EnhanceRequest.Kind.AUTH to NO_KEY
        "offline", "network" -> EnhanceRequest.Kind.OFFLINE to "No network. Connect and try again."
        "no_engine" -> EnhanceRequest.Kind.SERVER to "The fleet's AI engine is not ready: " + detail.ifBlank { "install Cloud Lib Decisions Engine from Store ▸ Cloud Constellation ▸ Libs" } + "."
        "no_image_policy", "unknown_use", "misconfigured" -> EnhanceRequest.Kind.SERVER to
            "The fleet's AI engine does not serve image runs yet ($reason). Update it from Store ▸ Cloud Constellation ▸ Libs."
        "app_not_allowed", "disabled" -> EnhanceRequest.Kind.AUTH to "The fleet's AI engine does not allow image runs for Cloud Canvas ($reason)."
        "no_consent" -> EnhanceRequest.Kind.AUTH to "Image runs for Cloud Canvas are switched off. Turn them on in the SuperApp's AI consent settings."
        "over_budget" -> EnhanceRequest.Kind.LIMIT to "Today's fleet image budget is spent. It resets at midnight UTC."
        "app_quota" -> EnhanceRequest.Kind.LIMIT to "Cloud Canvas has used today's image runs. They reset at midnight UTC."
        "rate_limited" -> EnhanceRequest.Kind.LIMIT to "Too many image runs this hour. Try again later."
        "breaker_open" -> EnhanceRequest.Kind.SERVER to "The image service failed several times in a row; the engine pauses it for a few minutes. Try again later."
        "metered", "battery_saver" -> EnhanceRequest.Kind.OFFLINE to "The fleet's AI engine holds image runs on this network or power state ($reason)."
        "bad_model" -> EnhanceRequest.Kind.MODEL to "The engine does not accept this model id. Pick an image model in Configs > AI."
        "prompt_too_large" -> EnhanceRequest.Kind.MODEL to "The prompt is too long for the engine. Shorten it."
        "image_too_large" -> EnhanceRequest.Kind.MODEL to "The picture is too large for the engine. Pick a smaller output size in Configs > AI."
        "output_too_large" -> EnhanceRequest.Kind.EMPTY to "The returned image is too large to hand over."
        "empty" -> EnhanceRequest.Kind.EMPTY to "The model returned no image. Pick an image model in Configs > AI."
        "bad_request" -> EnhanceRequest.Kind.MODEL to "The run is missing its picture or prompt."
        else -> EnhanceRequest.Kind.SERVER to "The fleet's AI engine refused the run ($reason)."
    }

    /** The Enhancer's sentence when no key pays for the run (the same text as before the engine held it). */
    const val NO_KEY = "No OpenRouter key. Add one in Configs > AI, or sign in to the fleet Account."
}

package com.diegonmarcos.cloudwriter.core

import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Cloud Writer's MODEL route: OpenRouter's chat completions, for translation (text in, text out)
 * and speech-to-text (a WAV segment in as `input_audio`, text out). The transport is
 * libs:decisions' [Http] — the fleet's one OpenRouter transport, over Android's own resolver — so
 * no socket code lives here and the suite drives a fake.
 *
 * NO URL, MODEL OR PROMPT IS SPELLED HERE: every one is build.json::writer_routes, handed in.
 * The token is read on use from the fleet Account and passed straight through; nothing here keeps it.
 */
data class OpenRouterConfig(val chatUrl: String, val modelsUrl: String, val timeoutMs: Int, val maxTokens: Int)

/** One row of OpenRouter's live catalogue, as much as the model pickers need. */
data class CatalogueModel(
    val id: String,
    val name: String,
    val audioIn: Boolean,
    val textIn: Boolean,
    val textOut: Boolean,
    val promptUsdPerMillion: Double?,
    /** #1418 accepts an image (OCR over OpenRouter needs a vision model). */
    val imageIn: Boolean = false,
)

object OpenRouter {

    fun chatBody(model: String, system: String, user: String, maxTokens: Int): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", maxTokens)
        .put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", user)))

    /** OpenRouter's audio input: a text part with the instruction and an `input_audio` part, base64 WAV. */
    fun transcribeBody(model: String, prompt: String, wav: ByteArray, maxTokens: Int): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", maxTokens)
        .put("messages", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                    .put("data", Base64.getEncoder().encodeToString(wav))
                    .put("format", "wav"))))))

    /** POST [body]. Never throws: no token, transport, non-2xx and a reply without content all come back as a reason. */
    fun call(http: Http, cfg: OpenRouterConfig, token: String?, body: JSONObject): Outcome {
        if (token.isNullOrBlank()) return Outcome.failed(Routing.NO_TOKEN)
        val res = runCatching { http.send(cfg.chatUrl, token, body.toString(), cfg.timeoutMs) }
            .getOrElse { return Outcome.failed("network: " + it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "")) }
        if (res.code !in 200..299) return Outcome.failed("HTTP ${res.code}: " + Decisions.apiError(res.body))
        val text = content(res.body) ?: return Outcome.failed("no message content: " + Decisions.apiError(res.body))
        return Outcome.ok(text.trim())
    }

    fun translate(http: Http, cfg: OpenRouterConfig, token: String?, model: String, system: String, text: String): Outcome =
        call(http, cfg, token, chatBody(model, system, text, cfg.maxTokens))

    fun transcribe(http: Http, cfg: OpenRouterConfig, token: String?, model: String, prompt: String, wav: ByteArray): Outcome =
        call(http, cfg, token, transcribeBody(model, prompt, wav, cfg.maxTokens))

    /** choices[0].message.content — a string, or a list of parts whose text is joined. */
    fun content(body: String): String? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val msg = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
        return when (val c = msg.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotEmpty() } }
                .takeIf { it.isNotEmpty() }?.joinToString("")
            else -> null
        }
    }

    /** The live catalogue body, parsed. Routers priced below zero (openrouter/auto, -1) are dropped: they are not a model. */
    fun catalogue(body: String): List<CatalogueModel> {
        val data = runCatching { JSONObject(body).getJSONArray("data") }.getOrNull() ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val arch = m.optJSONObject("architecture")
            val ins = strings(arch?.optJSONArray("input_modalities"))
            val outs = strings(arch?.optJSONArray("output_modalities"))
            val price = m.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull()
            if (price != null && price < 0) return@mapNotNull null
            CatalogueModel(id, m.optString("name").ifEmpty { id }, "audio" in ins, "text" in ins, "text" in outs, price?.let { it * 1_000_000 }, "image" in ins)
        }
    }

    /** Models that HEAR: audio in, text out — the only ones that can transcribe. Cheapest first. */
    fun speechModels(all: List<CatalogueModel>): List<CatalogueModel> =
        all.filter { it.audioIn && it.textOut }.sortedWith(byPrice)

    /** Text-in, text-out models — what translation can use. Cheapest first. */
    /** #1418 the models OCR can run on: an image in, text out, cheapest first. */
    fun visionModels(all: List<CatalogueModel>): List<CatalogueModel> =
        all.filter { it.imageIn && it.textOut }.sortedWith(byPrice)

    /** #1418 one image and an instruction, as OpenRouter's vision chat expects (a data: URL). */
    fun visionBody(model: String, prompt: String, image: ByteArray, mime: String, maxTokens: Int): JSONObject = JSONObject()
        .put("model", model)
        .put("max_tokens", maxTokens)
        .put("messages", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "text").put("text", prompt))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                    .put("url", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(image)))))))

    fun textModels(all: List<CatalogueModel>): List<CatalogueModel> =
        all.filter { it.textIn && it.textOut }.sortedWith(byPrice)

    private val byPrice = compareBy<CatalogueModel>({ it.promptUsdPerMillion ?: Double.MAX_VALUE }, { it.id })

    private fun strings(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.toSet()
}

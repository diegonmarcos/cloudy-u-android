package com.diegonmarcos.cloudcanvas.enhance

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** The AI tasks a model is chosen for in Configs > AI. Music Enhance is declared for later and has no page yet. */
enum class Task(val id: String, val label: String, val available: Boolean) {
    DrawEnhance("draw_enhance", "Draw Enhance", true),
    Convert3d("convert_3d", "Convert to 3D", true),
    MusicEnhance("music_enhance", "Music Enhance (coming soon)", false);

    companion object {
        fun byId(id: String?): Task = entries.firstOrNull { it.id == id } ?: DrawEnhance
    }
}

/** A preset of the library: [prompt] is the long professional prompt it expands to, [task] picks the model. */
data class Preset(val id: String, val label: String, val summary: String, val prompt: String, val task: Task)

/** A quality or output-size choice of Configs; [maxPx] is the long side in pixels for a size. */
data class Choice(val id: String, val label: String, val hint: String = "", val maxPx: Int = 0)

/** The runtime dataset enhance-presets (data/fleet/app/<package>/enhance-presets.json, served by Cloud Store): presets, Configs choices and limits. */
data class PresetLibrary(
    val presets: List<Preset>,
    val qualities: List<Choice>,
    val sizes: List<Choice>,
    val maxInputPx: Int,
    val maxInputBytes: Int,
    val costCapsUsd: List<Double>,
    val defaultCostCapUsd: Double,
    val defaultQuality: String,
    val defaultSize: String,
) {
    fun preset(id: String?): Preset? = presets.firstOrNull { it.id == id }
    fun quality(id: String?): Choice? = qualities.firstOrNull { it.id == id } ?: qualities.firstOrNull { it.id == defaultQuality } ?: qualities.firstOrNull()
    fun size(id: String?): Choice? = sizes.firstOrNull { it.id == id } ?: sizes.firstOrNull { it.id == defaultSize } ?: sizes.firstOrNull()

    companion object {
        fun parse(text: String): PresetLibrary? = runCatching {
            val o = JSONObject(text)
            fun list(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).map { a.getJSONObject(it) }
            val limits = o.optJSONObject("limits") ?: JSONObject()
            val caps = limits.optJSONArray("cost_caps_usd")
            val defaults = o.optJSONObject("defaults") ?: JSONObject()
            PresetLibrary(
                presets = list(o.optJSONArray("presets")).map {
                    Preset(it.getString("id"), it.getString("label"), it.optString("summary"), it.getString("prompt"), Task.byId(it.optString("task")))
                },
                qualities = list(o.optJSONArray("qualities")).map { Choice(it.getString("id"), it.getString("label"), it.optString("hint")) },
                sizes = list(o.optJSONArray("sizes")).map { Choice(it.getString("id"), it.getString("label"), maxPx = it.getInt("max_px")) },
                maxInputPx = limits.optInt("max_input_px", 2048),
                maxInputBytes = limits.optInt("max_input_bytes", 6_000_000),
                costCapsUsd = if (caps == null) emptyList() else (0 until caps.length()).map { caps.getDouble(it) },
                defaultCostCapUsd = limits.optDouble("default_cost_cap_usd", 0.25),
                defaultQuality = defaults.optString("quality"),
                defaultSize = defaults.optString("size"),
            )
        }.getOrNull()
    }
}

/** Preset to prompt: the preset's long text with {size} and {quality} filled from Configs, plus the user's own words. */
object PromptExpander {
    fun expand(preset: Preset?, custom: String, quality: Choice?, size: Choice?): String {
        val sizeText = size?.let { "${it.maxPx} pixels on the long side" } ?: "the highest available resolution"
        val qualityText = quality?.let { "${it.label.lowercase()} (${it.hint})" } ?: "high"
        val base = preset?.prompt?.replace("{size}", sizeText)?.replace("{quality}", qualityText).orEmpty()
        val extra = custom.trim()
        return when {
            base.isEmpty() && extra.isEmpty() -> ""
            base.isEmpty() -> "$extra Output a single finished image at $sizeText, quality target: $qualityText; keep the subject and composition of the input image; no text, watermarks or artefacts."
            extra.isEmpty() -> base
            else -> "$base Additional instructions from the user, which take priority where they conflict: $extra"
        }
    }
}

/** The OpenRouter request for an image-to-image run, and the parsing of its answer. Pure: the HTTP is the caller's. */
object EnhanceRequest {
    fun dataUrl(bytes: ByteArray, mime: String = "image/png"): String = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** A chat-completions body with the prompt and the image as one user message, asking for an image back. */
    fun body(model: String, prompt: String, imageDataUrl: String): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", imageDataUrl)))
        return JSONObject()
            .put("model", model)
            .put("modalities", JSONArray().put("image").put("text"))
            .put("stream", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()
    }

    enum class Kind { OFFLINE, AUTH, LIMIT, MODEL, SERVER, EMPTY }

    sealed class Result {
        class Image(val bytes: ByteArray) : Result()
        class Failure(val kind: Kind, val message: String) : Result()
    }

    /** Reads the answer: the first returned image (a data URL), or why there is none. */
    fun parse(code: Int, body: String): Result {
        val err = runCatching { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() }.getOrDefault("")
        if (code !in 200..299) {
            val kind = when (code) {
                401, 403 -> Kind.AUTH
                402, 429 -> Kind.LIMIT
                400, 404 -> Kind.MODEL
                else -> Kind.SERVER
            }
            return Result.Failure(kind, err.ifBlank { "HTTP $code" })
        }
        return runCatching {
            val msg = JSONObject(body).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val images = msg.optJSONArray("images")
            val url = images?.getJSONObject(0)?.getJSONObject("image_url")?.getString("url")
            if (url == null || !url.contains("base64,")) Result.Failure(Kind.EMPTY, "The model returned no image. Pick an image model in Configs > AI.")
            else Result.Image(Base64.getDecoder().decode(url.substringAfter("base64,")))
        }.getOrElse { Result.Failure(Kind.EMPTY, err.ifBlank { "The answer could not be read." }) }
    }

    /** A rough cost of one run in USD from the catalogue's price (unit "image" or "megapixel", output side); null when the price is unknown. */
    fun estimateUsd(unit: String?, outPrice: Double?, maxPx: Int): Double? {
        if (outPrice == null) return null
        return when (unit) {
            "image" -> outPrice
            "megapixel" -> outPrice * (maxPx.toDouble() * maxPx / 1_000_000.0)
            else -> null
        }
    }

    /** True when a run must be refused: the estimate is above the user's per-run cap. An unknown price is not refused. */
    fun overCap(estimate: Double?, capUsd: Double): Boolean = estimate != null && estimate > capUsd
}

/** Where Configs > AI keeps its choices: a tiny string store so the JVM suite runs the selection logic without Android. */
interface KeyValue {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

/** Per-task model, quality, size and cost cap, persisted through a [KeyValue]. */
class AiChoices(private val kv: KeyValue) {
    fun model(task: Task): String? = kv.get("model_" + task.id)?.takeIf { it.isNotBlank() }
    fun setModel(task: Task, id: String?) = kv.put("model_" + task.id, id)
    var quality: String?
        get() = kv.get("quality")
        set(v) = kv.put("quality", v)
    var size: String?
        get() = kv.get("size")
        set(v) = kv.put("size", v)
    fun costCap(default: Double): Double = kv.get("cost_cap")?.toDoubleOrNull() ?: default
    fun setCostCap(v: Double) = kv.put("cost_cap", v.toString())
}

/** An API key held in memory: it prints masked, so a log line or a debug dump never carries it. */
class Secret(private val value: String) {
    fun reveal(): String = value
    override fun toString(): String = mask(value)

    companion object {
        fun mask(v: String): String = if (v.length <= 8) "••••" else "••••" + v.takeLast(4)
    }
}

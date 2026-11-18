package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.util.Base64

/**
 * #1799 one declared IMAGE use (decisions.json::image.uses): an app that sends a picture and a prompt and gets a picture
 * back (Cloud Canvas's Draw Enhancer), so the app holds no key. The same identity and consent rules as a decision use: the
 * [apps] that may call it (read from the binder), [consentRequired] off until the user grants it (always for a sensitive
 * [content]), and [maxCallsPerHour] for the spend ledger.
 */
data class ImageUseDecl(
    val name: String,
    val enabled: Boolean,
    val maxCallsPerHour: Int,
    val consentRequired: Boolean,
    val content: String,
    val apps: Set<String>,
) {
    /** The consent book keys a grant by app and use name; this is the view of the use it reads. */
    fun asUse(): UseDecl = UseDecl(name, enabled, 1.0, emptySet(), UseClass.USER_FACING, 0, maxCallsPerHour, consentRequired, content, apps)
}

/**
 * The image block of decisions.json: its own endpoint (the chat-completions route image models answer on), timeout, size
 * limits, model-id shape, budget (a separate daily USD cap and per-app quota: an image costs a hundred decisions), network
 * suppression and breaker, so image traffic can neither spend nor trip the decisions' own. A block that does not validate
 * throws [IllegalArgumentException]; [Policy.parse] then serves decisions with no image use at all.
 */
class ImagePolicy private constructor(
    val endpoint: String,
    val timeoutMs: Int,
    val maxInputBytes: Int,
    val maxOutputBytes: Int,
    val maxPromptChars: Int,
    val model: Regex,
    val budget: BudgetRules,
    val suppress: SuppressRules,
    val breaker: BreakerRules,
    val uses: Map<String, ImageUseDecl>,
    val rejected: Map<String, String>,
) {
    companion object {
        fun parse(d: JSONObject, sensitive: Set<String>): ImagePolicy {
            val endpoint = d.optString("endpoint")
            require(endpoint.startsWith("https://")) { "image.endpoint must be an https URL" }
            val timeout = d.optInt("timeout_ms", 0)
            require(timeout in 1000..300000) { "image.timeout_ms must be 1000..300000" }
            val maxIn = d.optInt("max_input_bytes", 0)
            require(maxIn > 0) { "image.max_input_bytes must be positive" }
            val maxOut = d.optInt("max_output_bytes", 0)
            require(maxOut > 0) { "image.max_output_bytes must be positive" }
            val maxPrompt = d.optInt("max_prompt_chars", 0)
            require(maxPrompt > 0) { "image.max_prompt_chars must be positive" }
            val model = Regex(d.optString("model_re").also { require(it.isNotEmpty()) { "image.model_re is empty" } })
            val b = d.optJSONObject("budget") ?: throw IllegalArgumentException("image.budget is missing")
            val budget = BudgetRules(b.pos("daily_usd_cap"), b.pos("per_app_daily_calls").toInt(), b.pos("est_call_usd"))
            val s = d.optJSONObject("suppress") ?: throw IllegalArgumentException("image.suppress is missing")
            val suppress = SuppressRules(s.optBoolean("metered", true), s.optBoolean("offline", true), s.optBoolean("battery_saver", true))
            val br = d.optJSONObject("breaker") ?: throw IllegalArgumentException("image.breaker is missing")
            val breaker = BreakerRules(br.pos("failures").toInt(), br.pos("open_s").toLong())
            val uses = LinkedHashMap<String, ImageUseDecl>()
            val rejected = LinkedHashMap<String, String>()
            val declared = d.optJSONObject("uses") ?: JSONObject()
            for (name in declared.keys().asSequence().filter { !it.startsWith("_") }) {
                val u = declared.optJSONObject(name)
                val problem = if (u == null) "is not an object" else check(u, sensitive)
                if (problem != null) rejected[name] = problem else uses[name] = decl(name, u!!)
            }
            return ImagePolicy(endpoint, timeout, maxIn, maxOut, maxPrompt, model, budget, suppress, breaker, uses, rejected)
        }

        private fun check(u: JSONObject, sensitive: Set<String>): String? {
            if (u.optInt("max_calls_per_hour", 0) < 1) return "max_calls_per_hour must be at least 1"
            if (u.optJSONArray("apps").strings().isEmpty()) return "apps names no app, so nothing may call it"
            val consent = u.optString("consent", "required")
            if (consent != "required" && consent != "implicit") return "consent must be required or implicit"
            if (consent == "implicit" && u.optString("content") in sensitive) return "content ${u.optString("content")} always needs consent: required"
            return null
        }

        private fun decl(name: String, u: JSONObject) = ImageUseDecl(
            name = name,
            enabled = u.optBoolean("enabled", false),
            maxCallsPerHour = u.getInt("max_calls_per_hour"),
            consentRequired = u.optString("consent", "required") == "required",
            content = u.optString("content"),
            apps = u.optJSONArray("apps").strings().toSet(),
        )

        private fun JSONObject.pos(key: String): Double {
            val v = optDouble(key, Double.NaN)
            require(v > 0.0) { "image.$key must be a positive number" }
            return v
        }
    }
}

/**
 * The image-to-image request the fleet's image models take (OpenRouter chat completions with an image modality) and the
 * reading of its answer. The texts of [Failure] are the ones Cloud Canvas's Draw Enhancer showed when it made this call
 * itself (#1782), so moving the call here changes no message the user reads. Pure: the HTTP is [ImageEngine]'s.
 */
object ImageWire {
    enum class Kind { OFFLINE, AUTH, LIMIT, MODEL, SERVER, EMPTY }

    sealed class Result {
        class Image(val bytes: ByteArray, val mime: String, val cost: Double?) : Result()
        class Failure(val kind: Kind, val message: String) : Result()
    }

    fun mimeOf(bytes: ByteArray): String = if (bytes.size > 3 && bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"

    fun dataUrl(bytes: ByteArray, mime: String): String = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** One user message with the prompt and the picture, asking for an image back, with the run's cost in the answer. */
    fun body(model: String, prompt: String, image: ByteArray): String {
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", dataUrl(image, mimeOf(image)))))
        return JSONObject()
            .put("model", model)
            .put("modalities", JSONArray().put("image").put("text"))
            .put("stream", false)
            .put("usage", JSONObject().put("include", true))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .toString()
    }

    /** The first returned image (a data URL), or why there is none. */
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
            val o = JSONObject(body)
            val msg = o.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
            val url = msg.optJSONArray("images")?.getJSONObject(0)?.getJSONObject("image_url")?.getString("url")
            if (url == null || !url.contains("base64,")) Result.Failure(Kind.EMPTY, NO_IMAGE)
            else {
                val mime = url.substringAfter("data:", "").substringBefore(";", "").ifBlank { "image/png" }
                val cost = o.optJSONObject("usage")?.opt("cost")?.takeIf { it is Number }?.let { (it as Number).toDouble() }
                Result.Image(Base64.getDecoder().decode(url.substringAfter("base64,")), mime, cost)
            }
        }.getOrElse { Result.Failure(Kind.EMPTY, err.ifBlank { UNREADABLE }) }
    }

    /** A transport failure as the user reads it. */
    fun transport(e: Throwable): Result.Failure =
        if (e is UnknownHostException) Result.Failure(Kind.OFFLINE, NO_NETWORK)
        else Result.Failure(Kind.OFFLINE, "The request did not complete (" + e.javaClass.simpleName + ").")

    const val NO_IMAGE = "The model returned no image. Pick an image model in Configs > AI."
    const val UNREADABLE = "The answer could not be read."
    const val NO_NETWORK = "No network. Connect and try again."
}

/** What one image call answered: [answer] is the wire's JSON (never the picture), [image] the picture when ok. */
class ImageAnswer(val answer: JSONObject, val image: ByteArray?)

/**
 * #1799 the IMAGE half of the decisions engine: an app hands over a picture, a model id and a prompt and gets a picture
 * back, with the Account's OpenRouter token read in THIS process. [run] applies, in this order, and every way out but a
 * picture is `{"ok":false,"reason":...}`, with `kind` and `message` when the call itself failed:
 *
 *   no_image_policy / misconfigured / unknown_use / app_not_allowed / disabled    the declaration
 *   bad_request / bad_model / prompt_too_large / image_too_large                  the request, before anything is sent
 *   no_consent                                                                    the user's grant for this app and use
 *   offline / metered / battery_saver                                            the phone's state
 *   breaker_open                                                                  after repeated server or network failures
 *   no_key                                                                        the Account token, read in THIS process
 *   over_budget / app_quota / rate_limited                                        the image ledger (its own, not the decisions')
 *   network / http / empty / output_too_large                                     the call itself
 *
 * The journal gets one line per call (app, use, ✓/✗, reason, model, sizes, latency, cost): never the prompt, the picture
 * or the token.
 */
class ImageEngine(
    private val policy: ImagePolicy?,
    private val http: Http,
    private val token: () -> String?,
    private val env: Env,
    private val ledger: Ledger?,
    private val breaker: CircuitBreaker?,
    private val consent: ConsentBook,
    private val journal: Journal,
    private val clock: () -> Long,
) {
    /** The largest picture a call may hand over (0 = no image use is served); the service reads one byte more and the engine refuses it. */
    val maxInputBytes: Int get() = policy?.maxInputBytes ?: 0

    fun run(app: String, request: JSONObject, input: ByteArray): ImageAnswer {
        val name = request.optString("use")
        return try {
            go(app, name, request, input)
        } catch (e: Exception) {
            refuse(app, name, "exception")
        }
    }

    private fun go(app: String, name: String, request: JSONObject, input: ByteArray): ImageAnswer {
        val p = policy ?: return refuse(app, name, "no_image_policy")
        val ledger = ledger!!
        val breaker = breaker!!
        p.rejected[name]?.let { return refuse(app, name, "misconfigured") }
        val use = p.uses[name] ?: return refuse(app, name, "unknown_use")
        if (app !in use.apps) return refuse(app, name, "app_not_allowed")
        if (!use.enabled) return refuse(app, name, "disabled")
        val model = request.optString("model")
        val prompt = request.optString("prompt")
        if (prompt.isBlank() || input.isEmpty()) return refuse(app, name, "bad_request")
        if (!p.model.matches(model)) return refuse(app, name, "bad_model")
        if (prompt.length > p.maxPromptChars) return refuse(app, name, "prompt_too_large")
        if (input.size > p.maxInputBytes) return refuse(app, name, "image_too_large")
        if (!consent.granted(app, use.asUse())) return refuse(app, name, "no_consent")
        if (!env.online() && p.suppress.offline) return refuse(app, name, "offline", ImageWire.Kind.OFFLINE, ImageWire.NO_NETWORK)
        if (env.metered() && p.suppress.metered) return refuse(app, name, "metered")
        if (env.batterySaver() && p.suppress.batterySaver) return refuse(app, name, "battery_saver")
        if (!breaker.allow()) return refuse(app, name, "breaker_open")
        val key = token()
        if (key.isNullOrBlank()) return refuse(app, name, "no_key")
        ledger.admit(app, name, use.maxCallsPerHour)?.let { return refuse(app, name, it.wire) }
        val t0 = clock()
        val result = runCatching { http.send(p.endpoint, key, ImageWire.body(model, prompt, input), p.timeoutMs) }
            .fold({ ImageWire.parse(it.code, it.body) }, { ImageWire.transport(it) })
        val latency = clock() - t0
        val sizes = JSONObject().put("model", model).put("bytes_in", input.size).put("latency_ms", latency)
        return when (result) {
            is ImageWire.Result.Failure -> {
                if (result.kind == ImageWire.Kind.SERVER || result.kind == ImageWire.Kind.OFFLINE) breaker.failure()
                val reason = when (result.kind) {
                    ImageWire.Kind.OFFLINE -> "network"
                    ImageWire.Kind.EMPTY -> "empty"
                    else -> "http"
                }
                refuse(app, name, reason, result.kind, result.message, sizes)
            }
            is ImageWire.Result.Image -> {
                breaker.success()
                ledger.charge(result.cost)
                if (result.bytes.size > p.maxOutputBytes) return refuse(app, name, "output_too_large", extra = sizes)
                journal.record(app, name, "answered", JSONObject(sizes.toString()).put("kind", KIND)
                    .put("bytes_out", result.bytes.size).put("cost", result.cost ?: JSONObject.NULL))
                ImageAnswer(
                    JSONObject().put("ok", true).put("use", name).put("mime", result.mime).put("bytes", result.bytes.size)
                        .put("cost", result.cost ?: JSONObject.NULL).put("latency_ms", latency),
                    result.bytes,
                )
            }
        }
    }

    /** The image uses as the status method reports them: names and flags, never content. */
    fun status(app: String): JSONObject {
        val p = policy ?: return JSONObject().put("served", false)
        val uses = JSONObject()
        for ((n, u) in p.uses) {
            uses.put(n, JSONObject().put("enabled", u.enabled).put("allowed_for_app", app in u.apps).put("consent", consent.granted(app, u.asUse())))
        }
        return JSONObject().put("served", true).put("breaker_open", breaker!!.isOpen())
            .put("spent_today_usd", ledger!!.spentToday()).put("daily_usd_cap", p.budget.dailyUsdCap)
            .put("uses", uses).put("rejected", JSONArray(p.rejected.keys.toList()))
    }

    private fun refuse(
        app: String, use: String, reason: String, kind: ImageWire.Kind? = null, message: String? = null, extra: JSONObject? = null,
    ): ImageAnswer {
        val line = JSONObject((extra ?: JSONObject()).toString()).put("kind", KIND).put("reason", reason)
        journal.record(app, use, "refused", line)
        val answer = JSONObject().put("ok", false).put("use", use).put("reason", reason)
        kind?.let { answer.put("kind", it.name) }
        message?.let { answer.put("message", it) }
        return ImageAnswer(answer, null)
    }

    private companion object {
        const val KIND = "image"
    }
}

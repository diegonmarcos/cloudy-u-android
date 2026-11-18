package com.diegonmarcos.superapp.image.mlkit

import android.graphics.Bitmap
import android.util.Base64
import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * #772 image recognition behind ONE result shape, on two interchangeable routes:
 *
 *  - ml (the default, offline): ML Kit image labels and object boxes ([OnDevice]), OCR text and a
 *    ZXing barcode ([ImageScanner]), and the dominant colours ([Colours]).
 *  - openrouter: a decision model on OpenRouter's Decisions API (libs:decisions, the client Cloud
 *    Calc's Jev section speaks) asked one choice over the declared categories plus the declared
 *    extra questions; the photo rides as an image part and the colours as text, so a model that
 *    cannot see still has something to read. Its probabilities become [labels] and [answers].
 *
 * Everything the routes need arrives in the request (libs:ml-l-image/recognition.json via
 * RecognitionConfig.request), so this engine holds no declaration of its own. When the openrouter
 * route fails — no token, HTTP error, timeout — and the request allows it, the ml route answers
 * and the result says fell_back with the reason. Never throws: a failure is {"ok": false, "error"}.
 */
internal class Recognizer(
    private val scanner: ImageScanner,
    private val onDevice: OnDevice,
    private val http: Http,
    /** A token for [provider], or null; #1801 the service passes none (its [http] is keyed by the AI-key owner engine). */
    private val accountToken: (provider: String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class Label(val label: String, val p: Double)
    data class Box(val label: String, val p: Double, val x: Int, val y: Int, val w: Int, val h: Int)

    /** ML Kit's half of the on-device route, behind an interface so the suite can stand in for it. */
    interface OnDevice {
        fun labels(bitmap: Bitmap, minConfidence: Double, max: Int): List<Label>
        fun objects(bitmap: Bitmap, max: Int): List<Box>
    }

    fun json(image: ByteArray, request: String): String =
        runCatching { recognize(image, JSONObject(request)).toString() }
            .getOrElse { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName).toString() }

    fun recognize(image: ByteArray, req: JSONObject): JSONObject {
        val route = req.optString("route", ML)
        if (route != ML && route != OPENROUTER) return failure(route, "unknown route $route")
        val loaded = scanner.loadBitmap(image) ?: return failure(route, ImageScanner.CANNOT_DECODE)
        // One working copy at the declared size: below the scanner's own limits, so nothing it
        // calls rescales (and recycles) the bitmap the next step still needs.
        val work = fit(loaded, minOf(req.optJSONObject("ml")?.optInt("max_side", ImageScanner.OCR_MAX_DIMENSION) ?: ImageScanner.OCR_MAX_DIMENSION, ImageScanner.OCR_MAX_DIMENSION))
        try {
            if (route == ML) return onDeviceRoute(work, req, ML)
            val remote = openRouter(work, req)
            if (remote.optBoolean("ok")) return remote
            if (!req.optBoolean("fallback", true)) return remote
            return onDeviceRoute(work, req, OPENROUTER).put("fell_back", true).put("reason", remote.optString("error"))
        } finally {
            if (work !== loaded) work.recycle()
            loaded.recycle()
        }
    }

    // ── on device ────────────────────────────────────────────────────────────────────────────

    private fun onDeviceRoute(bmp: Bitmap, req: JSONObject, requested: String): JSONObject {
        val m = req.optJSONObject("ml") ?: JSONObject()
        val t0 = clock()
        val errors = JSONArray()
        val labels = runCatching { onDevice.labels(bmp, m.optDouble("min_confidence", 0.5), m.optInt("max_labels", 10)) }
            .getOrElse { errors.put("labels: ${it.message ?: it.javaClass.simpleName}"); emptyList() }
        val boxes = runCatching { onDevice.objects(bmp, m.optInt("max_objects", 5)) }
            .getOrElse { errors.put("objects: ${it.message ?: it.javaClass.simpleName}"); emptyList() }
        val ocr = if (m.optBoolean("ocr", true)) scanner.recognizeText(bmp) else null
        ocr?.optString("error")?.takeIf { it.isNotBlank() }?.let { errors.put("ocr: $it") }
        val barcode = if (m.optBoolean("barcode", true)) scanner.decodeBarcode(bmp) else null
        return result(ML, requested, bmp, req)
            .put("labels", JSONArray().apply { labels.forEach { put(label(it.label, it.p)) } })
            .put("boxes", JSONArray().apply { boxes.forEach { put(JSONObject().put("label", it.label).put("p", it.p).put("x", it.x).put("y", it.y).put("w", it.w).put("h", it.h)) } })
            .put("text", ocr?.optString("text").orEmpty())
            .put("barcode", barcode?.let { (format, raw) -> JSONObject().put("format", format).put("raw", raw) } ?: JSONObject.NULL)
            .put("latency_ms", clock() - t0)
            .put("errors", errors)
    }

    // ── OpenRouter ───────────────────────────────────────────────────────────────────────────

    private fun openRouter(bmp: Bitmap, req: JSONObject): JSONObject {
        val o = req.optJSONObject("openrouter") ?: return failure(OPENROUTER, "the request carries no openrouter block")
        val endpoint = o.optString("endpoint")
        if (!endpoint.startsWith("https://")) return failure(OPENROUTER, "the openrouter endpoint is not https — the token would travel in clear")
        // #1801 a key never arrives in the request (a caller's "token" is ignored): the engine passes an EngineKeyedHttp, the
        // fleet's AI-key owner adds the key, and [accountToken] answers null.
        val token = runCatching { accountToken(o.optString("account_provider", "openrouter")) }.getOrNull()
        val model = req.optString("model").ifBlank { o.optString("default_model") }
        val d = Decisions.post(endpoint, o.optInt("timeout_ms", 15000), http, token, model, state(bmp, req, o), questions(o), clock)
        if (!d.ok) return failure(OPENROUTER, d.error).put("model", model).put("latency_ms", d.latencyMs)
        val answers = JSONObject()
        d.answers?.keys()?.forEach { q -> if (q != CATEGORY) answers.put(q, JSONArray().apply { d.options(q).forEach { put(label(it.label, it.p)) } }) }
        return result(OPENROUTER, OPENROUTER, bmp, req)
            .put("labels", JSONArray().apply { d.options(CATEGORY).forEach { put(label(it.key, it.p)) } })
            .put("answers", answers)
            .put("model", model)
            .put("latency_ms", d.latencyMs)
            .put("cost", d.cost ?: JSONObject.NULL)
    }

    /** The declared category choice plus every declared extra question, as the API takes them. */
    fun questions(o: JSONObject): JSONObject {
        val q = JSONObject().put(CATEGORY, JSONObject().put("type", Decisions.CHOICE).put("instructions", o.getString("instructions")).put("criteria", o.getJSONObject("categories").stripDocs()))
        val extra = o.optJSONObject("questions") ?: JSONObject()
        extra.keys().asSequence().filterNot { it.startsWith("_") }.forEach { id ->
            val x = extra.getJSONObject(id)
            q.put(id, JSONObject().put("type", x.getString("type")).put("instructions", x.getString("instructions")).apply {
                x.opt("criteria")?.let { c -> put("criteria", if (c is JSONObject) c.stripDocs() else c) }
            })
        }
        return q
    }

    /** What the model is told: the measurements and context as text, the photo as an image part. */
    fun state(bmp: Bitmap, req: JSONObject, o: JSONObject): JSONArray {
        val measured = JSONObject().put("width", bmp.width).put("height", bmp.height).put("dominant_colours", colours(bmp, req))
        val context = req.optString("context")
        val text = "measured: $measured" + if (context.isNotBlank()) "\ncontext: $context" else ""
        return JSONArray()
            .put(JSONObject().put("type", "text").put("text", text))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", jpegDataUrl(bmp, o.optInt("image_side", 512)))))
    }

    // ── the shape both routes share ──────────────────────────────────────────────────────────

    private fun result(route: String, requested: String, bmp: Bitmap, req: JSONObject): JSONObject = JSONObject()
        .put("ok", true).put("route", route).put("requested", requested).put("fell_back", false).put("reason", "")
        .put("labels", JSONArray()).put("boxes", JSONArray()).put("text", "").put("barcode", JSONObject.NULL)
        .put("answers", JSONObject()).put("model", "").put("cost", JSONObject.NULL)
        .put("colours", colours(bmp, req)).put("width", bmp.width).put("height", bmp.height)

    private fun failure(route: String, why: String): JSONObject = JSONObject()
        .put("ok", false).put("route", route).put("requested", route).put("error", why)

    private fun label(l: String, p: Double) = JSONObject().put("label", l).put("p", p)

    /** The dominant colours, named in the declared vocabulary. */
    fun colours(bmp: Bitmap, req: JSONObject): JSONArray {
        val c = req.optJSONObject("colours") ?: JSONObject()
        val names = (c.optJSONObject("names") ?: JSONObject()).let { n ->
            n.keys().asSequence().filterNot { it.startsWith("_") }.mapNotNull { k -> Colours.parseHex(n.getString(k))?.let { k to it } }.toMap()
        }
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return JSONArray().apply {
            Colours.dominant(px, c.optInt("max", 5)).forEach { s ->
                put(JSONObject().put("hex", s.hex).put("name", Colours.name(s.rgb, names) ?: JSONObject.NULL).put("share", Math.round(s.share * 1000) / 1000.0))
            }
        }
    }

    private fun jpegDataUrl(bmp: Bitmap, side: Int): String {
        val small = fit(bmp, side)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 85, out)
        if (small !== bmp) small.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun JSONObject.stripDocs(): JSONObject = JSONObject().also { o -> keys().asSequence().filterNot { it.startsWith("_") }.forEach { o.put(it, get(it)) } }

    companion object {
        /** The decision models of an OpenRouter catalogue body: slug, image input, USD per prompt token. */
        fun catalogue(body: String): JSONArray {
            val data = JSONObject(body).getJSONArray("data")
            val out = JSONArray()
            for (i in 0 until data.length()) {
                val m = data.getJSONObject(i)
                val arch = m.optJSONObject("architecture")
                fun has(field: String, v: String) = arch?.optJSONArray(field)?.let { a -> (0 until a.length()).any { a.getString(it) == v } } == true
                if (!has("output_modalities", "decisions")) continue
                out.put(JSONObject().put("slug", m.getString("id")).put("images", has("input_modalities", "image"))
                    .put("prompt_price", m.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull()?.takeIf { it >= 0 } ?: JSONObject.NULL))
            }
            return out
        }

        const val ML = "ml"
        const val OPENROUTER = "openrouter"
        /** The id of the category choice in a request and its answers. */
        const val CATEGORY = "category"
    }
}

/** [bmp] scaled so its longest side is at most [side] (the same bitmap when it already fits); shared with [Detector]. */
internal fun fit(bmp: Bitmap, side: Int): Bitmap {
    val longest = maxOf(bmp.width, bmp.height)
    if (side <= 0 || longest <= side) return bmp
    val s = side.toFloat() / longest
    return Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * s).toInt()), maxOf(1, (bmp.height * s).toInt()), true)
}

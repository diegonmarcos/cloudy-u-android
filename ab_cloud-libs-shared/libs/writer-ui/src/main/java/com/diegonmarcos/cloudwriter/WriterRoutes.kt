package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R
import com.diegonmarcos.cloudwriter.writerui.BuildConfig

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Base64
import com.diegonmarcos.cloudwriter.core.Answer
import com.diegonmarcos.cloudwriter.core.CatalogueModel
import com.diegonmarcos.cloudwriter.core.OpenRouter
import com.diegonmarcos.cloudwriter.core.OpenRouterConfig
import com.diegonmarcos.cloudwriter.core.Outcome
import com.diegonmarcos.cloudwriter.core.Route
import com.diegonmarcos.cloudwriter.core.Routing
import com.diegonmarcos.cloudwriter.core.Wav
import com.diegonmarcos.cloudwriter.core.Ocr
import com.diegonmarcos.cloudwriter.core.OcrEngine
import com.diegonmarcos.superapp.mlocr.MlKitOcr
import com.diegonmarcos.superapp.mlocr.OcrImage
import com.diegonmarcos.superapp.mlocr.VisionOcr
import android.graphics.Bitmap
import com.diegonmarcos.superapp.decisions.EngineKeyedHttp
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.decisions.UrlHttp
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import com.diegonmarcos.superapp.decisions.link.RelayReply
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject

/**
 * #800 Speech-to-text and Translation, each on its own ROUTE (the Camera/Calc #799 shape):
 *
 *   MODEL      an OpenRouter model, paid with the fleet Account's token
 *   ON-DEVICE  Vosk (speech) / ML Kit through libs:translate (translation), offline
 *
 * Default = model, and an automatic fallback to on-device when offline, with no token, or on any
 * model error — never the reverse. Every call answers an [Answer] that names the route that
 * actually answered, and the last one per function is kept for the screen and /api/writer/route.
 * The decision and the fallback are core's [Routing]; this file is only the Android around it.
 *
 * THE KEY IS NOT HERE (#1801). It lives in the fleet's AI-key owner engine (Cloud-Lib-Decisions-
 * Engine, its AI keys screen) and nowhere else: every model request goes there through [relayHttp]
 * (DecisionsLink.relay, use writer_routes), the engine adds the key and returns the provider's answer.
 * What is known here is only whether the engine holds one ([accountHasKey]: DecisionsLink.keys), which
 * is what the route's "no token" blocker reads — the same handling Cloud Calc's JevStore and the image
 * engine use.
 *
 * Every call here BLOCKS (binder, network). Callers run it off the main thread.
 */
object WriterRoutes {

    enum class Function(val key: String) {
        SPEECH("speech"),
        TRANSLATION("translation"),
    }

    val config: JSONObject by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.WRITER_ROUTES_B64, Base64.DEFAULT), Charsets.UTF_8)) }
            .getOrDefault(JSONObject())
    }

    private val openRouterBlock: JSONObject get() = config.optJSONObject("openrouter") ?: JSONObject()
    val speech: JSONObject get() = config.optJSONObject("speech") ?: JSONObject()
    val translation: JSONObject get() = config.optJSONObject("translation") ?: JSONObject()
    val voiceEngine: JSONObject get() = config.optJSONObject("voice_engine") ?: JSONObject()

    val openRouter: OpenRouterConfig by lazy {
        OpenRouterConfig(
            openRouterBlock.optString("chat_url"),
            openRouterBlock.optString("models_url"),
            openRouterBlock.optInt("timeout_ms", 30000),
            openRouterBlock.optInt("max_tokens", 2048),
        )
    }

    // ── preferences (in WriterPrefs' own file, fleet-config `text_tools`; no new store) ──────

    const val KEY_ROUTE_PREFIX = "route_"
    const val KEY_ROUTE_MODEL_PREFIX = "route_model_"
    const val KEY_LISTEN_TRANSLATE = "listen_translate"
    const val KEY_LISTEN_TARGET = "listen_translate_target"
    const val KEY_LISTEN_MODE = "listen_translate_mode"
    const val KEY_LISTEN_LANGUAGE = "listen_language"
    private const val KEY_CATALOGUE = "routes_catalogue_body"

    /** "" = let the speech engine / model decide. */
    const val LANGUAGE_AUTO = ""

    private fun block(f: Function): JSONObject = if (f == Function.SPEECH) speech else translation

    fun defaultRoute(f: Function): Route = Route.of(block(f).optString("default_route"), Route.MODEL)

    fun route(context: Context, f: Function): Route {
        val saved = WriterPrefs.prefs(context).getString(KEY_ROUTE_PREFIX + f.key, null)
        // #882 Jev may fill an UNCHOSEN route only; no advice = the default, exactly as before
        return Routing.chosen(saved, if (saved == null) WriterJev.advice(context, f) else null, defaultRoute(f))
    }

    fun setRoute(context: Context, f: Function, route: Route) =
        WriterPrefs.put(context, KEY_ROUTE_PREFIX + f.key, route.id)

    /** Non-null only when the route in force came from the decision model (the owner saved none). */
    private fun advisedOrNull(context: Context, f: Function): Route? =
        if (WriterPrefs.prefs(context).contains(KEY_ROUTE_PREFIX + f.key)) null else route(context, f)

    fun defaultModel(f: Function): String = block(f).optString("default_model")

    fun model(context: Context, f: Function): String =
        WriterPrefs.prefs(context).getString(KEY_ROUTE_MODEL_PREFIX + f.key, null)?.takeIf { it.isNotBlank() } ?: defaultModel(f)

    fun setModel(context: Context, f: Function, id: String) =
        WriterPrefs.put(context, KEY_ROUTE_MODEL_PREFIX + f.key, id)

    fun listenTranslate(context: Context): Boolean = WriterPrefs.flag(context, KEY_LISTEN_TRANSLATE, false)

    fun listenTarget(context: Context): String =
        WriterPrefs.string(context, KEY_LISTEN_TARGET, translation.optString("default_target", "english"))

    fun listenMode(context: Context): String =
        WriterPrefs.string(context, KEY_LISTEN_MODE, translation.optString("default_mode", "alongside"))

    fun listenLanguage(context: Context): String = WriterPrefs.string(context, KEY_LISTEN_LANGUAGE, LANGUAGE_AUTO)

    /** The BCP-47 code of a writer_ai.languages id (its `tag`), what the on-device engines take. */
    fun tagOf(languageId: String?): String? {
        if (languageId.isNullOrBlank()) return null
        return WriterRegistry.registry.optJSONObject("languages")?.optJSONObject(languageId)
            ?.optString("tag")?.takeIf { it.isNotBlank() }
    }

    /** A language id from an id or a tag ("es" → "spanish"); null when neither names a language. */
    fun languageIdOf(idOrTag: String): String? {
        val langs = WriterRegistry.registry.optJSONObject("languages") ?: return null
        if (langs.has(idOrTag) && idOrTag != "keep") return idOrTag
        return langs.keys().asSequence().firstOrNull { tagOf(it).equals(idOrTag, ignoreCase = true) }
    }

    // ── the world ────────────────────────────────────────────────────────────────────────────

    fun online(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    @Volatile private var tools: TextToolsClient? = null

    /** The process's binder to the serving app, waited for (a fresh bind answers through a callback). */
    fun textTools(context: Context): TextToolsClient {
        val c = tools ?: synchronized(this) { tools ?: TextToolsClient(context.applicationContext).also { tools = it } }
        if (c.isServingAppInstalled()) {
            val until = SystemClock.elapsedRealtime() + openRouterBlock.optLong("account_wait_ms", 3000L)
            while (!c.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        }
        return c
    }

    /** The declared relay use (decisions.json::relay.uses). */
    const val RELAY_USE = "writer_routes"

    /** Tests replace the engine's answer for one relayed body. */
    @Volatile var relayer: (Context, String) -> RelayReply = { ctx, body -> DecisionsLink.relay(ctx, RELAY_USE, "chat", body) }

    /**
     * #1801 every model request's transport: the body to the AI-key owner engine, which adds the key; a refusal (no engine,
     * no key) is a transport failure with the engine's reason, so the route falls back on device and says why.
     */
    fun relayHttp(context: Context): Http = object : EngineKeyedHttp {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            if (body == null) return UrlHttp.send(url, null, null, timeoutMs)
            val r = relayer(context.applicationContext, body)
            if (!r.ok) throw java.io.IOException(r.why)
            return Http.Response(r.code, r.body)
        }
    }

    /** Tests replace the engine's key status. */
    @Volatile var keyStatus: (Context, String) -> Boolean = { ctx, provider -> engineHasKey(ctx, provider) }

    /** Whether the AI-key owner engine holds a key for the route's provider (presence only; the key is never read here). */
    fun accountHasKey(context: Context): Boolean = keyStatus(context, openRouterBlock.optString("account_provider", "openrouter"))

    /** The engine's AI keys screen with the route's provider preselected (Routes ▸ AI key); false with no engine. */
    fun openKeySettings(context: Context): Boolean =
        DecisionsLink.openKeySettings(context, openRouterBlock.optString("account_provider", "openrouter"))

    private fun engineHasKey(context: Context, provider: String): Boolean {
        val k = DecisionsLink.keys(context)
        val list = k.optJSONArray("providers") ?: return false
        return (0 until list.length()).any { list.optJSONObject(it)?.let { p -> p.optString("id") == provider && p.optBoolean("key_present") } == true }
    }

    // ── the two functions ────────────────────────────────────────────────────────────────────

    @Volatile var lastSpeech: Answer? = null
        private set
    @Volatile var lastTranslation: Answer? = null
        private set

    /**
     * Translate [text] into the writer_ai.languages entry [languageId] on the Translation route.
     * Model: the row's translate prompt to the chosen OpenRouter model. On-device: libs:translate
     * through the serving app (ITextTools.translate → ML Kit), addressed by the row's tag.
     */
    fun translate(context: Context, text: String, languageId: String): Answer {
        val requested = route(context, Function.TRANSLATION)
        val system = WriterRegistry.translatePrompt(languageId)
            ?: return Answer(null, null, requested, null, context.getString(R.string.run_translate_no_target)).also { lastTranslation = it }
        val modelId = model(context, Function.TRANSLATION)
        val blocker = if (requested == Route.MODEL) {
            val online = online(context)
            Routing.modelBlocker(online, online && accountHasKey(context), modelId)
        } else null
        val tag = tagOf(languageId)
        val answer = Routing.run(
            requested,
            blocker,
            model = { OpenRouter.translate(relayHttp(context), openRouter, null, modelId, system, text) },
            ml = {
                if (tag == null) Outcome.failed(context.getString(R.string.route_no_device_language, languageId))
                else textTools(context).translate(text, tag).let { r -> r.text?.let { Outcome.ok(it) } ?: Outcome.failed(r.error ?: Routing.EMPTY) }
            },
        )
        lastTranslation = answer
        WriterJev.observed(context, Function.TRANSLATION, advisedOrNull(context, Function.TRANSLATION), answer)
        return answer
    }

    /**
     * Transcribe one Listen segment on the Speech route. [onDevice] is what the on-device engine
     * heard over the same stretch of audio — Listen streams every frame to Vosk as well, so the
     * fallback costs no second pass and the live partial text exists on both routes.
     */
    // ── #1418 OCR (Load image / Take picture in every input box) ─────────────────────────────

    const val KEY_OCR_ENGINE = "ocr_engine"
    const val KEY_OCR_MODEL = "ocr_model"

    private val ocrBlock: JSONObject get() = config.optJSONObject("ocr") ?: JSONObject()

    fun ocrEngine(context: Context): OcrEngine =
        OcrEngine.of(WriterPrefs.prefs(context).getString(KEY_OCR_ENGINE, null) ?: ocrBlock.optString("default_engine", OcrEngine.DEFAULT.id))

    fun setOcrEngine(context: Context, engine: OcrEngine) = WriterPrefs.put(context, KEY_OCR_ENGINE, engine.id)

    fun defaultOcrModel(): String = ocrBlock.optString("default_model")

    fun ocrModel(context: Context): String =
        WriterPrefs.prefs(context).getString(KEY_OCR_MODEL, null)?.takeIf { it.isNotBlank() } ?: defaultOcrModel()

    fun setOcrModel(context: Context, id: String) = WriterPrefs.put(context, KEY_OCR_MODEL, id)

    @Volatile var lastOcr: Answer? = null
        private set

    /**
     * Reads the text in [bitmap] on the engine set in Configs: ML on-device (libs:ml-l-image-ocr's ML Kit),
     * or an OpenRouter vision model with the fleet Account's token, falling back to ML with the
     * reason (core Ocr.run = Routing.run). Blocking: call from a worker.
     */
    fun ocr(context: Context, bitmap: Bitmap): Answer {
        val engine = ocrEngine(context)
        val modelId = ocrModel(context)
        val blocker = if (engine == OcrEngine.OPENROUTER) {
            val online = online(context)
            Routing.modelBlocker(online, online && accountHasKey(context), modelId)
        } else null
        val vision = VisionOcr { model, prompt, jpeg ->
            val o = OpenRouter.call(relayHttp(context), openRouter, null, OpenRouter.visionBody(model, prompt, jpeg, "image/jpeg", openRouter.maxTokens))
            if (o.ok) Result.success(o.text!!) else Result.failure(IllegalStateException(o.error ?: Routing.EMPTY))
        }
        val answer = Ocr.run(
            engine,
            blocker,
            openRouter = {
                vision.read(OcrImage.jpeg(bitmap), modelId, Ocr.PROMPT)
                    .fold({ Outcome.ok(it) }, { Outcome.failed(it.message ?: Routing.EMPTY) })
            },
            ml = { MlKitOcr.read(bitmap).let { r -> r.text?.let { Outcome.ok(it) } ?: Outcome.failed(r.error ?: Routing.EMPTY) } },
        )
        lastOcr = answer
        return answer
    }

    /** The cached catalogue's vision models (image in, text out), cheapest first. */
    fun visionModels(context: Context): List<CatalogueModel> = OpenRouter.visionModels(cachedCatalogue(context))

    fun transcribe(context: Context, pcm: ByteArray, blockerAtStart: String?, onDevice: () -> Outcome): Answer {
        val requested = route(context, Function.SPEECH)
        val modelId = model(context, Function.SPEECH)
        val blocker = blockerAtStart ?: if (requested == Route.MODEL) {
            val online = online(context)
            Routing.modelBlocker(online, online && accountHasKey(context), modelId)
        } else null
        val answer = Routing.run(
            requested,
            blocker,
            model = { OpenRouter.transcribe(relayHttp(context), openRouter, null, modelId, speechPrompt(context), Wav.encode(pcm, sampleRate)) },
            ml = onDevice,
        )
        lastSpeech = answer
        WriterJev.observed(context, Function.SPEECH, advisedOrNull(context, Function.SPEECH), answer)
        return answer
    }

    /** Why the speech model cannot run at all right now, checked once when Listen starts. */
    fun speechBlocker(context: Context): String? {
        if (route(context, Function.SPEECH) != Route.MODEL) return null
        val online = online(context)
        return Routing.modelBlocker(online, online && accountHasKey(context), model(context, Function.SPEECH))
    }

    val sampleRate: Int get() = speech.optInt("sample_rate", 16000)

    private fun speechPrompt(context: Context): String {
        val base = speech.optString("prompt")
        val lang = listenLanguage(context).takeIf { it.isNotBlank() } ?: return base
        val label = WriterRegistry.language(lang).label
        return base + " " + String.format(speech.optString("language_hint", "%s"), label)
    }

    // ── the live catalogue, for the pickers ──────────────────────────────────────────────────

    /** The last catalogue fetched, parsed; empty before the first fetch. */
    fun cachedCatalogue(context: Context): List<CatalogueModel> =
        WriterPrefs.prefs(context).getString(KEY_CATALOGUE, null)?.let { OpenRouter.catalogue(it) }.orEmpty()

    /** Fetch the live catalogue (no token: it is public) and keep it for the next open of the page. BLOCKS. */
    fun fetchCatalogue(context: Context): Result<List<CatalogueModel>> = runCatching {
        val res = UrlHttp.send(openRouter.modelsUrl, null, null, openRouter.timeoutMs)
        require(res.code in 200..299) { "HTTP ${res.code} from the model catalogue" }
        val models = OpenRouter.catalogue(res.body)
        require(models.isNotEmpty()) { "the model catalogue listed nothing" }
        WriterPrefs.put(context, KEY_CATALOGUE, res.body)
        models
    }

    // ── /api/writer/route ────────────────────────────────────────────────────────────────────

    fun routeJson(context: Context, serving: ServingApp?): JSONObject {
        fun fn(f: Function, last: Answer?) = JSONObject()
            .put("route", route(context, f).id)
            .put("default_route", defaultRoute(f).id)
            .put("model", model(context, f))
            .put("default_model", defaultModel(f))
            .put("last", last?.toJson() ?: JSONObject.NULL)
        return JSONObject()
            .put("speech", fn(Function.SPEECH, lastSpeech))
            .put("translation", fn(Function.TRANSLATION, lastTranslation))
            .put("online", online(context))
            .put("account_key_present", accountHasKey(context))
            .put("serving_app", serving?.let {
                JSONObject().put("state", it.state.name).put("package", it.packageName ?: JSONObject.NULL)
                    .put("label", it.label ?: JSONObject.NULL).put("version", it.version ?: JSONObject.NULL)
            } ?: JSONObject.NULL)
    }
}

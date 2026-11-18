package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.diegonmarcos.superapp.ai.AiMsg
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.browser.BrowserAddon
import com.diegonmarcos.superapp.browser.BrowserBus
import com.diegonmarcos.superapp.browser.PageSummary
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.summarization.Summarization
import com.google.mlkit.genai.summarization.SummarizationRequest
import com.google.mlkit.genai.summarization.SummarizerOptions
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * #823 "Summarize the page" on the user's route (setting `summarize_route`, declared in
 * build.json::ui.browser.addons[ai].summarize): the fleet model through libs:ai's OpenRouter backend
 * with the fleet Account token read per call, or on the phone — ML Kit's on-device summarizer
 * (Gemini Nano, only on phones that ship AICore) and, where that is not available, the
 * extractive summary [PageSummary.extractive]. [PageSummary.routed] is the one fallback rule;
 * every answer names its route and engine. The page text is read live over [BrowserBus].
 */
class PageSummarizer(private val app: Context, private val search: SearchAddon, addon: BrowserAddon) {

    private val cfg: JSONObject = addon.config.optJSONObject("summarize") ?: JSONObject()
    val defaultRoute: String = PageSummary.route(cfg.optString("default_route"), PageSummary.MODEL)
    private val fallback = cfg.optBoolean("fallback", true)
    private val inputCap = cfg.optInt("input_cap_chars", 12_000)
    private val sentences = cfg.optInt("extractive_sentences", 4)
    private val bullets = cfg.optInt("bullets", 3).coerceIn(1, 3)
    private val prompt = cfg.optString("prompt").ifBlank { "Summarize this web page in %d short bullet points. Answer in the page's language." }
    private val model = addon.config.optString("model").ifBlank { search.ai.defaultModel }

    /** The live page's summary on [chosen] (null = the setting's / declared default). Blocking. */
    fun summarizePage(chosen: String?): JSONObject {
        val page = BrowserBus.call("page_text", mapOf("n" to inputCap.toString()), timeoutMs = 20_000)
        if (!page.optBoolean("ok")) return JSONObject().put("ok", false).put("error", page.optString("error", "no page is open"))
        val r = summarize(page.optString("title"), page.optString("text"), chosen)
        return r.json().put("title", page.optString("title")).put("url", page.optString("url"))
    }

    fun summarize(title: String, text: String, chosen: String?): PageSummary.Result {
        val route = PageSummary.route(chosen, defaultRoute)
        if (text.isBlank()) return PageSummary.Result.failed(route, "none", "the page has no text")
        return PageSummary.routed(route, online(), null, fallback,
            onDevice = { onDevice(text) },
            model = { viaModel(title, text) })
    }

    private fun online(): Boolean? = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrNull()

    private fun viaModel(title: String, text: String): PageSummary.Result {
        val ai = search.ai
        val backend = search.backend(SearchAddon.relay(app))
        val ask = prompt.format(bullets) + "\n\n" + title + "\n\n" + text.take(inputCap)
        val r = backend.chat(AiRequest(model, listOf(AiMsg("user", ask)), stream = false)) {}
        val out = r.text?.takeIf { it.isNotBlank() } ?: return PageSummary.Result.failed(PageSummary.MODEL, PageSummary.ENGINE_MODEL,
            r.error ?: "no answer")
        return PageSummary.Result(true, out, PageSummary.MODEL, "${PageSummary.ENGINE_MODEL}:$model", PageSummary.MODEL)
    }

    /** ML Kit when the phone has it ready; otherwise the extractive summary, saying why. */
    private fun onDevice(text: String): PageSummary.Result {
        val (ml, why) = mlkit(text)
        if (ml != null) return PageSummary.Result(true, ml, PageSummary.ON_DEVICE, PageSummary.ENGINE_MLKIT, PageSummary.ON_DEVICE)
        val ex = PageSummary.extractive(text, sentences)
        return if (ex.isBlank()) PageSummary.Result.failed(PageSummary.ON_DEVICE, PageSummary.ENGINE_EXTRACTIVE, "the page has no sentences to summarize; ML Kit: $why")
            else PageSummary.Result(true, ex, PageSummary.ON_DEVICE, PageSummary.ENGINE_EXTRACTIVE, PageSummary.ON_DEVICE, reason = "ML Kit: $why")
    }

    /** The summary, or null and why ML Kit's on-device summarizer did not answer. Never throws. */
    private fun mlkit(text: String): Pair<String?, String> = runCatching {
        val opts = SummarizerOptions.builder(app)
            .setInputType(SummarizerOptions.InputType.ARTICLE)
            .setOutputType(when (bullets) { 1 -> SummarizerOptions.OutputType.ONE_BULLET; 2 -> SummarizerOptions.OutputType.TWO_BULLETS; else -> SummarizerOptions.OutputType.THREE_BULLETS })
            .setLanguage(SummarizerOptions.Language.ENGLISH)
            .setLongInputAutoTruncationEnabled(true)
            .build()
        val s = Summarization.getClient(opts)
        try {
            when (val st = s.checkFeatureStatus().get(10, TimeUnit.SECONDS)) {
                FeatureStatus.AVAILABLE -> {
                    val out = s.runInference(SummarizationRequest.builder(text).build()).get(90, TimeUnit.SECONDS).summary
                    if (out.isNullOrBlank()) null to "it answered nothing" else out to ""
                }
                FeatureStatus.UNAVAILABLE -> null to "this phone has no on-device summarizer (AICore / Gemini Nano)"
                FeatureStatus.DOWNLOADING -> null to "its model is still downloading"
                FeatureStatus.DOWNLOADABLE -> null to "its model is not downloaded yet"
                else -> null to "status $st"
            }
        } finally { s.close() }
    }.getOrElse { null to (it.message ?: it.javaClass.simpleName) }

    companion object {
        @Volatile private var instance: PageSummarizer? = null

        fun get(app: Context, search: SearchAddon, addon: BrowserAddon): PageSummarizer = instance ?: synchronized(this) {
            instance ?: PageSummarizer(app.applicationContext, search, addon).also { instance = it }
        }
    }
}

package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import android.util.Base64
import com.diegonmarcos.cloudbrowser.BuildConfig
import com.diegonmarcos.cloudsearch.core.SearchConfig
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiHost
import com.diegonmarcos.superapp.ai.AiMemoryPrefs
import com.diegonmarcos.superapp.ai.AiMemorySessions
import com.diegonmarcos.superapp.ai.AiRelay
import com.diegonmarcos.superapp.ai.AiRelayBackend
import com.diegonmarcos.superapp.ai.AiRelayed
import com.diegonmarcos.superapp.ai.AiSession
import com.diegonmarcos.superapp.ai.AiSink
import com.diegonmarcos.superapp.ai.AiStandardHost
import com.diegonmarcos.superapp.browser.BrowserSearch
import com.diegonmarcos.superapp.browser.BrowserSearchEngine
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 I8 the Search add-on's engine side: cloud-search's own declaration (baked from
 * ac_cloud-search/build.json::search as BuildConfig.SEARCH_CONFIG_B64) for the engines, and libs:ai for the
 * chat: its OpenRouter backend, its sessions and its shared contract (baked from assets/ai/ai.json as
 * BuildConfig.AI_CONFIG_B64), linked by reference. #1801 THE OPENROUTER KEY IS NOT HERE: every chat body goes to the
 * fleet's AI-key owner engine (DecisionsLink.relay, use browser_search_ai), which adds the key in its own process and
 * returns the provider's answer; this app never holds, reads, stores or answers it, and an engine refusal (no engine, no
 * key) is the turn's error in the engine's words. Chat sessions live in memory for the life of the process.
 */
class SearchAddon(val cfg: SearchConfig, val ai: AiConfig = fromBuild()) {

    data class Outcome(val session: AiSession, val error: String?, val citations: List<String>)

    private val sessions = AiMemorySessions()

    fun engines(): List<BrowserSearchEngine> = cfg.engines.map { BrowserSearchEngine(it.id, it.label, it.url) }

    fun enginesJson(): JSONArray = JSONArray().also { a ->
        cfg.engines.forEach { a.put(JSONObject().put("id", it.id).put("label", it.label).put("url", it.url)) }
    }

    /** The results URL for [q] on [engineId] (the first declared engine when absent), or null for an unknown id. */
    fun searchUrl(q: String, engineId: String?): String? {
        val e = engines().let { all -> if (engineId.isNullOrBlank()) all.firstOrNull() else all.firstOrNull { it.id == engineId } }
            ?: return null
        return BrowserSearch.searchUrl(q, e)
    }

    fun sessionsJson(): JSONArray = JSONArray().also { a ->
        sessions.all().forEach {
            a.put(JSONObject().put("id", it.id).put("title", it.title).put("model", it.model).put("updated", it.updated).put("turns", it.messages.size))
        }
    }

    /** libs:ai's OpenRouter route through [relay]: the body to the AI-key owner engine, its answer back (#1801). */
    fun backend(relay: AiRelay) = AiRelayBackend(ai, relay)

    /** The AI page's host (libs:ai's standard host on this add-on's sessions); [open] opens a source or result as a tab. */
    fun host(app: Context, open: (String) -> Unit): AiHost =
        AiStandardHost(ai, sessions, AiMemoryPrefs(ai.defaultModel), direct = backend(relay(app)), opener = open)

    /** One send (blocking: call off the main thread). [relay] is called only after the message is recorded. */
    fun send(sessionId: String?, text: String, model: String?, web: Boolean, relay: AiRelay): Outcome {
        val m = model?.takeIf { it.isNotBlank() } ?: ai.defaultModel
        val host = AiStandardHost(ai, sessions, AiMemoryPrefs(m, web), direct = backend(relay))
        val prior = sessionId?.let { id -> sessions.all().firstOrNull { it.id == id } } ?: host.newSession(m, System.currentTimeMillis())
        val o = host.send(prior, text, m, web, System.currentTimeMillis(), AiSink.None)
        return Outcome(o.session, o.error, o.citations)
    }

    /** The debug answer for a send: the session and the last message, never a header or the token. */
    fun outcomeJson(o: Outcome): JSONObject = JSONObject().put("ok", o.error == null).put("session", o.session.id)
        .put("model", o.session.model).put("error", o.error ?: JSONObject.NULL)
        .put("message", o.session.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.content ?: JSONObject.NULL)
        .put("citations", JSONArray(o.citations))

    companion object {
        @Volatile private var instance: SearchAddon? = null

        /** The shared AI contract baked into this build (BuildConfig.AI_CONFIG_B64). */
        private fun fromBuild(): AiConfig = AiConfig.parse(String(java.util.Base64.getDecoder().decode(BuildConfig.AI_CONFIG_B64)))

        fun get(b64: String): SearchAddon = instance ?: synchronized(this) {
            instance ?: SearchAddon(SearchConfig.parse(String(Base64.decode(b64, Base64.DEFAULT)))).also { instance = it }
        }

        /** The declared relay use (decisions.json::relay.uses) and the endpoint it may reach. */
        const val USE = "browser_search_ai"
        const val ENDPOINT = "chat"

        /** Tests replace the engine call. */
        @Volatile var relayer: (Context, String) -> AiRelayed = { ctx, body ->
            DecisionsLink.relay(ctx, USE, ENDPOINT, body).let { r -> if (r.ok) AiRelayed(r.code, r.body) else AiRelayed(0, "", r.why) }
        }

        /** The relay every chat of this add-on goes through: the engine adds the key, this app never sees it. */
        fun relay(ctx: Context): AiRelay = AiRelay { body -> relayer(ctx.applicationContext, body) }

        /** The engine's AI keys screen with [provider] preselected (Search add-on ▸ Set key); false with no engine. */
        fun openKeySettings(ctx: Context, provider: String): Boolean = DecisionsLink.openKeySettings(ctx, provider)
    }
}

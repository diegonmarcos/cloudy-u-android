package com.diegonmarcos.cloudbrowser.search

import android.content.Context
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiTool
import com.diegonmarcos.superapp.browser.AgentLoop
import com.diegonmarcos.superapp.browser.BrowserAddon
import com.diegonmarcos.superapp.browser.BrowserAgentHost
import com.diegonmarcos.superapp.browser.BrowserBus
import com.diegonmarcos.superapp.browser.PageSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * #802 I9 the AI Chat add-on's runner: one [AgentLoop] per session, the model reached with
 * libs:ai's OpenRouter backend (the tools as its AiTool specs, the wire messages the loop keeps) and the fleet
 * Account's token (read per model call, never kept), every tool executed on the live page through [BrowserBus]. A
 * mutating tool stops the turn: the consent sheet is put on screen and the turn resumes only
 * from his Allow/Deny ([BrowserAgentHost.decide]). The debug route can ask, never allow.
 */
class AgentRunner(private val app: Context, private val search: SearchAddon, addon: BrowserAddon) {

    private val cfg = addon.config
    private val tools = AgentLoop.parseTools(cfg.optJSONArray("tools"))
    private val cap = cfg.optInt("max_tool_calls_per_turn", 6)
    private val model = cfg.optString("model").ifBlank { search.ai.defaultModel }
    /** The tools as the model sees them. The loop executes them (on the live page, behind the consent card), never the backend. */
    private val specs: List<AiTool> = tools.map { t ->
        object : AiTool {
            override val name = t.id
            override val description = t.description
            override val schema = t.schema().toString()
            override fun run(args: JSONObject): String = "executed by the agent loop"
        }
    }
    private val backend = search.backend(SearchAddon.relay(app))
    private val sessions = LinkedHashMap<String, AgentLoop>()
    /** #823 summarize_page on the user's route: `model` hands the page text to the model, `on_device` summarizes here. */
    private val summarizer = PageSummarizer.get(app, search, addon)
    /** The `summarize_route` setting (set by the app; null = the declared default). */
    @Volatile var summarizeRoute: () -> String? = { null }

    private fun route(t: com.diegonmarcos.superapp.browser.AgentTool): String =
        if (t.id == "summarize_page") PageSummary.route(summarizeRoute(), summarizer.defaultRoute) else t.route

    val toolsJson: JSONArray get() = JSONArray(tools.map {
        JSONObject().put("id", it.id).put("label", it.label).put("mutating", it.mutating).put("confirm", it.confirm).put("route", route(it))
    })

    fun sessionsJson(): JSONArray = synchronized(sessions) {
        JSONArray(sessions.map { (id, l) -> JSONObject().put("id", id).put("messages", l.messages.length()).put("pending", l.pending?.name ?: JSONObject.NULL) })
    }

    /** One message from him in [session] (created when new). Blocking: off the main thread. */
    fun ask(session: String, text: String): JSONObject {
        val loop = synchronized(sessions) { sessions.getOrPut(session) { AgentLoop(tools, cap) } }
        synchronized(loop) {
            loop.pending?.let { return JSONObject().put("ok", false).put("session", session).put("status", "pending_confirmation")
                .put("tool", it.name).put("error", "answer the confirmation on screen first") }
            loop.user(text)
            return step(session, loop)
        }
    }

    /** #823 New chat: the session is forgotten (a waiting action with it: it never runs). */
    fun reset(session: String) { synchronized(sessions) { sessions.remove(session) } }

    /** His decision from the sheet. */
    fun decide(callId: String, allow: Boolean): JSONObject {
        val (session, loop) = synchronized(sessions) { sessions.entries.firstOrNull { it.value.pending?.id == callId } }
            ?.let { it.key to it.value } ?: return JSONObject().put("ok", false).put("error", "no action is waiting")
        synchronized(loop) {
            loop.decide(callId, if (allow) AgentLoop.Decision.ALLOW else AgentLoop.Decision.DENY)
            return step(session, loop)
        }
    }

    private fun step(session: String, loop: AgentLoop): JSONObject {
        val out = loop.step(model = ::callModel, run = { c ->
            if (c.name == "summarize_page" && route(tools.first { it.id == c.name }) == PageSummary.ON_DEVICE)
                summarizer.summarizePage(PageSummary.ON_DEVICE).toString()
            else BrowserBus.call("agent_tool", mapOf("name" to c.name, "args" to c.args.toString()), timeoutMs = 60_000).toString().take(cfg.optInt("page_text_cap_chars", 6000) + 500)
        }, currentUrl = { BrowserBus.call("page_text", mapOf("n" to "1")).optString("url").ifBlank { null } })
        val base = JSONObject().put("session", session)
        return when (out) {
            is AgentLoop.Outcome.Answer -> base.put("ok", true).put("status", "answered").put("message", out.text)
            is AgentLoop.Outcome.Failed -> base.put("ok", false).put("status", "failed").put("error", out.error)
            is AgentLoop.Outcome.Pending -> {
                BrowserBus.call("agent_confirm", mapOf("call" to out.call.id, "sentence" to out.sentence))
                base.put("ok", true).put("status", "pending_confirmation").put("tool", out.call.name).put("confirm", out.sentence)
            }
        }
    }

    private fun callModel(messages: JSONArray): AgentLoop.ModelTurn {
        val r = backend.chat(AiRequest(model, emptyList(), tools = specs, wire = messages, stream = false)) {}
        if (r.error != null) return AgentLoop.ModelTurn(null, emptyList(), r.error)
        return AgentLoop.ModelTurn(r.text, r.toolCalls.map { AgentLoop.Call(it.id, it.name, AgentLoop.args(it.arguments)) })
    }

    companion object {
        @Volatile private var instance: AgentRunner? = null
        private const val SCREEN = "screen"

        fun get(app: Context, search: SearchAddon, addon: BrowserAddon): AgentRunner = instance ?: synchronized(this) {
            instance ?: AgentRunner(app.applicationContext, search, addon).also { r ->
                instance = r
                // The screen's side panel: its messages, its consent card, Summarize and New chat.
                BrowserAgentHost.ask = { text -> r.ask(SCREEN, text) }
                BrowserAgentHost.summarize = { r.summarizer.summarizePage(r.summarizeRoute()) }
                BrowserAgentHost.decide = { id, allow -> r.decide(id, allow) }
                BrowserAgentHost.reset = { r.reset(SCREEN) }
            }
        }
    }
}

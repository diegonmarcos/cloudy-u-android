package com.diegonmarcos.superapp.ai

import com.diegonmarcos.superapp.modelcatalogue.AgentCatalogue
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * What the AI page needs from the app it runs in. The page knows no network, no store and no token: the
 * host sends (reading its token per call), keeps the sessions, lists the models and opens a URL. [send]
 * and [models] block: the page calls them off the main thread. Most apps use [AiStandardHost].
 */
interface AiHost {
    /** The model and Web switch the user last picked. */
    var model: String
    var web: Boolean
    fun newSession(model: String, now: Long): AiSession
    fun models(): List<AiModel>
    /** One send: [sink] gets the answer's pieces as they stream and the consent question of a mutating tool. */
    fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome
    fun openUrl(url: String) {}
    /** The Agent picker's choices; empty (the default) hides it, as in a host with no agent gateway. */
    fun agents(): List<AiAgent> = emptyList()
    /** The agent the user last picked ("" = none); [send] routes through it. */
    fun agent(): String = ""
    fun pickAgent(id: String) {}
    /** The stored sessions, newest first (the page's Sessions list). */
    fun sessions(): List<AiSession> = emptyList()
    /** Renames / deletes a stored session (the sessions drawer); a host with no store ignores both. */
    fun renameSession(id: String, title: String) {}
    fun deleteSession(id: String) {}
    /** The composer's Permission Mode (a design mode id) and MCP selection: a host that sends them keeps them for the next [send]. */
    fun pickMode(id: String) {}
    fun pickMcps(names: Set<String>) {}
    /** The composer's Effort (`reasoning.effort` value, "" = none) and the optimisation plugins' request headers. */
    fun pickEffort(value: String) {}
    fun pickPlugins(headers: Map<String, String>) {}
    /** The gateway's live plugins (name to enabled), blocking; empty = it lists none. */
    fun plugins(): Map<String, Boolean> = emptyMap()
    /** Tokens and cost the backend REPORTED, for the open session and the day; empty = nothing reported yet. */
    fun usage(sessionId: String): List<AiUsageLine> = emptyList()
    /** The same with the files attached to the message (the (+) menu); a host that takes none ignores them. */
    fun sendWith(session: AiSession, text: String, attachments: List<AiAttachment>, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome =
        send(session, text, model, web, now, sink)
    /** The standing system prompt in force ("" = none). */
    fun systemPrompt(): String = ""
    /** The MCP servers the MCPs control lists, blocking (the page calls it off the main thread); empty = the control shows none. */
    fun mcpServers(): List<AiMcpServer> = emptyList()
}

/**
 * The road for a message that carries an image or asks for one: OpenRouter's chat completions with the user's own key ([backend], the key read
 * per send from the private store, never kept here). [flow] picks the model (image out, vision, text); [store] keeps the images an answer returns.
 */
class AiMedia(val backend: AiBackend, val flow: AiImageFlow, val store: AiImageStore, val modalities: List<String>)

/** The small choices a host remembers. */
interface AiPrefs {
    var model: String
    var web: Boolean
    var agent: String
}

/** [AiPrefs] kept in memory (Cloud Browser, Cloud Drive); an app with a store of its own maps [AiPrefs] onto it. */
class AiMemoryPrefs(model: String, web: Boolean = false, agent: String = "") : AiPrefs {
    @Volatile override var model: String = model
    @Volatile override var web: Boolean = web
    @Volatile override var agent: String = agent
}

/** The agents of the shared descriptor (libs:model-catalogue agents.json) as the picker lists them. */
object AiAgents {
    fun from(cat: AgentCatalogue.Catalogue): List<AiAgent> = cat.agents.map { a ->
        AiAgent(a.id, a.label, AgentCatalogue.subtitle(a), a.mode) { picked -> AgentCatalogue.modelFor(a, picked) }
    }

    /** The shared descriptor from the app's merged assets; empty when the app does not carry it. */
    fun load(ctx: android.content.Context): List<AiAgent> = runCatching {
        val shared = from(AgentCatalogue.parse(ctx.assets.open(AgentCatalogue.ASSET).bufferedReader().use { it.readText() }))
        // The gateway modes the descriptor lacks (openrouter: any model; the task mapping runs on it), from the shared design.
        shared + AiDesign.load(ctx).extraAgents.filter { x -> shared.none { it.id == x.id } }
    }.getOrDefault(emptyList())
}

/**
 * The standard host: sessions in a store, the model and agent in prefs, and pluggable backends. With an agent
 * picked (and a [gateway]) the send goes through the gateway with that agent's mode; otherwise through [direct]
 * (OpenRouter, or an app's own backend). Tools, when given, are offered to the model and run in a bounded loop;
 * a mutating one waits for the consent card.
 */
class AiStandardHost(
    private val cfg: AiConfig,
    private val store: AiSessionStore,
    private val prefs: AiPrefs,
    private val direct: AiBackend? = null,
    private val gateway: AiBackend? = null,
    private val agents: List<AiAgent> = emptyList(),
    /** The model list when the app has a better one than the backend's (cached, offline-aware). */
    private val modelSource: (() -> List<AiModel>)? = null,
    private val tools: List<AiTool> = emptyList(),
    private val opener: (String) -> Unit = {},
    /** Words for the gateway agent's failure ("Hermes (fleet agent gateway, mesh only): ..."). */
    private val gatewayNote: String = "fleet agent gateway, mesh only",
    /** A standing system prompt (a persona chat): sent first on every request, never stored in the session. */
    private val system: String = "",
    /** The shared design: gives the composer's Permission Modes their sentence. Null = no mode is sent. */
    private val design: AiDesign? = null,
    /** An app's own usage lines (Cloud Tools' Costs ledger); replaces the reported-usage tally when set. */
    private val usageSource: ((String) -> List<AiUsageLine>)? = null,
    /** Image in / image out and vision: the OpenRouter road for a message with an image or an ask for one. Null = every message goes the usual way. */
    private val media: AiMedia? = null,
    /** A file's bytes for the wire (null = unreadable). */
    private val files: (AiAttachment) -> ByteArray? = { a -> runCatching { a.file.readBytes() }.getOrNull() },
) : AiHost {
    @Volatile private var effortValue: String = ""
    @Volatile private var headers: Map<String, String> = emptyMap()
    private class Used(val at: Long, val session: String, val u: AiUsage)
    private val used = java.util.concurrent.CopyOnWriteArrayList<Used>()
    @Volatile private var modeId: String = ""
    @Volatile private var mcpOn: Set<String> = emptySet()

    override var model: String
        get() = prefs.model
        set(v) { prefs.model = v }
    override var web: Boolean
        get() = prefs.web
        set(v) { prefs.web = v }

    override fun newSession(model: String, now: Long) = AiSession(UUID.randomUUID().toString(), "", model, now, emptyList())

    override fun models(): List<AiModel> = modelSource?.invoke() ?: (direct ?: gateway)?.models().orEmpty()

    override fun openUrl(url: String) = opener(url)
    override fun agents(): List<AiAgent> = if (gateway == null) emptyList() else agents
    override fun agent(): String = prefs.agent
    override fun pickAgent(id: String) { prefs.agent = id }
    override fun sessions(): List<AiSession> = store.all()
    override fun renameSession(id: String, title: String) = store.rename(id, title)
    override fun deleteSession(id: String) = store.delete(id)
    override fun pickMode(id: String) { modeId = id }
    override fun pickMcps(names: Set<String>) { mcpOn = names }
    override fun pickEffort(value: String) { effortValue = value }
    override fun pickPlugins(headers: Map<String, String>) { this.headers = headers }
    override fun plugins(): Map<String, Boolean> = gateway?.plugins().orEmpty()
    override fun systemPrompt(): String = standing()
    override fun usage(sessionId: String): List<AiUsageLine> {
        usageSource?.let { return it(sessionId) }
        if (used.isEmpty()) return emptyList()
        fun line(label: String, us: List<AiUsage>): AiUsageLine {
            val cost = us.mapNotNull { it.cost }.takeIf { it.isNotEmpty() }?.sum()
            return AiUsageLine(label, "${us.sumOf { it.prompt }} in · ${us.sumOf { it.completion }} out" + (cost?.let { " · \$" + "%.4f".format(java.util.Locale.ROOT, it) } ?: ""))
        }
        val day = java.time.LocalDate.now()
        val today = used.filter { java.time.Instant.ofEpochMilli(it.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == day }
        return listOf(line("This session", used.filter { it.session == sessionId }.map { it.u }), line("Today", today.map { it.u }))
    }
    override fun mcpServers(): List<AiMcpServer> = if (gateway == null) emptyList() else gateway.mcpServers()

    /** The standing system text of a send: the persona's, then the Permission Mode's sentence, then the chosen MCP servers'. */
    internal fun standing(): String = listOf(system, design?.mode(modeId)?.instruction.orEmpty(), design?.mcpSentence(mcpOn).orEmpty())
        .filter { it.isNotBlank() }.joinToString("\n\n")

    override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome =
        sendWith(session, text, emptyList(), model, web, now, sink)

    override fun sendWith(session: AiSession, text: String, attachments: List<AiAttachment>, model: String, web: Boolean, now: Long, sink: AiSink): AiOutcome {
        val asked = session.copy(
            title = session.title.ifBlank { AiProtocol.title(text.ifBlank { attachments.firstOrNull()?.name.orEmpty() }, cfg.titleChars) },
            model = model, updated = now, messages = session.messages + AiMsg("user", text, attachments = attachments, at = now),
        )
        store.put(asked)
        // An image in the message or an ask for one goes the media road (OpenRouter, the user's key) with the model the flow picks.
        val m = media
        val plan = if (m != null) m.flow.plan(text, attachments.any { it.image }, model) else null
        val route = if (m != null && plan != null && plan.kind != AiImageFlow.Kind.TEXT) m to plan else null
        val sendModel = route?.second?.model ?: model
        val drawing = route?.second?.kind == AiImageFlow.Kind.IMAGE
        // With no direct backend the gateway is the only road: an app that picked no agent gets the first one.
        val agent = if (route != null) null else if (gateway != null) agents.firstOrNull { it.id == prefs.agent } ?: (if (direct == null) agents.firstOrNull() else null) else null
        val backend = if (route != null) route.first.backend else if (agent != null) gateway else direct
        if (backend == null) return AiOutcome(asked, "no AI backend is configured", emptyList())
        val via = agent?.let { "${it.label} ($gatewayNote)" }
        val nativeWeb = agent == null && route == null && web && runCatching { models().firstOrNull { it.id == model }?.nativeWeb }.getOrNull() == true
        val standing = standing()
        val wire = if (standing.isBlank() && attachments.isEmpty()) null else JSONArray().also { a ->
            if (standing.isNotBlank()) a.put(JSONObject().put("role", "system").put("content", standing))
            val h = AiProtocol.messages(asked.messages, cfg.historyTurns, files)
            for (i in 0 until h.length()) a.put(h.get(i))
        }
        val request = AiRequest(
            sendModel, asked.messages, web && route == null, nativeWeb, agent, if (route == null) tools else emptyList(), wire, stream = !drawing,
            effort = effortValue, headers = if (agent != null) headers else emptyMap(), modalities = if (drawing && route != null) route.first.modalities else emptyList(),
        )
        val reply = converse(backend, request, sink, route == null)
        reply.usage?.let { used.add(Used(now, session.id, it)) }
        val answer = reply.text
        val saved = reply.images.mapNotNull { u -> media?.let { runCatching { it.store.save(u) }.getOrNull() } }
        if (answer == null || (answer.isEmpty() && reply.error == null && saved.isEmpty())) {
            val why = reply.error ?: "no choices in the answer"
            return AiOutcome(asked, if (reply.transport) listOfNotNull(via, why).joinToString(": ") else why, emptyList())
        }
        val meta = listOfNotNull(agent?.label, sendModel.takeIf { x -> x.isNotBlank() && (agent == null || agent.modelFor(x) != null) }).joinToString(" · ")
        val answered = asked.copy(
            messages = asked.messages + AiMsg("assistant", answer, meta, reply.usage?.prompt ?: 0, reply.usage?.completion ?: 0, saved, at = System.currentTimeMillis()),
            updated = System.currentTimeMillis(),
        )
        store.put(answered)
        return AiOutcome(answered, null, reply.citations)
    }

    /** One model call, or the bounded tool loop when tools are offered. */
    private fun converse(backend: AiBackend, req: AiRequest, sink: AiSink, withTools: Boolean = true): AiReply {
        if (tools.isEmpty() || !withTools) return backend.chat(req, sink::delta)
        val wire = req.wire ?: AiProtocol.messages(req.messages, cfg.historyTurns)
        var last = AiReply(null, error = "no answer")
        repeat(cfg.maxToolRounds) {
            last = backend.chat(AiRequest(req.model, req.messages, req.web, req.nativeWeb, req.agent, tools, wire, false, req.effort, req.headers), sink::delta)
            if (last.error != null || last.toolCalls.isEmpty()) return last
            wire.put(JSONObject().put("role", "assistant").put("content", last.text ?: "").put("tool_calls", org.json.JSONArray(last.toolCalls.map { c ->
                JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))
            })))
            for (c in last.toolCalls) wire.put(JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", runTool(c, sink)))
        }
        return AiReply(null, error = "the tool loop did not finish in ${cfg.maxToolRounds} rounds")
    }

    private fun runTool(c: AiToolCall, sink: AiSink): String {
        val tool = tools.firstOrNull { it.name == c.name } ?: return "unknown tool: ${c.name}"
        val args = runCatching { JSONObject(c.arguments) }.getOrDefault(JSONObject())
        if (tool.mutating && !sink.consent(AiConsentRequest(c.id, tool.name, tool.sentence(args)))) return "denied: the user did not allow ${tool.name}"
        return runCatching { tool.run(args) }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
    }
}

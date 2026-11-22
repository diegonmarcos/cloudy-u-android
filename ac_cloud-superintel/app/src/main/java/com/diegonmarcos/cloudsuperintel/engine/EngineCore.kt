package com.diegonmarcos.cloudsuperintel.engine

import com.diegonmarcos.cloudsuperintel.OrModel
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.aiengine.AiEngine
import com.diegonmarcos.superapp.modelcatalogue.AgentCatalogue
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1855 P2 the pure side of the AI engine SuperIntel serves over libs:ai-engine: the request mapping, the provider and model
 * rows, the run of one chat. No Android, no network: [EngineService] gathers the live inputs (gateway health, the key, the
 * OpenRouter catalogue) and hands them in, so every rule here is JVM-tested. No key is read, kept or returned by anything here.
 */
object EngineRequest {
    /** One accepted chat request. [model] null = the backend's own; [wire] is the OpenAI-style message array as sent. */
    data class Parsed(
        val id: String, val provider: String, val mode: String, val model: String?, val wire: JSONArray,
        val effort: String, val headers: Map<String, String>,
    )

    /** Headers a client may NOT set: SuperIntel adds the credentials itself and never takes them from a caller. */
    private val FORBIDDEN = setOf("authorization", "proxy-authorization", "cookie", "host", "content-length", "x-api-key")

    const val MAX_ID = 100

    /** The request as the contract documents it (IAiEngine.aidl), or the reason it is refused. Never throws. */
    fun parse(json: String?): Pair<Parsed?, String> {
        val o = runCatching { JSONObject(json.orEmpty()) }.getOrNull() ?: return null to "the request is not JSON"
        val id = o.optString("id").trim()
        if (id.isEmpty() || id.length > MAX_ID) return null to "the request has no id"
        val provider = o.optString("provider").trim()
        if (provider !in EngineProviders.IDS) return null to "unknown provider '$provider'"
        val msgs = o.optJSONArray("messages")
        if (msgs == null || msgs.length() == 0) return null to "the request has no messages"
        for (i in 0 until msgs.length()) {
            val m = msgs.optJSONObject(i) ?: return null to "message $i is not an object"
            if (m.optString("role").isBlank()) return null to "message $i has no role"
        }
        val hdr = LinkedHashMap<String, String>()
        o.optJSONObject("headers")?.let { h ->
            h.keys().forEach { k -> if (k.lowercase() !in FORBIDDEN && k.none { it.code < 0x21 || it == ':' }) hdr[k] = h.optString(k) }
        }
        return Parsed(id, provider, o.optString("mode").trim(), o.optString("model").trim().ifEmpty { null }, msgs,
            o.optString("effort").trim(), if (provider == EngineProviders.OPENROUTER) emptyMap() else hdr) to ""
    }

    /**
     * The libs:ai request for [p]. A gateway provider carries its mode (the X-Agent-Mode header) as an agent whose model rule is
     * the identity (the caller already named the model as the backend takes it); claude-cli is the gateway with the Claude mode;
     * OpenRouter carries no agent. [claudeMode] is the catalogue's mode of the Claude agent.
     */
    fun toAi(p: Parsed, claudeMode: String = EngineProviders.CLAUDE_MODE_DEFAULT): AiRequest {
        val mode = if (p.provider == EngineProviders.CLAUDE_CLI) claudeMode else p.mode
        val agent = if (p.provider == EngineProviders.OPENROUTER || mode.isEmpty()) null else AiAgent(mode, mode, "", mode) { it }
        return AiRequest(p.model, emptyList(), agent = agent, wire = p.wire, stream = true, effort = p.effort, headers = p.headers)
    }
}

/** The gateway's live state as `/health` describes it: [up] null = not asked / unknown; [modes] its served modes. */
data class GatewayState(val up: Boolean?, val modes: List<Mode> = emptyList()) {
    data class Mode(val id: String, val label: String, val model: String, val available: Boolean)

    fun mode(id: String): Mode? = modes.firstOrNull { it.id == id }

    companion object {
        val UNKNOWN = GatewayState(null)
        val DOWN = GatewayState(false)

        /** A 2xx /health body; an unparsable body is still "up" (the gateway answered), with no modes listed. */
        fun parse(body: String): GatewayState {
            val a = runCatching { JSONObject(body).optJSONArray("modes") }.getOrNull() ?: return GatewayState(true)
            val modes = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { m ->
                val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Mode(id, m.optString("label").ifBlank { id }, m.optString("model"), m.optBoolean("available", true))
            }
            return GatewayState(true, modes)
        }
    }
}

object EngineProviders {
    const val GATEWAY = AiEngine.KIND_GATEWAY
    const val OPENROUTER = AiEngine.KIND_OPENROUTER
    const val CLAUDE_CLI = AiEngine.KIND_CLAUDE_CLI
    const val CLAUDE_MODE_DEFAULT = "claude-cli"

    /** The provider ids this engine runs. The `local` kind of the contract is not served yet: no on-device backend is bound here. */
    val IDS = listOf(GATEWAY, CLAUDE_CLI, OPENROUTER)

    /** The mode of the catalogue's Claude agent (the one the gateway runs through the claude CLI). */
    fun claudeMode(cat: AgentCatalogue.Catalogue?): String =
        cat?.agents?.firstOrNull { it.id == "claude" }?.mode ?: CLAUDE_MODE_DEFAULT

    /**
     * The provider rows with their REAL state: the gateway from its /health, claude-cli from the gateway's mode list (down when the
     * gateway answers but serves no such mode), OpenRouter from the key (nokey) and from the catalogue read ([orCatalogue]: true = a
     * list is on hand, false = none could be read, null = not asked). Nothing is guessed: an unasked state is "unknown".
     * [openRouterKey] is only whether the Account holds a key, never the key.
     */
    fun rows(gw: GatewayState, cat: AgentCatalogue.Catalogue?, openRouterKey: Boolean?, orCatalogue: Boolean?, orDefault: String): List<AiEngine.ProviderInfo> {
        val claude = claudeMode(cat)
        val gwHealth = when (gw.up) { true -> AiEngine.HEALTH_UP; false -> AiEngine.HEALTH_DOWN; null -> AiEngine.HEALTH_UNKNOWN }
        val modes = gw.modes.filter { it.id != claude }
        val claudeLive = gw.mode(claude)
        val claudeHealth = when {
            gw.up == false -> AiEngine.HEALTH_DOWN
            gw.up == null -> AiEngine.HEALTH_UNKNOWN
            gw.modes.isEmpty() -> AiEngine.HEALTH_UNKNOWN
            claudeLive == null || !claudeLive.available -> AiEngine.HEALTH_DOWN
            else -> AiEngine.HEALTH_UP
        }
        val orHealth = when {
            openRouterKey == false -> AiEngine.HEALTH_NOKEY
            openRouterKey == null || orCatalogue == null -> AiEngine.HEALTH_UNKNOWN
            orCatalogue -> AiEngine.HEALTH_UP
            else -> AiEngine.HEALTH_DOWN
        }
        val claudeAgent = cat?.agents?.firstOrNull { it.id == "claude" }
        return listOf(
            AiEngine.ProviderInfo(GATEWAY, "Fleet gateway", AiEngine.KIND_GATEWAY, gwHealth, "",
                if (modes.isEmpty()) "agent modes through the fleet gateway (mesh)" else "modes: " + modes.joinToString(", ") { it.id + if (it.available) "" else " (unavailable)" }),
            AiEngine.ProviderInfo(CLAUDE_CLI, "Claude (CLI via the gateway)", AiEngine.KIND_CLAUDE_CLI, claudeHealth,
                claudeAgent?.defaultModel.orEmpty(), claudeLive?.takeIf { !it.available }?.let { "the gateway reports it unavailable" } ?: "the subscription claude CLI behind the gateway"),
            AiEngine.ProviderInfo(OPENROUTER, "OpenRouter", AiEngine.KIND_OPENROUTER, orHealth, orDefault,
                if (openRouterKey == false) "no OpenRouter key in the Cloud Account" else "OpenRouter with the key held by Cloud Account"),
        )
    }
}

object EngineModels {
    private fun per(s: String?) = s.orEmpty()

    /** OpenRouter's catalogue as model rows (id, name, "openrouter", context, the raw per-token prices, modality). */
    fun openRouter(models: List<OrModel>): List<AiEngine.ModelInfo> = models.map {
        AiEngine.ModelInfo(it.id, it.name, "openrouter", it.contextLength, per(it.pricePrompt), per(it.priceCompletion), it.modality)
    }

    /** The Anthropic rows of the catalogue the claude CLI can run (its agent's id prefix), grouped "anthropic". */
    fun claude(models: List<OrModel>, cat: AgentCatalogue.Catalogue?): List<AiEngine.ModelInfo> {
        val prefix = cat?.agents?.firstOrNull { it.id == "claude" }?.prefix ?: "anthropic/"
        return models.filter { it.id.startsWith(prefix) }.map {
            AiEngine.ModelInfo(it.id, it.name, "anthropic", it.contextLength, per(it.pricePrompt), per(it.priceCompletion), it.modality)
        }
    }

    /**
     * The gateway's modes (libs:ai agents.json merged with the live /health.modes; a live mode the catalogue lacks is listed too) as
     * rows grouped "mode" (the id is the value a chat request names as `mode`), then the models the gateway lists, grouped "model".
     */
    fun gateway(cat: AgentCatalogue.Catalogue?, gw: GatewayState, listed: List<AiModel>): List<AiEngine.ModelInfo> {
        val claude = EngineProviders.claudeMode(cat)
        val declared = cat?.agents.orEmpty().filter { it.mode != claude }
        val modes = declared.map { a ->
            val live = gw.mode(a.mode)
            AiEngine.ModelInfo(a.mode, a.label, "mode", 0, "", "",
                when { gw.up == null || gw.modes.isEmpty() -> a.backend; live == null -> "not served now"; !live.available -> "unavailable"; else -> live.model.ifBlank { a.backend } })
        } + gw.modes.filter { m -> m.id != claude && declared.none { it.mode == m.id } }.map {
            AiEngine.ModelInfo(it.id, it.label, "mode", 0, "", "", if (it.available) it.model else "unavailable")
        }
        return modes + listed.map { AiEngine.ModelInfo(it.id, it.name, "model", it.context, "", "", if (it.free) "free" else "") }
    }
}

/** The run of one chat: the backend's answer to the caller's listener, with cancel and metering. */
object EngineChat {
    private class Cancelled : RuntimeException("cancelled")

    /** Tokens the backend REPORTED, as the JSON onDone carries ("" when it reported none). */
    fun usageJson(prompt: Long, completion: Long, cost: Double?): String =
        JSONObject().put("prompt", prompt).put("completion", completion).also { if (cost != null) it.put("cost", cost) }.toString()

    /**
     * Blocks until the answer ends. Exactly one of onDone / onError reaches [out]. [cancelled] is polled on every piece (a streaming
     * backend stops at the next one) and once more at the end (a whole answer is dropped). A reported usage goes to [meter].
     */
    fun run(p: EngineRequest.Parsed, ai: AiRequest, backend: AiBackend, cancelled: () -> Boolean, out: AiEngine.Listener,
            meter: (prompt: Long, completion: Long, cost: Double?) -> Unit) {
        var streamed = false
        val reply = try {
            backend.chat(ai) { d ->
                if (cancelled()) throw Cancelled()
                streamed = true
                out.onChunk(d)
            }
        } catch (e: Exception) {
            out.onError(if (cancelled()) AiEngine.CANCELLED else e.message ?: e.javaClass.simpleName)
            return
        }
        if (cancelled()) return out.onError(AiEngine.CANCELLED)
        reply.error?.let { return out.onError(it) }
        val text = reply.text
        if (text.isNullOrEmpty() && !streamed) return out.onError("no answer")
        if (!streamed && !text.isNullOrEmpty()) out.onChunk(text)
        val u = reply.usage
        if (u != null) meter(u.prompt, u.completion, u.cost)
        out.onDone(if (u == null) "" else usageJson(u.prompt, u.completion, u.cost))
    }
}

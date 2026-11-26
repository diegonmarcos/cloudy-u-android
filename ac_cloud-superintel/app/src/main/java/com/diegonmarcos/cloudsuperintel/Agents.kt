package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.ai.AiAgent
import com.diegonmarcos.superapp.ai.AiAgents
import com.diegonmarcos.superapp.ai.AiConfig
import com.diegonmarcos.superapp.ai.AiUrlHttp
import org.json.JSONObject

/** One agent as assets/agents/agents.json declares it: the shared descriptor fields plus what the Agents page shows (#1939). */
data class AgentDecl(
    val id: String, val label: String, val icon: String, val what: String, val runsOn: String, val models: String,
    val healthKey: String, val gateway: Boolean,
)

/** An agent's live state: from the gateway's /health, never from the declaration. */
enum class AgentState { CHECKING, REACHABLE, DOWN, NOT_DEPLOYED }

/** [model] is the model name the gateway reports for the agent when it reports one. */
data class AgentLive(val state: AgentState, val model: String = "")

/**
 * The Agents page and the composer's Agent picker share ONE declared list (assets/agents/agents.json). The live status is layered
 * on top (the gateway's /health `agents` object) and never decides what is listed: a gateway that does not answer leaves every
 * gateway agent listed as down, never an empty page.
 */
object Agents {
    const val ASSET = "agents/agents.json"

    fun parse(text: String): List<AgentDecl> {
        val a = JSONObject(text).getJSONArray("agents")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            AgentDecl(
                o.getString("id"), o.getString("label"), o.optString("icon", o.getString("label").take(1)), o.optString("what"), o.optString("where"),
                o.optString("models"), o.optString("health_key", o.getString("id")), o.optBoolean("gateway", true),
            )
        }
    }

    fun declared(ctx: Context): List<AgentDecl> = runCatching { parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    /** Every declared agent, then every agent the composer offers that the declaration does not name (the design's extra gateway modes), so none is dropped. */
    fun rows(declared: List<AgentDecl>, offered: List<AiAgent>): List<AgentDecl> =
        declared + offered.filter { o -> declared.none { it.id == o.id } }.map { AgentDecl(it.id, it.label, it.label.take(1), it.sub, "Fleet gateway my-ai-api (mesh only)", "", it.id, true) }

    /** The agents the composer's picker offers: the shared list without the declared agents that are not a gateway mode. */
    fun composer(all: List<AiAgent>, declared: List<AgentDecl>): List<AiAgent> =
        all.filter { a -> declared.firstOrNull { it.id == a.id }?.gateway != false }

    fun composer(ctx: Context): List<AiAgent> = composer(AiAgents.load(ctx), declared(ctx))

    /** The `agents` object of the gateway's /health answer, or null when the answer is not one. */
    fun healthAgents(body: String?): JSONObject? = body?.let { runCatching { JSONObject(it).optJSONObject("agents") }.getOrNull() }

    /**
     * The live state of [d]: no health answer at all = the gateway is unreachable, so every gateway agent is down; a declared non-gateway agent
     * is not deployed; a key the gateway lists as on (true or a model name) is reachable, as off (false, empty) is down, and absent is not deployed.
     */
    fun state(d: AgentDecl, health: JSONObject?): AgentLive {
        if (!d.gateway) return AgentLive(AgentState.NOT_DEPLOYED)
        if (health == null) return AgentLive(AgentState.DOWN)
        if (!health.has(d.healthKey) || health.isNull(d.healthKey)) return AgentLive(AgentState.NOT_DEPLOYED)
        return when (val v = health.get(d.healthKey)) {
            is Boolean -> AgentLive(if (v) AgentState.REACHABLE else AgentState.DOWN)
            is String -> if (v.isNotBlank()) AgentLive(AgentState.REACHABLE, v) else AgentLive(AgentState.DOWN)
            else -> AgentLive(AgentState.REACHABLE)
        }
    }

    /** The gateway's /health body, blocking; null when it does not answer (mesh down, edge down). */
    fun fetchHealth(ctx: Context): String? = runCatching {
        val cfg = AiConfig.load(ctx)
        val r = AiUrlHttp.get(BuildConfig.AI_GATEWAY_URL.trimEnd('/') + cfg.gatewayHealthPath, emptyMap(), HEALTH_TIMEOUT_MS)
        if (r.code in 200..299) r.body else null
    }.getOrNull()

    private const val HEALTH_TIMEOUT_MS = 5000
}

/** One API account as assets/ai/api-accounts.json declares it: names and references only, never a secret. */
data class ApiAccount(
    val id: String, val label: String, val provider: String, val auth: String, val keyRef: String, val usedBy: String, val statusKey: String, val cost: Boolean,
)

object ApiAccounts {
    const val ASSET = "ai/api-accounts.json"

    fun parse(text: String): List<ApiAccount> {
        val a = JSONObject(text).getJSONArray("accounts")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            ApiAccount(o.getString("id"), o.getString("label"), o.optString("provider"), o.optString("auth"), o.optString("key_ref"), o.optString("used_by"), o.optString("status_key"), o.optBoolean("cost"))
        }
    }

    fun declared(ctx: Context): List<ApiAccount> = runCatching { parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    /** Live status of an account: behind the gateway it follows the gateway's /health key; with no key it is not deployed. */
    fun state(a: ApiAccount, health: JSONObject?): AgentLive =
        if (a.statusKey.isEmpty()) AgentLive(AgentState.NOT_DEPLOYED)
        else Agents.state(AgentDecl(a.id, a.label, "", "", "", "", a.statusKey, true), health)
}

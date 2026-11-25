package com.diegonmarcos.superapp.ai

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONObject

/**
 * The shared contract (the served fleet/ai dataset) as the lib reads it: the gateway's paths and mode header,
 * OpenRouter's endpoints and request parameters, the protocol knobs. One file, read by every Kotlin
 * app through this class and by Cloud Code (JS) through tools/resolve-targets.py, so no consumer
 * restates an endpoint, a path or a header name.
 */
data class AiConfig(
    val gatewayService: String,
    val gatewayChatPath: String,
    val gatewayHealthPath: String,
    val gatewayModelsPath: String,
    val gatewayMcpPath: String,
    val modeHeader: String,
    val openRouterId: String,
    val openRouterLabel: String,
    val chatUrl: String,
    val modelsUrl: String,
    val accountProvider: String,
    val defaultModel: String,
    val referer: String,
    val timeoutMs: Int,
    val historyTurns: Int,
    val webPlugin: String,
    val nativeWebParam: String,
    val titleChars: Int,
    val maxToolRounds: Int,
    val stream: Boolean,
    /** #1566 false = the fleet-private contract is not served (yet): [NOT_SERVED], whose endpoints are empty so no call goes anywhere. */
    val served: Boolean = true,
) {
    companion object {
        /** #1566 the contract is a fleet-private served dataset (Cloud Account's private route), never an APK asset. */
        const val DATASET = "fleet/ai"

        /** The minimal safe default while nothing is served: empty endpoints (a call fails and says so), the protocol defaults, no gateway. */
        val NOT_SERVED = AiConfig(
            gatewayService = "", gatewayChatPath = "", gatewayHealthPath = "/health", gatewayModelsPath = "/v1/models", gatewayMcpPath = "/v1/mcp/status",
            modeHeader = "X-Agent-Mode", openRouterId = "openrouter", openRouterLabel = "OpenRouter", chatUrl = "", modelsUrl = "",
            accountProvider = "openrouter", defaultModel = "", referer = "", timeoutMs = 60000, historyTurns = 20, webPlugin = "web",
            nativeWebParam = "web_search_options", titleChars = 40, maxToolRounds = 6, stream = true, served = false,
        )

        @Volatile private var lastServed: AiConfig? = null

        fun parse(text: String): AiConfig {
            val root = JSONObject(text)
            val g = root.getJSONObject("gateway")
            val o = root.getJSONObject("openrouter")
            val p = root.getJSONObject("protocol")
            return AiConfig(
                gatewayService = g.getString("service"),
                gatewayChatPath = g.getString("chat_path"),
                gatewayHealthPath = g.optString("health_path", "/health"),
                gatewayModelsPath = g.optString("models_path", "/v1/models"),
                gatewayMcpPath = g.optString("mcp_path", "/v1/mcp/status"),
                modeHeader = g.optString("mode_header", "X-Agent-Mode"),
                openRouterId = o.getString("id"),
                openRouterLabel = o.getString("label"),
                chatUrl = o.getString("chat_url"),
                modelsUrl = o.getString("models_url"),
                accountProvider = o.getString("account_provider"),
                defaultModel = o.getString("default_model"),
                referer = o.optString("referer"),
                timeoutMs = p.optInt("timeout_ms", 60000),
                historyTurns = p.optInt("history_turns", 20),
                webPlugin = o.optString("web_plugin", "web"),
                nativeWebParam = o.optString("native_web_param", "web_search_options"),
                titleChars = p.optInt("title_chars", 40),
                maxToolRounds = p.optInt("max_tool_rounds", 6),
                stream = p.optBoolean("stream", true),
            )
        }

        /** The served contract (last-known-good while the provider is away); null when nothing was ever served or the copy does not parse. */
        fun loadOrNull(ctx: Context): AiConfig? {
            lastServed?.let { return it }
            return runCatching { FleetData.get(ctx.applicationContext, DATASET) { parse(it) } }.getOrNull()?.also { lastServed = it }
        }

        /** The contract, or [NOT_SERVED] (never null, so no call site crashes): a served copy is held for the process, not served is retried on every call. */
        fun load(ctx: Context): AiConfig = loadOrNull(ctx) ?: NOT_SERVED
    }
}

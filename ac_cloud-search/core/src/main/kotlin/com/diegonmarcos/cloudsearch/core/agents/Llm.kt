package com.diegonmarcos.cloudsearch.core.agents

import com.diegonmarcos.cloudsearch.core.Chat
import com.diegonmarcos.cloudsearch.core.Http
import com.diegonmarcos.cloudsearch.core.SearchConfig
import org.json.JSONArray
import org.json.JSONObject

/** One completion with no tools: a system prompt, one user message, a hard cap on the answer. */
data class LlmRequest(val model: String, val system: String, val user: String, val maxTokens: Int)

data class LlmReply(
    val text: String?, val error: String?, val promptTokens: Int = 0, val completionTokens: Int = 0, val costUsd: Double? = null,
)

interface Llm {
    fun complete(req: LlmRequest): LlmReply
}

/**
 * OpenRouter's chat completions, the way the chat page speaks it, with NO `tools`: a model can only answer in
 * text, it cannot act. The token comes from [token] for each call (the fleet Account's, read per use) and goes
 * only into the Authorization header; it is in no body, no error text and no return value of this class.
 */
class OpenRouterLlm(private val ai: SearchConfig.Ai, private val http: Http, private val token: () -> String?) : Llm {
    override fun complete(req: LlmRequest): LlmReply {
        val t = token() ?: return LlmReply(null, "no ${ai.accountProvider} token in the fleet Account")
        val res = runCatching { http.post(ai.chatUrl, Chat.headers(ai, t), body(req), ai.timeoutMs) }
        val r = res.getOrNull() ?: return LlmReply(null, "request failed: ${res.exceptionOrNull()?.javaClass?.simpleName ?: "unknown"}")
        return parse(r.code, r.body).let { it.copy(error = it.error?.replace(t, "***")) }
    }

    companion object {
        /** The chat-completions body of [req]: no tools, the answer capped, the run's cost asked for. */
        fun body(req: LlmRequest): String = JSONObject().put("model", req.model)
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", req.system))
                .put(JSONObject().put("role", "user").put("content", req.user)))
            .put("max_tokens", req.maxTokens)
            .put("usage", JSONObject().put("include", true))
            .toString()

        /** One answer as the agents read it: the text and the usage, or the provider's error (else the HTTP code). */
        fun parse(code: Int, body: String): LlmReply {
            val reply = Chat.reply(body)
            val text = reply.text ?: return LlmReply(null, reply.error ?: "HTTP $code")
            val usage = runCatching { JSONObject(body).optJSONObject("usage") }.getOrNull()
            return LlmReply(
                text = text, error = null,
                promptTokens = usage?.optInt("prompt_tokens") ?: 0,
                completionTokens = usage?.optInt("completion_tokens") ?: 0,
                costUsd = usage?.takeIf { it.has("cost") && !it.isNull("cost") }?.optDouble("cost"),
            )
        }
    }
}

/**
 * #1801 the same completion with NO KEY ON THIS SIDE: [relay] hands [OpenRouterLlm.body] to the fleet's AI-key owner engine
 * (the app's DecisionsLink.relay), which adds the key in its own process and returns the provider's code and body; the
 * answer is read by [OpenRouterLlm.parse], so a relayed run reads exactly like a direct one. A refusal (no engine, no key,
 * ...) is the run's error, worded by the engine.
 */
class RelayLlm(private val relay: (String) -> Relayed) : Llm {
    /** One relayed POST: the provider's [code] and [body], or [refused] when the call was not made. */
    data class Relayed(val code: Int, val body: String, val refused: String? = null)

    override fun complete(req: LlmRequest): LlmReply {
        val r = runCatching { relay(OpenRouterLlm.body(req)) }.getOrElse { return LlmReply(null, "request failed: ${it.javaClass.simpleName}") }
        r.refused?.let { return LlmReply(null, it) }
        return OpenRouterLlm.parse(r.code, r.body)
    }
}

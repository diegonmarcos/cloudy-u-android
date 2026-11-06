package com.diegonmarcos.superapp.ai

import java.net.HttpURLConnection
import java.net.URL

/** The HTTP the backends use: an app hands its own (Cloud Search's fakeable one) or [AiUrlHttp]. */
interface AiHttp {
    class Response(val code: Int, val body: String)

    /** True when [post] with an `onChunk` hands the body over as it arrives (server-sent events); false = the whole answer at the end. */
    val streams: Boolean get() = false

    fun get(url: String, headers: Map<String, String>, timeoutMs: Int): Response

    /** With [onChunk] and a 2xx answer, a streaming client calls it per piece and returns an empty body. */
    fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int, onChunk: ((String) -> Unit)?): Response
}

/** HttpURLConnection, streaming (so it goes through Android's resolver, the fleet DNS menu applies). */
object AiUrlHttp : AiHttp {
    override val streams: Boolean get() = true

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): AiHttp.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            return AiHttp.Response(code, (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { c.disconnect() }
    }

    override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int, onChunk: ((String) -> Unit)?): AiHttp.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code in 200..299 && onChunk != null) {
                c.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(2048)
                    while (true) {
                        val n = r.read(buf)
                        if (n < 0) break
                        if (n > 0) onChunk(String(buf, 0, n))
                    }
                }
                return AiHttp.Response(code, "")
            }
            return AiHttp.Response(code, (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally { c.disconnect() }
    }
}

/** A token read at the moment of a send: [value] null = none, [why] says so. The lib never stores or logs it. */
data class AiToken(val value: String?, val why: String = "")

fun interface AiTokenSource { fun token(): AiToken }

/** A pluggable model backend: the gateway, OpenRouter, or an app's own (Cloud Tools Small LLMs' router). */
interface AiBackend {
    val id: String
    /** The models this backend can run, blocking (the page calls it off the main thread). Empty when unknown/offline. */
    fun models(): List<AiModel>
    /** One request. [onDelta] gets the answer's pieces when the backend streams. Blocking. */
    fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply
    /** The gateway's live optimisation plugins (`/health.plugins`: name to on/off); empty when it lists none. Blocking. */
    fun plugins(): Map<String, Boolean> = emptyMap()
    /** The MCP servers this backend can expose to a session (the gateway's); empty when it has none. Blocking. */
    fun mcpServers(): List<AiMcpServer> = emptyList()
}

/** The OpenAI-style chat-completions backends share everything but the address, the headers and the body's extras. */
abstract class AiChatBackend(protected val cfg: AiConfig, protected val http: AiHttp) : AiBackend {
    /** Where [req] goes. */
    protected abstract fun url(req: AiRequest): String
    /** The headers [req] needs; null with [why] set when it cannot be sent (no token). */
    protected abstract fun headers(req: AiRequest): Map<String, String>?
    protected open fun why(): String = ""
    /** The model the body carries (null = none). */
    protected abstract fun model(req: AiRequest): String?
    protected abstract val openRouter: Boolean

    override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply {
        val headers = headers(req) ?: return AiReply(null, error = why().ifBlank { "cannot be sent" })
        val stream = req.stream && cfg.stream && http.streams && req.tools.isEmpty() && req.modalities.isEmpty()
        val body = AiProtocol.body(cfg, req, model(req), stream, openRouter)
        return try {
            if (!stream) {
                val r = http.post(url(req), headers, body, cfg.timeoutMs, null)
                AiProtocol.parseWhole(r.code, r.body)
            } else {
                val sse = AiProtocol.Sse()
                val text = StringBuilder()
                val r = http.post(url(req), headers, body, cfg.timeoutMs) { chunk ->
                    for (d in sse.push(chunk)) { text.append(d); onDelta(d) }
                }
                if (r.code !in 200..299) AiProtocol.parseWhole(r.code, r.body)
                else if (sse.error != null) AiReply(null, error = sse.error)
                else AiReply(text.toString(), sse.citations.toList(), usage = sse.usage)
            }
        } catch (e: Exception) {
            AiReply(null, error = e.message ?: e.javaClass.simpleName, transport = true)
        }
    }
}

/**
 * The fleet agent gateway (my-ai-api, mesh only): one OpenAI-compatible endpoint, the agent chosen by the
 * mode header. No token leaves the phone for it. [baseUrl] is the gateway's mesh address (baked by the app).
 */
class AiGatewayBackend(cfg: AiConfig, private val baseUrl: String, http: AiHttp = AiUrlHttp) : AiChatBackend(cfg, http) {
    override val id: String get() = "gateway"
    override val openRouter: Boolean get() = false
    override fun url(req: AiRequest) = baseUrl.trimEnd('/') + cfg.gatewayChatPath
    override fun headers(req: AiRequest): Map<String, String> {
        val h = linkedMapOf("Content-Type" to "application/json")
        req.agent?.let { h[cfg.modeHeader] = it.mode }
        h.putAll(req.headers)
        return h
    }
    override fun model(req: AiRequest): String? = req.model?.let { m -> req.agent?.modelFor?.invoke(m) ?: if (req.agent == null) m else null }

    override fun models(): List<AiModel> = runCatching {
        val r = http.get(baseUrl.trimEnd('/') + cfg.gatewayModelsPath, emptyMap(), cfg.timeoutMs)
        if (r.code !in 200..299) emptyList() else AiProtocol.parseModels(r.body, cfg.nativeWebParam)
    }.getOrDefault(emptyList())

    override fun plugins(): Map<String, Boolean> = runCatching {
        val r = http.get(baseUrl.trimEnd('/') + cfg.gatewayHealthPath, emptyMap(), cfg.timeoutMs)
        if (r.code !in 200..299) emptyMap() else AiProtocol.parsePlugins(r.body)
    }.getOrDefault(emptyMap())

    override fun mcpServers(): List<AiMcpServer> = runCatching {
        val r = http.get(baseUrl.trimEnd('/') + cfg.gatewayMcpPath, emptyMap(), cfg.timeoutMs)
        if (r.code !in 200..299) emptyList() else AiProtocol.parseMcp(r.body)
    }.getOrDefault(emptyList())
}

/** OpenRouter with the user's own token (the fleet Account's, handed in per send by the app). [title] is the app's X-Title. */
class AiOpenRouterBackend(cfg: AiConfig, private val title: String, private val tokens: AiTokenSource, http: AiHttp = AiUrlHttp) : AiChatBackend(cfg, http) {
    override val id: String get() = cfg.openRouterId
    override val openRouter: Boolean get() = true
    private var reason = ""
    override fun why(): String = reason
    override fun url(req: AiRequest) = cfg.chatUrl
    override fun headers(req: AiRequest): Map<String, String>? {
        val t = tokens.token()
        reason = t.why
        val v = t.value ?: return null
        return mapOf("Authorization" to "Bearer $v", "HTTP-Referer" to cfg.referer, "X-Title" to title, "Content-Type" to "application/json")
    }
    override fun model(req: AiRequest): String? = req.model

    override fun models(): List<AiModel> = runCatching {
        val r = http.get(cfg.modelsUrl, emptyMap(), cfg.timeoutMs)
        if (r.code !in 200..299) emptyList() else AiProtocol.parseModels(r.body, cfg.nativeWebParam)
    }.getOrDefault(emptyList())
}

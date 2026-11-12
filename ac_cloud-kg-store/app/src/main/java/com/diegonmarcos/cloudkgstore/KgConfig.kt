package com.diegonmarcos.cloudkgstore

import org.json.JSONObject

/** One page of the kg dataset's pages: its [kind] picks the reader, the rest is what that reader is told. */
class KgPage(val id: String, o: JSONObject) {
    /** The fleet service a page rerank asks (embeddings-api) and the model it names. */
    data class Rerank(val service: String, val path: String, val model: String)

    val kind: String = o.getString("kind")
    val services: List<String> = strings(o, "services")
    val verticals: List<String> = strings(o, "verticals")
    val methods: List<String> = strings(o, "methods")
    val service: String = o.optString("service")
    val probePath: String = o.optString("probe_path")
    val gatewayRoute: String = o.optString("gateway_route")
    /** The honest sentence for "nothing is declared here" (an app with no MCP endpoint yet). */
    val missing: String = o.optString("missing")
    val rerank: Rerank? = o.optJSONObject("rerank")?.let { Rerank(it.getString("service"), it.getString("path"), it.getString("model")) }

    private fun strings(o: JSONObject, k: String): List<String> = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
}

/** The runtime dataset kg (data/fleet/app/<package>/kg.json), read through libs:fleet-data-client. Nothing here names a page, a service or a vertical: they are all in the JSON. */
class KgConfig(
    val timeoutMs: Int, val mcpPath: String, val responseChars: Int, val pages: Map<String, KgPage>, val ask: Ask,
    /** #1640 the Headless Browsers stack the Scrapped page lists (kg.json::scrapped.headless); empty when the dataset names none. */
    val headless: List<HeadlessService> = emptyList(),
    /** #1640 the repository the Me tab clones at runtime (kg.json::me); null when the dataset declares none. */
    val me: MeRepo? = null,
) {
    data class Ask(val page: String, val system: String, val suggestions: List<String>, val mcps: List<String>)

    fun page(id: String): KgPage? = pages[id]

    companion object {
        /** #1547 the runtime dataset (Cloud Store, repo data/fleet/app/com.diegonmarcos.cloudkgstore/kg.json); no asset. */
        const val DATASET = "kg"

        /** What the app shows while Cloud Store has not served [DATASET] yet: no page, no suggestion. Never cached as the real config. */
        val EMPTY = KgConfig(8000, "/mcp", 2000, emptyMap(), Ask("", "", emptyList(), emptyList()))

        const val KIND_BINARY = "binary"
        const val KIND_DEBUG = "debug"
        const val KIND_MCP = "mcp"
        const val KIND_CONSOLE = "console"
        const val KIND_CODE = "code"
        const val KIND_CGC = "cgc"
        const val KIND_SEMANTIC = "semantic"
        const val KIND_GRAPH = "graph"
        const val KIND_PHONE_APPS = "phone_apps"
        const val KIND_NEWS = "news"
        const val KIND_VERTICALS = "verticals"
        val KINDS = setOf(KIND_BINARY, KIND_DEBUG, KIND_MCP, KIND_CONSOLE, KIND_CODE, KIND_CGC, KIND_SEMANTIC, KIND_GRAPH, KIND_PHONE_APPS, KIND_NEWS, KIND_VERTICALS)

        fun parse(text: String): KgConfig {
            val o = JSONObject(text)
            val p = o.getJSONObject("pages")
            val pages = p.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { KgPage(it, p.getJSONObject(it)) }
            val a = o.getJSONObject("ask")
            val ask = Ask(
                a.getString("page"), a.getString("system"),
                a.getJSONArray("suggestions").let { s -> (0 until s.length()).map { s.getString(it) } },
                a.optJSONArray("mcps")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList(),
            )
            // An unreadable scrapped / me block is "nothing declared" for that page, never a refused dataset.
            val headless = runCatching { o.optJSONObject("scrapped")?.optJSONArray("headless")?.let { HeadlessService.parse(it.toString()) } }.getOrNull() ?: emptyList()
            val me = runCatching { o.optJSONObject("me")?.let { MeRepo.parse(it.toString()) } }.getOrNull()
            val cfg = KgConfig(o.getInt("timeout_ms"), o.getString("mcp_path"), o.getInt("response_chars"), pages, ask, headless, me)
            require(pages.values.all { it.kind in KINDS }) { "kg.json::pages names a kind that is none of $KINDS" }
            require(cfg.mcpPath.startsWith("/")) { "kg.json::mcp_path must start with /" }
            return cfg
        }
    }
}

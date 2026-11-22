package com.diegonmarcos.superapp.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1856 Scrapper tab: the fleet's headless browser (cloudy-browser-headless, headless-api v1: POST /run {steps}) and scrapper API
 * (cloudy-browser-scrapper-api: POST /scrape/crawl, POST /download/youtube) run on the CURRENT page URL. Everything is declared in
 * build.json::ui.browser.addons[scraper].fleet: `services` (ordered `bases`, mesh first; `bearer_key` names the secret the host hands
 * over, never its value) and `tools` (id, service, path, a JSON `body` with `{url}` placeholders, `show` = the step whose result is
 * the answer, `download` = the answer is a file). Pure apart from [call]/[download], which block: call them off the main thread.
 */
object FleetScrapper {
    data class Service(val id: String, val bases: List<String>, val bearerKey: String?)
    data class Tool(val id: String, val service: String, val path: String, val body: JSONObject, val show: Int?, val download: Boolean)
    data class Fleet(val services: Map<String, Service>, val tools: Map<String, Tool>)

    val EMPTY = Fleet(emptyMap(), emptyMap())

    fun parse(fleet: JSONObject?): Fleet {
        if (fleet == null) return EMPTY
        val sv = fleet.optJSONObject("services")
        val services = LinkedHashMap<String, Service>()
        sv?.keys()?.forEach { k ->
            val o = sv.optJSONObject(k) ?: return@forEach
            val b = o.optJSONArray("bases")
            val bases = (0 until (b?.length() ?: 0)).map { b!!.optString(it).trimEnd('/') }.filter { it.isNotBlank() }
            services[k] = Service(k, bases, o.optString("bearer_key").ifBlank { null })
        }
        val a = fleet.optJSONArray("tools")
        val tools = LinkedHashMap<String, Tool>()
        for (i in 0 until (a?.length() ?: 0)) {
            val o = a!!.optJSONObject(i) ?: continue
            val id = o.optString("id").ifBlank { continue }
            tools[id] = Tool(id, o.optString("service"), o.optString("path"), o.optJSONObject("body") ?: JSONObject(),
                if (o.has("show")) o.optInt("show") else null, o.optBoolean("download"))
        }
        return Fleet(services, tools)
    }

    /** [template] with every `{url}` replaced by [url], recursively (a JSON copy; the declaration is never mutated). */
    fun fill(template: Any?, url: String): Any? = when (template) {
        is JSONObject -> JSONObject().also { o -> template.keys().forEach { k -> o.put(k, fill(template.get(k), url)) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until template.length()) a.put(fill(template.get(i), url)) }
        is String -> template.replace("{url}", url)
        else -> template
    }

    /** What the sheet shows for an answer: a lone big text field as itself (markdown, text, html), else the pretty JSON; [show] picks one step of a /run answer. */
    fun display(resp: JSONObject, tool: Tool, max: Int = 60_000): String {
        val steps = resp.optJSONArray("steps")
        val failed = steps?.let { s -> (0 until s.length()).mapNotNull { s.optJSONObject(it) }.firstOrNull { !it.optBoolean("ok", true) } }
        if (failed != null) return "Failed: " + (failed.optJSONObject("error")?.optString("message") ?: failed.toString())
        if (!resp.optBoolean("ok", true)) return "Failed: " + (resp.optJSONObject("error")?.optString("message") ?: resp.optString("error").ifBlank { resp.toString() })
        val picked: Any = if (tool.show != null && steps != null) steps.optJSONObject(tool.show)?.opt("result") ?: resp else resp
        val text = if (picked is JSONObject) {
            listOf("markdown", "text", "html").firstNotNullOfOrNull { k -> picked.optString(k).takeIf { it.isNotBlank() } } ?: picked.toString(2)
        } else picked.toString()
        return if (text.length > max) text.take(max) + "\n… (${text.length - max} more characters)" else text
    }

    /** The bases of [service] in order, each asked until one answers; an HTTP error from a base that answered is final. */
    private fun <T> request(f: Fleet, tool: Tool, url: String, secret: (String) -> String?, timeoutMs: Int, ok: (HttpURLConnection) -> T): T {
        val svc = f.services[tool.service] ?: throw IOException("service ${tool.service} is not declared")
        val bearer = svc.bearerKey?.let(secret)?.takeIf { it.isNotBlank() }
        if (svc.bearerKey != null && bearer == null) throw IOException("no ${svc.bearerKey} in the encrypted store: set it in Configs > Headless Browser")
        val body = (fill(tool.body, url) as JSONObject).toString().toByteArray()
        var last: IOException? = null
        for (b in svc.bases) {
            val c = URL(b + tool.path).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 8_000; c.readTimeout = timeoutMs
                c.setRequestProperty("Content-Type", "application/json")
                bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                try { c.outputStream.use { it.write(body) } } catch (e: IOException) { last = e; continue }
                return ok(c)
            } finally { c.disconnect() }
        }
        throw last ?: IOException("no base declared for ${tool.service}")
    }

    /** Runs a JSON tool; the answer is [display]ed text. Blocking. */
    fun call(f: Fleet, toolId: String, url: String, secret: (String) -> String?, timeoutMs: Int = 120_000): String {
        val tool = f.tools[toolId] ?: return "Failed: $toolId is not a declared fleet tool"
        return runCatching {
            request(f, tool, url, secret, timeoutMs) { c ->
                val s = (if (c.responseCode < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(s) }.getOrNull()
                    ?: return@request if (c.responseCode < 400) s.take(60_000) else "Failed: HTTP ${c.responseCode} ${s.take(300)}"
                if (c.responseCode >= 400 && json.optJSONObject("error") == null) "Failed: HTTP ${c.responseCode} ${json.optString("detail", s.take(300))}" else display(json, tool)
            }
        }.getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName} (is the mesh up?)" }
    }

    /** Runs a file tool: [sink] gets the stream and the file name and returns where it was saved. Blocking. */
    fun download(f: Fleet, toolId: String, url: String, secret: (String) -> String?, sink: (InputStream, String) -> String): String {
        val tool = f.tools[toolId] ?: return "Failed: $toolId is not a declared fleet tool"
        return runCatching {
            request(f, tool, url, secret, 600_000) { c ->
                if (c.responseCode >= 400) "Failed: HTTP ${c.responseCode} ${c.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(300)}"
                else {
                    val name = Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""").find(c.getHeaderField("Content-Disposition").orEmpty())?.groupValues?.get(1)
                        ?.substringAfterLast('/')?.ifBlank { null } ?: "fleet-download"
                    "Saved to " + c.inputStream.use { sink(it, name) }
                }
            }
        }.getOrElse { "Failed: ${it.message ?: it.javaClass.simpleName} (is the mesh up?)" }
    }
}

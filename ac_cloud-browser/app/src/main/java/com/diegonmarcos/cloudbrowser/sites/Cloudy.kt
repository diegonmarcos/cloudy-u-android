package com.diegonmarcos.cloudbrowser.sites

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket

/** One site Cloudy lists. [port] is set for a page the phone serves (the live probe asks it). */
data class CloudyEntry(val title: String, val url: String, val detail: String = "", val port: Int? = null)

/**
 * #1493 Cloudy, pure (JVM-tested in CloudyTest). Public GH-Pages: the declared repos' pages (ui.cloudy.gh_pages, derived from
 * ac_cloud-c3/data/repos_declared.json at build time) plus the live front-html pages.json ([merge]). Private Webserver: what the
 * Cloud-Webserver declares it serves (its serving endpoints and Pages sub-pages, from its build.json) plus the entries of its
 * Repos store when that file can be read ([storeEntries]); a missing store or a stopped server only shortens the list.
 */
object Cloudy {
    fun seedPages(decl: JSONObject): List<CloudyEntry> {
        val a = decl.optJSONArray("gh_pages") ?: JSONArray()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o -> o.optString("url").takeIf { it.isNotBlank() }?.let { CloudyEntry(o.optString("name"), it) } } }
    }

    /** front-html pages.json ({pages:[{repo,url,public}]}): only the public ones, merged into [seed] by address, sorted by title. */
    fun merge(seed: List<CloudyEntry>, pagesJson: String?): List<CloudyEntry> {
        val pages = runCatching { JSONObject(pagesJson ?: "{}").optJSONArray("pages") }.getOrNull() ?: JSONArray()
        val live = (0 until pages.length()).mapNotNull { i ->
            val o = pages.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optBoolean("public", true)) return@mapNotNull null
            o.optString("url").takeIf { it.isNotBlank() }?.let { CloudyEntry(o.optString("repo").ifBlank { it }, it) }
        }
        return (seed + live).distinctBy { it.url.trimEnd('/') }.sortedBy { it.title.lowercase() }
    }

    /** The declared Webserver pages: [decl] = ui.cloudy.webserver (endpoints, pages, base_role). */
    fun declaredWebserver(decl: JSONObject): List<CloudyEntry> {
        val eps = decl.optJSONArray("endpoints") ?: JSONArray()
        val list = (0 until eps.length()).mapNotNull { eps.optJSONObject(it) }
        val out = ArrayList<CloudyEntry>()
        list.forEach { e -> out += CloudyEntry(e.optString("title"), "http://${e.optString("name")}/", e.optString("summary"), e.optInt("port").takeIf { it > 0 }) }
        val base = list.firstOrNull { it.optString("role") == decl.optString("base_role") }
        val pages = decl.optJSONArray("pages") ?: JSONArray()
        if (base != null) for (i in 0 until pages.length()) {
            val p = pages.optJSONObject(i) ?: continue
            out += CloudyEntry(p.optString("label"), "http://${base.optString("name")}${p.optString("route")}", p.optString("tab"), base.optInt("port").takeIf { it > 0 })
        }
        return out
    }

    /** The Repos store (`{entries:[{label,repo,exposure,port}]}`): each running site, on its own loopback port. */
    fun storeEntries(json: String?): List<CloudyEntry> {
        val a = runCatching { JSONObject(json ?: return emptyList()).optJSONArray("entries") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val port = o.optInt("port").takeIf { it in 1..65535 } ?: return@mapNotNull null
            CloudyEntry(o.optString("label").ifBlank { o.optString("repo") }, "http://127.0.0.1:$port/",
                listOf(o.optString("repo"), o.optString("exposure")).filter { it.isNotBlank() }.joinToString(" · "), port)
        }
    }

    /** Does something answer on loopback [port]? BLOCKING: off the main thread. */
    fun probe(port: Int, timeoutMs: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs); true } }.getOrDefault(false)
}

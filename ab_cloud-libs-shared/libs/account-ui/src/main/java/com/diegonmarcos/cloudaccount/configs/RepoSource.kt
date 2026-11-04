package com.diegonmarcos.cloudaccount.configs

import android.util.Base64
import com.diegonmarcos.superapp.accountui.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * #1438 libs:account-ui account-ui.json::configs_sources (#1439, was Cloud Account build.json) as baked into the lib's BuildConfig.CONFIGS_SOURCES_B64: which repos Configs > Desktop,
 * C3 > Infra and C3 > Containers read, and the Runbook's adb-shell switches. Pure data; [parse] takes the JSON so a
 * test can hand in its own.
 */
class ConfigsSources(val json: JSONObject) {
    val owner: String get() = json.optString("owner", "diegonmarcos")
    val exclude: List<String> get() = json.optJSONArray("exclude").strings()
    val desktop: List<JSONObject> get() = json.optJSONArray("desktop").objects()
    val infra: JSONObject get() = json.optJSONObject("infra") ?: JSONObject()
    val containers: JSONObject get() = json.optJSONObject("containers") ?: JSONObject()
    /** #1445 the private repos Configs > Repos connects (read AND write) as libs:git-ui's GitConnect specs. */
    val connectors: List<com.diegonmarcos.cloudlib.gitui.GitConnectSpec> get() = json.optJSONArray("connectors").objects().map {
        com.diegonmarcos.cloudlib.gitui.GitConnectSpec(it.optString("id"), it.optString("label"), owner, it.optString("repo"),
            it.optString("ref", "main"), it.optJSONArray("paths").strings())
    }
    val toggles: List<Toggle> get() = json.optJSONArray("runbook_toggles").objects().map {
        Toggle(it.optString("id"), it.optString("label"), it.optString("on"), it.optString("off"), it.optString("check"), it.optString("on_when"))
    }

    class Toggle(val id: String, val label: String, val on: String, val off: String, val check: String, val onWhen: String)

    companion object {
        fun parse(text: String) = ConfigsSources(runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
        val baked: ConfigsSources by lazy {
            parse(runCatching { String(Base64.decode(BuildConfig.CONFIGS_SOURCES_B64, Base64.DEFAULT)) }.getOrDefault("{}"))
        }
    }
}

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotEmpty() }
internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * Reads the owner's working repos at run time, nothing copied into the app: the listing through the GitHub trees API,
 * a file of a public repo from raw.githubusercontent.com (no API quota), a file of a private one through the contents
 * API with [token] (the gh engine's, used for this request only, never stored or logged here). Answers are cached for
 * the process; [clear] is the pages' Refresh. BLOCKS: call on IO.
 */
object RepoSource {
    var api = "https://api.github.com"
    var raw = "https://raw.githubusercontent.com"
    private val cache = ConcurrentHashMap<String, String>()

    fun clear() = cache.clear()

    private fun get(url: String, token: String?, accept: String): String? = cache[url] ?: runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "cloud-account")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try { if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()?.also { cache[url] = it }

    /** Every blob path of [repo] at [ref]; null when the repo could not be read. */
    fun tree(owner: String, repo: String, ref: String, token: String? = null): List<String>? =
        get("$api/repos/$owner/$repo/git/trees/$ref?recursive=1", token, "application/vnd.github+json")?.let { body ->
            runCatching { JSONObject(body).optJSONArray("tree").objects().filter { it.optString("type") == "blob" }.map { it.optString("path") } }.getOrNull()
        }

    /** The top-level directory names of [repo] at [ref] (one small, non-recursive trees call); null when unreadable. */
    fun topDirs(owner: String, repo: String, ref: String, token: String? = null): List<String>? =
        get("$api/repos/$owner/$repo/git/trees/$ref", token, "application/vnd.github+json")?.let { body ->
            runCatching { JSONObject(body).optJSONArray("tree").objects().filter { it.optString("type") == "tree" }.map { it.optString("path") } }.getOrNull()
        }

    fun file(owner: String, repo: String, ref: String, path: String, token: String? = null): String? =
        if (token.isNullOrBlank()) get("$raw/$owner/$repo/$ref/$path", null, "text/plain")
        else get("$api/repos/$owner/$repo/contents/$path?ref=$ref", token, "application/vnd.github.raw")
}

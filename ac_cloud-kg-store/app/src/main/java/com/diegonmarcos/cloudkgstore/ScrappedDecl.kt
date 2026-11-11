package com.diegonmarcos.cloudkgstore

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1640 the Headless Browsers stack the Scrapped page lists, decoded from the declaration the build script bakes BY REFERENCE
 * (ac_cloud-browser/build.json::ui.domains.headless, the one declaration Cloud Browser's Domains > Headless Browsers draws, #1637).
 * No service, field or link is named here: [stack] keeps every key the declaration gives, in its order.
 */
class HeadlessService(o: JSONObject) {
    val id: String = o.optString("id")
    val title: String = o.optString("title")
    val tagline: String = o.optString("tagline")
    val service: String = o.optString("service")
    val note: String = o.optString("note")
    /** The stack description as declared: (key, text) in declaration order. */
    val stack: List<Pair<String, String>> = o.optJSONObject("stack")?.let { s -> s.keys().asSequence().map { it to s.optString(it) }.filter { it.second.isNotEmpty() }.toList() } ?: emptyList()
    /** The API / MCP link: label, url and detail. */
    val link: Link? = o.optJSONObject("link")?.let { Link(it.optString("label"), it.optString("url"), it.optString("detail")) }

    data class Link(val label: String, val url: String, val detail: String)

    companion object {
        fun parse(text: String): List<HeadlessService> {
            val a = JSONArray(text)
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { HeadlessService(it) }
        }

        /** The baked declaration; an unreadable one is an empty list, never a crash. */
        val all: List<HeadlessService> by lazy {
            runCatching { parse(String(Base64.decode(BuildConfig.HEADLESS_B64, Base64.DEFAULT), Charsets.UTF_8)) }.getOrDefault(emptyList())
        }
    }
}

/** #1640 the repository the Me tab clones at runtime, as build.json::ui.me declares it (owner, repo, ref, entry); no credential. */
data class MeRepo(val owner: String, val repo: String, val ref: String, val entry: String) {
    companion object {
        fun parse(text: String): MeRepo = JSONObject(text).let { MeRepo(it.getString("owner"), it.getString("repo"), it.optString("ref", "main"), it.optString("entry")) }

        val declared: MeRepo? by lazy {
            runCatching { parse(String(Base64.decode(BuildConfig.ME_REPO_B64, Base64.DEFAULT), Charsets.UTF_8)) }.getOrNull()
        }
    }
}

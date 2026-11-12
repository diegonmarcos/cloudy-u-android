package com.diegonmarcos.cloudkgstore

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1640 the Headless Browsers stack the Scrapped page lists, read at runtime from the kg dataset ([KgConfig.headless], #1547): the value of
 * ac_cloud-browser/build.json::ui.domains.headless, the one declaration Cloud Browser's Domains > Headless Browsers draws (#1637).
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
    }
}

/** #1640 the repository the Me tab clones at runtime, as build.json::ui.me declares it (owner, repo, ref, entry); no credential. */
data class MeRepo(val owner: String, val repo: String, val ref: String, val entry: String) {
    companion object {
        fun parse(text: String): MeRepo = JSONObject(text).let { MeRepo(it.getString("owner"), it.getString("repo"), it.optString("ref", "main"), it.optString("entry")) }

        /** This build's declaration (four plain BuildConfig strings): always there, with or without Cloud Store (#1656). Null only when the build names no repository. */
        val declared: MeRepo? = MeRepo(BuildConfig.ME_OWNER, BuildConfig.ME_REPO, BuildConfig.ME_REF.ifBlank { "main" }, BuildConfig.ME_ENTRY)
            .takeIf { it.owner.isNotBlank() && it.repo.isNotBlank() }
    }
}

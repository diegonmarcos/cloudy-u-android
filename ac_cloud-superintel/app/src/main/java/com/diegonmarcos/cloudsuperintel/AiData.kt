package com.diegonmarcos.cloudsuperintel

import android.content.Context
import android.net.Uri
import com.diegonmarcos.superapp.ai.AiKgTab
import com.diegonmarcos.superapp.ai.AiMcpBucket
import org.json.JSONObject

/** One dense row and one titled group of them, as the derived assets (ai/mcp.json, dotfiles.json, plugins.json) declare them. */
data class AiRow(val title: String, val caption: String, val end: String)
data class AiGroup(val title: String, val rows: List<AiRow>)

/** One source of a rows page (#1854): an asset and the group titles (regexes, full match) it takes from it; empty = every group. */
data class AiSource(val asset: String, val groups: List<String>)

/**
 * A page of the Context section or the Cost window: its screen kind and the data that declares it (assets/ai/pages.json, keyed by page
 * id): [asset] for the apis and cost kinds, [sources] for a rows page (so one derived asset can be split over several pages).
 */
data class AiPageSpec(val kind: String, val asset: String, val sources: List<AiSource> = emptyList())

/** The AIs and Cost pages are data: Kotlin names no page, group, row or label (#1499). */
object AiData {
    const val PAGES = "ai/pages.json"
    const val KIND_ROWS = "rows"
    const val KIND_APIS = "apis"
    const val KIND_COST = "cost"
    const val KIND_KG = "kg"

    fun pages(text: String): Map<String, AiPageSpec> {
        val o = JSONObject(text).getJSONObject("pages")
        return o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { AiPageSpec(it.getString("kind"), it.optString("asset"), sources(it)) } }
    }

    private fun sources(o: JSONObject): List<AiSource> {
        val a = o.optJSONArray("sources") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { s ->
            val g = s.optJSONArray("groups")
            AiSource(s.getString("asset"), if (g == null) emptyList() else (0 until g.length()).map { g.getString(it) })
        }
    }

    /** The groups of an asset, only those whose title fully matches one of [only] (empty = all), in the asset's order. */
    fun groups(text: String, only: List<String> = emptyList()): List<AiGroup> {
        val a = JSONObject(text).getJSONArray("groups")
        val keep = only.map { Regex(it) }
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { g -> keep.isEmpty() || keep.any { it.matches(g.getString("title")) } }.map { g ->
            val r = g.getJSONArray("rows")
            AiGroup(g.getString("title"), (0 until r.length()).map { r.getJSONObject(it) }.map { AiRow(it.getString("title"), it.optString("caption"), it.optString("end")) })
        }
    }

    /**
     * #1854 The MCP-KG page: the KG MCP servers of the composer's homes (libs:ai ai-design.json mcp.buckets: the servers each picker lists) and
     * the KG-Store sources that are not servers (the tabs' web and source items). Read from the design the composer itself draws, so the page
     * and the chips cannot drift (no baked copy).
     */
    fun kgGroups(buckets: List<AiMcpBucket>, tabs: List<AiKgTab>): List<AiGroup> =
        buckets.map { b -> AiGroup(b.label, b.known.map { AiRow(it, "known server of the " + b.label + " picker", "") }) } +
            AiGroup("KG-Store sources (not servers)", tabs.flatMap { t -> t.items.filter { it.kind != "mcp" }.map { AiRow(it.label, t.label + " · " + it.detail, it.kind) } })

    fun asset(ctx: Context, name: String): String = ctx.assets.open(name).bufferedReader().use { it.readText() }
}

/**
 * The private store: where the values that must never be in this public repo come from at runtime (the cloud-me-pvt-configs
 * private-data rule). Cloud Account's `secret` provider is the one door (the same one the other apps read the fleet bearer
 * through, behind CONSTELLATION_DATA and the owner's per-package grant). Null when it is not reachable or holds no such key.
 */
fun interface PrivateSource {
    fun get(key: String): String?

    companion object {
        fun account(ctx: Context, authority: String, method: String): PrivateSource = PrivateSource { key ->
            runCatching { ctx.contentResolver.call(Uri.parse("content://$authority"), method, key, null) }.getOrNull()
                ?.takeIf { it.getBoolean("ok") }?.getString("value")?.trim()?.takeIf { it.isNotEmpty() }
        }
    }
}

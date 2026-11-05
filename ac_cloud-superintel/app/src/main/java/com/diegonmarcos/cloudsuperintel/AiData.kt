package com.diegonmarcos.cloudsuperintel

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/** One dense row and one titled group of them, as the derived assets (ai/mcp.json, dotfiles.json, plugins.json) declare them. */
data class AiRow(val title: String, val caption: String, val end: String)
data class AiGroup(val title: String, val rows: List<AiRow>)

/** A page of the AIs and Cost sections: its screen kind and the asset that declares it (assets/ai/pages.json, keyed by page id). */
data class AiPageSpec(val kind: String, val asset: String)

/** The AIs and Cost pages are data: Kotlin names no page, group, row or label (#1499). */
object AiData {
    const val PAGES = "ai/pages.json"
    const val KIND_ROWS = "rows"
    const val KIND_APIS = "apis"
    const val KIND_COST = "cost"

    fun pages(text: String): Map<String, AiPageSpec> {
        val o = JSONObject(text).getJSONObject("pages")
        return o.keys().asSequence().associateWith { k -> o.getJSONObject(k).let { AiPageSpec(it.getString("kind"), it.getString("asset")) } }
    }

    fun groups(text: String): List<AiGroup> {
        val a = JSONObject(text).getJSONArray("groups")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { g ->
            val r = g.getJSONArray("rows")
            AiGroup(g.getString("title"), (0 until r.length()).map { r.getJSONObject(it) }.map { AiRow(it.getString("title"), it.optString("caption"), it.optString("end")) })
        }
    }

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

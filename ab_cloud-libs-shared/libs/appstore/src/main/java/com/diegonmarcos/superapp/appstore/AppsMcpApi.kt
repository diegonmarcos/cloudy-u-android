package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1211 Store > Access > Apps MCP-API: the model. The apps are exactly the members of the fleet group Store > Cloud > Apps
 * lists (assets/apps-mcp-api.json::apps_group, resolved against the baked fleet); each app's endpoints come from its
 * entry in that asset, in three kinds (MCP, API-Debug, API-App). The debug kind falls back to the shared lib's default
 * (GET + POST to c3-infra-api); a kind with no endpoints is reported MISSING per app. This file names no app and no route.
 */
object AppsMcpApi {
    const val TAG_ROW = "apps-mcp-api-row:"
    const val TAG_SUMMARY = "apps-mcp-api-summary"
    const val TAG_MISSING = "apps-mcp-api-missing:"

    class Kind(val id: String, val label: String, val what: String)
    class Endpoint(val method: String, val url: String, val what: String)
    class Row(val id: String, val label: String, val byKind: Map<String, List<Endpoint>>, val inherited: Set<String>) {
        fun missing(kinds: List<Kind>) = kinds.filter { byKind[it.id].isNullOrEmpty() }
    }
    class Decl(val group: String, val kinds: List<Kind>, val lib: String, val base: String,
               val shared: List<Endpoint>, val apps: Map<String, Map<String, List<Endpoint>>>)

    /** #1566 the endpoints every app declares: a fleet-private tier-2 dataset (last-known-good copy of Cloud Account's route); null = not served yet, the Access rows then say so instead of listing anything. */
    const val DATASET = "fleet/apps-mcp-api"
    fun load(ctx: Context): Decl? = runCatching { FleetData.get(ctx.applicationContext, DATASET) { parse(it) } }.getOrNull()

    fun parse(json: String): Decl {
        val o = JSONObject(json)
        val sd = o.optJSONObject("shared_debug") ?: JSONObject()
        val base = sd.optString("base").trimEnd('/')
        val kinds = o.optJSONArray("kinds").objs().map { Kind(it.getString("id"), it.optString("label", it.getString("id")), it.optString("what")) }
        val apps = o.optJSONObject("apps")?.let { a -> a.keys().asSequence().associateWith { id ->
            kinds.associate { k -> k.id to a.getJSONObject(id).optJSONArray(k.id).objs().map { endpoint(it, "") } }
        } }.orEmpty()
        return Decl(o.optString("apps_group", "apps"), kinds, sd.optString("lib"), base,
            sd.optJSONArray("endpoints").objs().map { endpoint(it, base) }, apps)
    }

    private fun endpoint(e: JSONObject, base: String) = Endpoint(e.getString("method"), base + e.getString("path"), e.optString("what"))
    private fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    /** One row per app, in the group's order. [own] = the app's declared endpoints; debug inherits [Decl.shared] when it declares none. */
    fun rows(d: Decl, apps: List<Pair<String, String>>): List<Row> = apps.map { (id, label) ->
        val own = d.apps[id].orEmpty()
        val inherited = if (own["debug"].isNullOrEmpty() && d.shared.isNotEmpty()) setOf("debug") else emptySet()
        Row(id, label, d.kinds.associate { k -> k.id to (if (k.id in inherited) d.shared else own[k.id].orEmpty()) }, inherited)
    }

    fun summary(d: Decl, rows: List<Row>): String =
        "${rows.size} apps  ·  " + d.kinds.joinToString("  ·  ") { k -> "${k.label} ${rows.count { !it.byKind[k.id].isNullOrEmpty() }}/${rows.size}" }

    fun report(d: Decl, rows: List<Row>): String = buildString {
        appendLine("# Apps MCP-API"); appendLine(summary(d, rows))
        for (r in rows) {
            appendLine("## ${r.label} (${r.id})")
            for (k in d.kinds) {
                val eps = r.byKind[k.id].orEmpty()
                if (eps.isEmpty()) appendLine("- ${k.label}: MISSING")
                else eps.forEach { appendLine("- ${k.label}${if (k.id in r.inherited) " (shared ${d.lib})" else ""}: ${it.method} ${it.url}") }
            }
        }
    }
}

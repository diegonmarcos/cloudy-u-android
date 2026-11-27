package com.diegonmarcos.cloudagenda.data

import android.content.Context
import org.json.JSONObject

/** One tile of the trip section (a link of the "Trip management" grid, or a checklist card): label, subtitle, icon, accent and target are the Cloud Me block's fields. */
data class TripLink(val label: String, val subtitle: String, val icon: String, val accent: String, val target: String)

sealed class TripBlock {
    data class Grid(val title: String, val subtitle: String, val links: List<TripLink>) : TripBlock()
    data class Note(val title: String, val body: String) : TripBlock()
    data class Cards(val items: List<TripLink>) : TripBlock()
}

/** Where a tile leads: a Cloud Me page (deep link), a page of this app, or another app. */
sealed class TripTarget {
    data class CloudMe(val page: String) : TripTarget()
    data class Here(val section: String, val page: String) : TripTarget()
    data class App(val id: String) : TripTarget()

    companion object {
        fun parse(t: String): TripTarget? = when {
            t.startsWith("cloudme:page:") -> CloudMe(t.removePrefix("cloudme:"))
            t.startsWith("tasks:") -> t.removePrefix("tasks:").split('/').takeIf { it.size == 2 }?.let { Here(it[0], it[1]) }
            t.startsWith("extapp:") -> App(t.removePrefix("extapp:"))
            else -> null
        }
    }
}

/** #1975 Files > RoadMap > Trip roadmap: assets/trip-roadmap.json (moved from Cloud Me's Geo page). Pure parsing. */
class TripRoadmap(val blocks: List<TripBlock>, val apps: Map<String, String>) {
    companion object {
        const val ASSET = "trip-roadmap.json"
        val EMPTY = TripRoadmap(emptyList(), emptyMap())

        private fun link(o: JSONObject) = TripLink(o.optString("label").ifBlank { o.optString("title") }, o.optString("subtitle"), o.optString("icon"), o.optString("accent"), o.optString("target"))

        fun parse(json: String): TripRoadmap {
            val root = JSONObject(json)
            val apps = root.optJSONObject("apps")?.let { a -> a.keys().asSequence().associateWith { a.optString(it) } }.orEmpty()
            val arr = root.optJSONArray("blocks") ?: return TripRoadmap(emptyList(), apps)
            val blocks = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { b ->
                when (b.optString("kind")) {
                    "link_grid" -> TripBlock.Grid(b.optString("title"), b.optString("subtitle"), b.optJSONArray("links")?.let { l -> (0 until l.length()).map { link(l.getJSONObject(it)) } }.orEmpty())
                    "note" -> TripBlock.Note(b.optString("title"), b.optString("body"))
                    "cards" -> TripBlock.Cards(b.optJSONArray("items")?.let { l -> (0 until l.length()).map { link(l.getJSONObject(it)) } }.orEmpty())
                    else -> null
                }
            }
            return TripRoadmap(blocks, apps)
        }

        fun load(context: Context): TripRoadmap = runCatching { parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrDefault(EMPTY)
    }
}

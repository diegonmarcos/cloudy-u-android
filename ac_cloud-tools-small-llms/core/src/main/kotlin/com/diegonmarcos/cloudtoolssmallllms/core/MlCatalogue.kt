package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject

/** One ML lib as the Cloud Store lists it: its fleet [id], its [label], the one-line [description] (reference rows only) and its [application] (voice, text, image, sensor, tabular), read off the id. */
data class MlLib(val id: String, val label: String, val description: String?, val application: String?)

/** One Cloud Store ML group (Lite-ML or Tiny-ML) shown on the MLs page [page]: its [label], [blurb] and libs in the Store's order. */
data class MlGroup(val page: String, val id: String, val label: String, val blurb: String, val libs: List<MlLib>)

/**
 * The MLs page's data, DERIVED from the Cloud Store's own declaration (aa_cloud-superapp/data/constellation-fleet.json, copied into the
 * assets at build time, never hand-copied): the groups named by build.json::ui.ml_pages, their members in order, and each member's row
 * from the fleet's `apps` (real modules) or `catalogue` (reference rows). No group, lib or application name is spelled here.
 */
object MlCatalogue {
    private val application = Regex("^lib-ml-[tl]-([a-z0-9]+)-")

    /** build.json::ui.ml_pages: page id -> group id (the "_doc" key is not a page). */
    fun pages(o: JSONObject): Map<String, String> {
        return o.keys().asSequence().filter { !it.startsWith("_") }.associateWith { o.getString(it) }
    }

    fun parse(fleetJson: String, pages: Map<String, String>): List<MlGroup> {
        val fleet = JSONObject(fleetJson)
        val rows = HashMap<String, JSONObject>()
        for (key in listOf("catalogue", "apps")) {
            val a = fleet.getJSONArray(key)
            for (i in 0 until a.length()) a.getJSONObject(i).let { rows[it.getString("id")] = it }
        }
        val groups = fleet.getJSONArray("groups")
        val byId = (0 until groups.length()).map { groups.getJSONObject(it) }.associateBy { it.getString("id") }
        return pages.map { (page, id) ->
            val g = requireNotNull(byId[id]) { "the Cloud Store declares no group $id" }
            val members = g.getJSONArray("members")
            val libs = (0 until members.length()).map { i ->
                val m = members.getString(i)
                val r = requireNotNull(rows[m]) { "group $id lists $m, which the Cloud Store declares no row for" }
                MlLib(m, r.optString("label", m), r.optString("description").ifBlank { null }, application.find(m)?.groupValues?.get(1))
            }
            MlGroup(page, id, g.getString("label"), g.optString("blurb"), libs)
        }
    }
}

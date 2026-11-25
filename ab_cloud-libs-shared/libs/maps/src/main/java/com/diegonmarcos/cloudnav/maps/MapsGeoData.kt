package com.diegonmarcos.cloudnav.maps

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Explored map's two reference datasets (#1547, shared, tier 4): country capitals (`geo-capitals`) and the political-cultural subregion
 * members (`geo-region-members`). Served by Cloud Store from data/fleet, never APK assets. While one is loading or not served the map
 * uses its [DEFAULT] (empty): a country pin sits at its cities' centroid, and no subregion is painted visited.
 */
object MapsGeoData {
    const val CAPITALS = "geo-capitals"
    const val REGION_MEMBERS = "geo-region-members"

    val DEFAULT_CAPITALS: Map<String, DoubleArray> = emptyMap()
    val DEFAULT_MEMBERS: List<MapsGeoLayers.RegionMembers> = emptyList()

    /** country name -> [lat, lon] of its capital. */
    fun parseCapitals(text: String): Map<String, DoubleArray> {
        val o = JSONObject(text).getJSONObject("capitals")
        return o.keys().asSequence().associateWith { k -> val a = o.getJSONArray(k); doubleArrayOf(a.getDouble(0), a.getDouble(1)) }
    }

    fun parseMembers(text: String): List<MapsGeoLayers.RegionMembers> {
        val arr: JSONArray = JSONObject(text).getJSONArray("members")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cs = o.getJSONArray("countries")
            MapsGeoLayers.RegionMembers(o.getString("subregion"), (0 until cs.length()).map { cs.getString(it) }.toSet())
        }
    }

    /** The served capitals now and after every update; null while none is served (the caller keeps [DEFAULT_CAPITALS]). */
    fun observeCapitals(ctx: Context): Flow<Map<String, DoubleArray>?> = FleetData.observe(ctx, CAPITALS) { runCatching { parseCapitals(it) }.getOrNull() }

    /** One blocking read (off the main thread), else [DEFAULT_MEMBERS]. */
    fun members(ctx: Context): List<MapsGeoLayers.RegionMembers> =
        FleetData.get(ctx, REGION_MEMBERS) { runCatching { parseMembers(it) }.getOrNull() } ?: DEFAULT_MEMBERS
}

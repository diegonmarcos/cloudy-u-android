package com.diegonmarcos.cloudwiki

import org.json.JSONObject
import java.io.File

/** The real declared data (Gradle runs a module's tests from the module dir): the runtime dataset in data/fleet and build.json::ui, never a copy. */
object Data {
    val wiki: WikiData by lazy { Wiki.parse(File("../../data/fleet/app/com.diegonmarcos.cloudwiki/wiki.json").readText()) }
    private val ui: JSONObject by lazy { JSONObject(File("../build.json").readText()).getJSONObject("ui") }

    private fun section(id: String): JSONObject {
        val s = ui.getJSONArray("sections")
        return (0 until s.length()).map { s.getJSONObject(it) }.first { it.getString("id") == id }
    }

    fun bottomNav(): List<String> = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }

    /** The page ids of the wiki section, in order. */
    fun pages(): List<String> = section("wiki").getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    /** The wiki section's groups as (id, page ids), in order. */
    fun groups(): List<Pair<String, List<String>>> = section("wiki").getJSONArray("groups").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { g -> g.getString("id") to g.getJSONArray("pages").let { p -> (0 until p.length()).map { p.getString(it) } } }
    }

    /** #1867 the hub pages of the wiki section (Sciences, A. Architecture of Sacred Sciences, ...) as (id, the topic ids they hold), in order. */
    fun hubs(): List<Pair<String, List<String>>> = section("wiki").getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        .filter { it.getString("id") != Chronos.PARENT && it.has("pages") }
        .map { h -> h.getString("id") to h.getJSONArray("pages").let { p -> (0 until p.length()).map { p.getJSONObject(it).getString("id") } } }

    fun navMode(): String = section("wiki").optString("nav_mode")
}

package com.diegonmarcos.cloudshopping

import org.json.JSONObject
import java.io.File

/** The real declared data of the app (Gradle runs a module's tests from the module dir), never a copy of it. */
object Data {
    val data: ShopData by lazy { Shop.parse(File("src/main/assets/shop/shop.json").readText()) }
    private val ui: JSONObject by lazy { JSONObject(File("../build.json").readText()).getJSONObject("ui") }

    private fun section(id: String): JSONObject {
        val s = ui.getJSONArray("sections")
        return (0 until s.length()).map { s.getJSONObject(it) }.first { it.getString("id") == id }
    }

    fun bottomNav(): List<String> = ui.getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }

    /** The page ids of the shop section, in order. */
    fun pages(): List<String> = section("shop").getJSONArray("pages").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    /** The shop section's groups as (id, page ids), in order. */
    fun groups(): List<Pair<String, List<String>>> = section("shop").getJSONArray("groups").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { g -> g.getString("id") to g.getJSONArray("pages").let { p -> (0 until p.length()).map { p.getString(it) } } }
    }

    fun groupOf(): Map<String, String> = groups().flatMap { (g, ps) -> ps.map { it to g } }.toMap()
    fun navMode(): String = section("shop").optString("nav_mode")
}

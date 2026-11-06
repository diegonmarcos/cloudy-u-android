package com.diegonmarcos.cloudsuperintel

import org.json.JSONObject
import java.io.File

/** The real declared data of the app (Gradle runs a module's tests from the module dir), never a copy of it. */
object Data {
    fun asset(name: String): String = File("src/main/assets/si/$name").readText()

    /** An asset of assets/ai (the AIs and Cost pages' data, #1499). */
    fun ai(name: String): String = File("src/main/assets/ai/$name").readText()
    fun buildJson(): JSONObject = JSONObject(File("../build.json").readText())

    /** The page ids build.json::ui declares for one section. */
    fun pages(section: String): List<String> {
        val secs = buildJson().getJSONObject("ui").getJSONArray("sections")
        val s = (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == section }
        val p = s.getJSONArray("pages")
        return (0 until p.length()).map { p.getJSONObject(it).getString("id") }
    }

    fun bottomNav(): List<String> = buildJson().getJSONObject("ui").getJSONArray("bottom_nav").let { a -> (0 until a.length()).map { a.getString(it) } }

    private fun section(id: String): JSONObject {
        val secs = buildJson().getJSONObject("ui").getJSONArray("sections")
        return (0 until secs.length()).map { secs.getJSONObject(it) }.first { it.getString("id") == id }
    }

    /** A section's declared `nav_mode`. */
    fun navMode(section: String): String = section(section).optString("nav_mode")

    /** A section's page groups as (id, label, page ids). */
    fun groups(section: String): List<Triple<String, String, List<String>>> {
        val g = section(section).getJSONArray("groups")
        return (0 until g.length()).map { g.getJSONObject(it) }.map { o ->
            val p = o.getJSONArray("pages")
            Triple(o.getString("id"), o.getString("label"), (0 until p.length()).map { p.getString(it) })
        }
    }
}

/** The shared AI lib's real declared data (libs:ai assets), read from where the lib keeps it, never copied. */
object LibAi {
    private const val DIR = "../../ab_cloud-libs-shared/libs/ai/src/main/assets/ai/"
    fun asset(name: String): String = java.io.File(DIR + name).readText()
    fun tabs(): com.diegonmarcos.superapp.ai.AiModelTabs = com.diegonmarcos.superapp.ai.AiModelTabs.parse(asset("model-tabs.json"), asset("models-snapshot.json"))
    fun config(): com.diegonmarcos.superapp.ai.AiConfig = com.diegonmarcos.superapp.ai.AiConfig.parse(asset("ai.json"))
}

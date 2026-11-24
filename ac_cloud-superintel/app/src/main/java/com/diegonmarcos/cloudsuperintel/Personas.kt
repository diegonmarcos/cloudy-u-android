package com.diegonmarcos.cloudsuperintel

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** One SI persona as the served `personas` dataset declares it. */
data class Persona(
    val id: String,
    val label: String,
    val icon: String,
    val role: String,
    val system: String,
    val suggestions: List<String>,
    val agent: String,
    val models: List<String>,
    val web: Boolean,
    val tools: List<String>,
    val router: Boolean,
    /** The MCP servers of the gateway this persona switches on (by the names it lists). */
    val mcps: List<String> = emptyList(),
    /** The CGCs group this persona is in (a build.json::ui group id); empty for the SI router. */
    val group: String = "",
)

/** The personas, parsed from data: Kotlin names none of them. */
object Personas {
    /** The app's own served dataset (`app/<pkg>/personas`, #1547: from data/fleet/app/<pkg>/personas.json, never an APK asset). */
    const val DATASET = "personas"

    /** The router persona's id: the default chat. */
    const val DEFAULT_ID = "si"

    /**
     * What the app uses while the dataset loads or is not served yet (first run, provider absent): the SI router alone, the general assistant
     * that answers as the best-fitting expert, so the Share > SI chat and the SI tab work and nothing crashes; the CGC experts appear when the
     * dataset arrives. A test holds this equal to the served file's `si` persona.
     */
    val DEFAULT: List<Persona> = listOf(
        Persona(
            id = DEFAULT_ID, label = "SI", icon = "auto_awesome", role = "General assistant and router across the specialists.",
            system = "You are SI, the general assistant of Cloud SuperIntel. For each question pick the CGC expert that fits best from this list and answer in that specialist's manner, saying which one you used in a few words at the start (for example 'As the Physics expert:'). If no specialist fits, answer as a careful general assistant.\n{{personas}}\nBe concise, state uncertainty plainly, never invent facts, sources or numbers, and say when something needs a professional.",
            suggestions = listOf("Explain this topic to me step by step", "Compare these two options and recommend one", "Help me plan the next week"),
            agent = "", models = emptyList(), web = false, tools = emptyList(), router = true,
        ),
    )

    /** The served personas and the id of the default one. */
    data class Served(val personas: List<Persona>, val default: String)

    fun parseServed(text: String): Served {
        val all = parse(text)
        val d = JSONObject(text).getString("default")
        require(all.any { it.id == d }) { "default persona $d is not declared" }
        return Served(all, d)
    }

    fun observe(ctx: Context): Flow<Served?> = FleetData.observe(ctx, DATASET, own = true) { runCatching { parseServed(it) }.getOrNull() }
    fun read(ctx: Context): Served? = FleetData.get(ctx, DATASET, own = true) { runCatching { parseServed(it) }.getOrNull() }

    fun parse(text: String): List<Persona> {
        val a = JSONObject(text).getJSONArray("personas")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            fun list(k: String) = o.optJSONArray(k)?.let { x -> (0 until x.length()).map { x.getString(it) } } ?: emptyList()
            Persona(
                o.getString("id"), o.getString("label"), o.getString("icon"), o.optString("role"), o.getString("system"),
                list("suggestions"), o.optString("agent"), list("models"), o.optBoolean("web"), list("tools"), o.optBoolean("router"),
                list("mcps"), o.optString("group"),
            )
        }
    }

    /** The system prompt a persona sends; the router's {{personas}} becomes one "label: role" line per other persona. */
    fun system(p: Persona, all: List<Persona>): String =
        p.system.replace("{{personas}}", all.filter { it.id != p.id }.joinToString("\n") { "- ${it.label}: ${it.role}" })

    /** The first model of [p]'s preference the gateway lists; null = leave the user's pick. */
    fun preferredModel(p: Persona, listed: List<String>): String? = p.models.firstOrNull { it in listed }
}

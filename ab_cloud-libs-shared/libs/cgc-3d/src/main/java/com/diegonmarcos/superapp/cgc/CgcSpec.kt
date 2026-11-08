package com.diegonmarcos.superapp.cgc

import org.json.JSONArray
import org.json.JSONObject

/**
 * One card of a category. What it does depends on the category's [CgcCategory.kind]:
 * clip plays [clip]; color sets [color] (null = the model's own) on the category's targets; visible applies [visible]
 * (target to shown); morph applies [morphs] (blendshape to 0..1). [thumb] is an asset path the card shows, [color] also tints it.
 */
data class CgcOption(
    val id: String,
    val label: String,
    val clip: String? = null,
    val color: String? = null,
    val visible: Map<String, Boolean> = emptyMap(),
    val morphs: Map<String, Double> = emptyMap(),
    val thumb: String? = null,
)

/**
 * One category of the editor (a bottom-bar icon). [kind] is clip | color | tint | visible | morph | background, or anything else for a category
 * no engine draws yet; [engine] names the AvatarEngine capability it needs ("" = none). [targets] are the meshes, materials or
 * nodes a color/tint category paints. [soon] marks a category declared but not wired: its cards show "coming soon".
 */
data class CgcCategory(
    val id: String,
    val label: String,
    val title: String,
    val icon: String,
    val kind: String,
    val targets: List<String>,
    val options: List<CgcOption>,
    val soon: Boolean,
)

/** The OpenRouter connector's declaration (data, never code): where to send, which model, the key's slot. */
data class CgcAiDecl(
    val id: String,
    val endpoint: String,
    val model: String,
    val keySlot: String,
    val signup: String,
    val referer: String,
    val title: String,
)

/** The whole declaration: the scene config cgc.js boots from, the engine id, the categories, the defaults and the AI connector. */
data class CgcSpec(
    val scene: String,
    val engine: String,
    val categories: List<CgcCategory>,
    val defaults: Map<String, String>,
    val ai: CgcAiDecl?,
    val ui: Map<String, String>,
) {
    fun category(id: String): CgcCategory? = categories.firstOrNull { it.id == id }

    /** A UI string declared under "ui" ([fallback] when absent): the editor's chrome names nothing in Kotlin. */
    fun text(key: String, fallback: String = key): String = ui[key] ?: fallback

    companion object {
        const val KIND_CLIP = "clip"
        const val KIND_COLOR = "color"
        const val KIND_TINT = "tint"
        const val KIND_VISIBLE = "visible"
        const val KIND_MORPH = "morph"
        /** The 3D backdrop: each option id is a cgc.js background type (solid | gradient | stars | studio), [CgcOption.color] its colour. */
        const val KIND_BACKGROUND = "background"
        val WIRED_KINDS = setOf(KIND_CLIP, KIND_COLOR, KIND_TINT, KIND_VISIBLE, KIND_MORPH, KIND_BACKGROUND)

        /** Parses the declaration; a malformed document gives an empty spec (the editor then shows nothing rather than crash). */
        fun parse(json: String): CgcSpec {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return CgcSpec("{}", "", emptyList(), emptyMap(), null, emptyMap())
            val cats = objects(o.optJSONArray("categories")).map { c ->
                val kind = c.optString("kind")
                CgcCategory(
                    id = c.optString("id"),
                    label = c.optString("label"),
                    title = c.optString("title").ifEmpty { c.optString("label") },
                    icon = c.optString("icon"),
                    kind = kind,
                    targets = strings(c.optJSONArray("targets")),
                    options = objects(c.optJSONArray("options")).map { option(it) },
                    soon = c.optBoolean("soon", false) || kind !in WIRED_KINDS,
                )
            }
            val d = o.optJSONObject("default")
            val defaults = cats.associate { c ->
                c.id to (d?.optString(c.id)?.takeIf { id -> c.options.any { it.id == id } } ?: c.options.firstOrNull()?.id.orEmpty())
            }
            val ai = o.optJSONObject("ai")?.let {
                CgcAiDecl(it.optString("id"), it.optString("endpoint"), it.optString("model"), it.optString("key_slot"),
                    it.optString("signup"), it.optString("referer"), it.optString("title"))
            }
            val ui = o.optJSONObject("ui")?.let { u -> u.keys().asSequence().filter { !it.startsWith("_") }.associateWith { u.optString(it) } } ?: emptyMap()
            return CgcSpec(o.optJSONObject("scene")?.toString() ?: "{}", o.optString("engine"), cats, defaults, ai, ui)
        }

        private fun option(o: JSONObject): CgcOption {
            val vis = o.optJSONObject("visible")
            val mor = o.optJSONObject("morphs")
            return CgcOption(
                id = o.optString("id"),
                label = o.optString("label"),
                clip = o.optString("clip").ifEmpty { null },
                color = if (o.isNull("color")) null else o.optString("color").ifEmpty { null },
                visible = vis?.keys()?.asSequence()?.associateWith { vis.optBoolean(it) } ?: emptyMap(),
                morphs = mor?.keys()?.asSequence()?.associateWith { mor.optDouble(it, 0.0) } ?: emptyMap(),
                thumb = o.optString("thumb").ifEmpty { null },
            )
        }

        private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() }
    }
}

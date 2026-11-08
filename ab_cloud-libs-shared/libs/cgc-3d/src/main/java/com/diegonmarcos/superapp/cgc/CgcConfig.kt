package com.diegonmarcos.superapp.cgc

import org.json.JSONObject

/**
 * The editor's configuration (one option id per category) and how it becomes scene ops for cgc.js's apply():
 *   {"clip": id, "colors": {target: hex|null}, "tints": {target: hex|null}, "visible": {target: bool}, "morphs": {name: 0..1}, "background": {type: solid|gradient|stars|studio, color?}}.
 * Pure (org.json only), so the JVM suite holds every rule; .github/workflows/cg-omniverse-avatar-proof.yml plants mutants in it.
 */
object CgcConfig {
    /** [saved] (a JSON object of category to option id, or anything else) made whole against [spec]: every category, every id declared. */
    fun load(spec: CgcSpec, saved: String?): Map<String, String> {
        val o = saved?.let { runCatching { JSONObject(it) }.getOrNull() }
        return spec.categories.associate { c ->
            val id = o?.optString(c.id)
            c.id to (if (id != null && c.options.any { it.id == id }) id else spec.defaults[c.id].orEmpty())
        }
    }

    fun save(cfg: Map<String, String>): String = JSONObject(cfg.toSortedMap() as Map<*, *>).toString()

    /** [cfg] with [category] set to [id]; an undeclared category or option, or a category not wired yet, leaves it unchanged. */
    fun set(spec: CgcSpec, cfg: Map<String, String>, category: String, id: String, engine: AvatarEngine? = null): Map<String, String> {
        val c = spec.category(category) ?: return cfg
        if (soon(c, engine)) return cfg
        return if (c.options.any { it.id == id }) cfg + (category to id) else cfg
    }

    /** True when [c] has no engine yet: declared soon or of an unwired kind (CgcSpec folds both into soon), or a kind [engine] cannot draw. */
    fun soon(c: CgcCategory, engine: AvatarEngine? = null): Boolean =
        c.soon || (engine != null && c.kind !in engine.kinds)

    /** The scene ops that draw [cfg]: every wired category contributes; a morph category zeroes the blendshapes its other options use. */
    fun ops(spec: CgcSpec, cfg: Map<String, String>, engine: AvatarEngine? = null): JSONObject {
        val out = JSONObject()
        val colors = JSONObject(); val tints = JSONObject(); val visible = JSONObject(); val morphs = JSONObject()
        for (c in spec.categories) {
            if (soon(c, engine)) continue
            val id = cfg[c.id] ?: spec.defaults[c.id]
            val opt = c.options.firstOrNull { it.id == id } ?: continue
            when (c.kind) {
                CgcSpec.KIND_CLIP -> if (opt.clip != null) out.put("clip", opt.clip)
                CgcSpec.KIND_COLOR, CgcSpec.KIND_TINT -> {
                    val into = if (c.kind == CgcSpec.KIND_COLOR) colors else tints
                    for (t in c.targets) into.put(t, opt.color ?: JSONObject.NULL)
                }
                CgcSpec.KIND_VISIBLE -> for ((t, on) in opt.visible) visible.put(t, on)
                CgcSpec.KIND_BACKGROUND -> out.put("background", JSONObject().put("type", opt.id).also { b -> opt.color?.let { b.put("color", it) } })
                CgcSpec.KIND_MORPH -> {
                    for (o in c.options) for (name in o.morphs.keys) if (!morphs.has(name)) morphs.put(name, 0.0)
                    for ((name, v) in opt.morphs) morphs.put(name, v.coerceIn(0.0, 1.0))
                }
            }
        }
        if (colors.length() > 0) out.put("colors", colors)
        if (tints.length() > 0) out.put("tints", tints)
        if (visible.length() > 0) out.put("visible", visible)
        if (morphs.length() > 0) out.put("morphs", morphs)
        return out
    }
}

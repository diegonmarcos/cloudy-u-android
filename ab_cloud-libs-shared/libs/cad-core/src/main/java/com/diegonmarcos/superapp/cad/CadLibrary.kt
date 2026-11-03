package com.diegonmarcos.superapp.cad

import org.json.JSONObject

/** One format row of a library page: its extensions (the first names the format), a label and a one-line purpose. */
data class LibFormat(val exts: List<String>, val label: String, val purpose: String) {
    val formats: Set<String> get() = exts.map(CadFormats::normalize).toSet()
}

/** A library page's declaration (build.json::ui.libraries[id]): formats, the Core Difference values, the default model. */
data class CadLibrary(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultModel: String,
    val formats: List<LibFormat>,
    val core: Map<String, String>,
)

object CadLibraries {
    /** The Core Difference rows, in order (keys of each library's `core`). */
    val CORE_ROWS = listOf("geometry", "need", "standards")

    fun parse(json: String): List<CadLibrary> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        return root.keys().asSequence().filterNot { it.startsWith("_") }.mapNotNull { id ->
            val o = root.optJSONObject(id) ?: return@mapNotNull null
            val fa = o.optJSONArray("formats")
            val formats = (0 until (fa?.length() ?: 0)).mapNotNull { fa!!.optJSONObject(it) }.map { f ->
                val e = f.optJSONArray("ext")
                LibFormat((0 until (e?.length() ?: 0)).map { e!!.optString(it) }, f.optString("label"), f.optString("purpose"))
            }
            val c = o.optJSONObject("core")
            CadLibrary(id, o.optString("title", id), o.optString("subtitle"), o.optString("default_model"), formats,
                CORE_ROWS.associateWith { c?.optString(it).orEmpty() })
        }.toList()
    }

    /** The model a page opens first: the declared default, only if it belongs to this library. */
    fun defaultModel(lib: CadLibrary, models: List<Model>): Model? =
        models.firstOrNull { it.id == lib.defaultModel && it.library == lib.id }

    /** The library's models of one format row (original format, not the derived view). */
    fun modelsOf(lib: CadLibrary, f: LibFormat, models: List<Model>): List<Model> =
        models.filter { it.library == lib.id && it.format in f.formats }
}

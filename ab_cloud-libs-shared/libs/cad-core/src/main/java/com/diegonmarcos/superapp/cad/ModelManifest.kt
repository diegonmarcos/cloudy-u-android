package com.diegonmarcos.superapp.cad

import org.json.JSONObject

/** A derived file of a model: the view the app renders (a GLB) or another derivative (IFC). [tool] is its provenance. */
data class ModelView(
    val path: String, val format: String, val size: Long, val sha256: String,
    val tool: String = "", val vertices: Long = -1, val triangles: Long = -1,
    /** A view bundled in the APK (path under assets/): drawn from there, never fetched. Blank = fetched from the originals repo at [path]. */
    val asset: String = "",
    /** #1664 the preview image of the view (path under assets/, a JPEG rendered from the GLB by tools/make-thumbs.mjs); blank = none declared. */
    val thumb: String = "",
)

/** A declared category of the model data (the Model filter): [id] is what a model names, [label] what the filter shows. */
data class ModelCategory(val id: String, val label: String)

data class Model(
    val id: String,
    val name: String,
    val library: String,
    val format: String,
    val path: String,
    val source: String,
    val licence: String,
    val size: Long,
    val sha256: String,
    val view: ModelView?,
    val conversion: String,
    /** #1542 the declared metadata the table and the model page show; every field is optional in the data (blank / -1 = not declared). */
    val category: String = "",
    val software: String = "",
    val updated: String = "",
    val units: String = "",
    val vertices: Long = -1,
    val triangles: Long = -1,
    val bboxMin: List<Double> = emptyList(),
    val bboxMax: List<Double> = emptyList(),
    val materialCount: Int = -1,
    val materials: List<String> = emptyList(),
    val layerCount: Int = -1,
    val layers: List<String> = emptyList(),
    val componentCount: Int = -1,
    val derived: List<ModelView> = emptyList(),
    val log: List<String> = emptyList(),
)

/** models.json of the public LFS repo hardtech-db-models (#1404, schema 2 #1542). An entry missing id/path/format or with no valid sha256 is dropped. */
object ModelManifest {
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun parse(json: String): List<Model> {
        val arr = runCatching { JSONObject(json).optJSONArray("models") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id"); val path = o.optString("path"); val format = o.optString("format")
            val sha = o.optString("sha256").lowercase()
            if (id.isBlank() || path.isBlank() || format.isBlank() || !SHA.matches(sha)) return@mapNotNull null
            val v = o.optJSONObject("view")?.let { view(it) }
            val extra = o.optJSONArray("derived")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> view(j) } } }.orEmpty()
            val st = o.optJSONObject("stats")
            val bb = o.optJSONObject("bbox")
            Model(id, o.optString("name", id), o.optString("library"), CadFormats.normalize(format), path, o.optString("source"),
                o.optString("licence"), o.optLong("size"), sha, v, o.optString("conversion"),
                category = o.optString("category"), software = o.optString("software"), updated = o.optString("updated"), units = o.optString("units"),
                vertices = st?.optLong("vertices", -1) ?: -1, triangles = st?.optLong("triangles", -1) ?: -1,
                bboxMin = nums(bb?.optJSONArray("min")), bboxMax = nums(bb?.optJSONArray("max")),
                materialCount = o.optInt("material_count", -1), materials = strs(o.optJSONArray("materials")),
                layerCount = o.optInt("layer_count", -1), layers = strs(o.optJSONArray("layers")),
                componentCount = o.optInt("component_count", -1), derived = extra, log = strs(o.optJSONArray("conversion_log")))
        }
    }

    /** The declared categories (the Model filter), in order; none declared = empty (the table then derives them from the models). */
    fun categories(json: String): List<ModelCategory> {
        val arr = runCatching { JSONObject(json).optJSONArray("categories") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
            .map { ModelCategory(it.optString("id"), it.optString("label", it.optString("id"))) }
    }

    private fun view(o: JSONObject): ModelView? {
        // a bundled view names only its asset: that is its path too
        val j = if (o.optString("path").isBlank() && o.optString("asset").isNotBlank()) JSONObject(o.toString()).put("path", o.optString("asset")) else o
        val vs = j.optString("sha256").lowercase()
        return if (j.optString("path").isBlank() || !SHA.matches(vs)) null
        else ModelView(j.optString("path"), CadFormats.normalize(j.optString("format", j.optString("path").substringAfterLast('.'))), j.optLong("size"), vs,
            j.optString("tool"), j.optLong("vertices", -1), j.optLong("triangles", -1), j.optString("asset"), j.optString("thumb"))
    }

    private fun strs(a: org.json.JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }
    private fun nums(a: org.json.JSONArray?): List<Double> = (0 until (a?.length() ?: 0)).map { a!!.optDouble(it) }
}

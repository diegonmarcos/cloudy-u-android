package com.diegonmarcos.superapp.cad

import org.json.JSONObject

data class ModelView(val path: String, val format: String, val size: Long, val sha256: String)

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
)

/** models.json of the public LFS repo hardtech-db-models (#1404). An entry missing id/path/format or with no valid sha256 is dropped. */
object ModelManifest {
    private val SHA = Regex("^[0-9a-f]{64}$")

    fun parse(json: String): List<Model> {
        val arr = runCatching { JSONObject(json).optJSONArray("models") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val id = o.optString("id"); val path = o.optString("path"); val format = o.optString("format")
            val sha = o.optString("sha256").lowercase()
            if (id.isBlank() || path.isBlank() || format.isBlank() || !SHA.matches(sha)) return@mapNotNull null
            val v = o.optJSONObject("view")?.let { j ->
                val vs = j.optString("sha256").lowercase()
                if (j.optString("path").isBlank() || !SHA.matches(vs)) null
                else ModelView(j.optString("path"), CadFormats.normalize(j.optString("format", j.optString("path").substringAfterLast('.'))), j.optLong("size"), vs)
            }
            Model(id, o.optString("name", id), o.optString("library"), CadFormats.normalize(format), path, o.optString("source"),
                o.optString("licence"), o.optLong("size"), sha, v, o.optString("conversion"))
        }
    }
}

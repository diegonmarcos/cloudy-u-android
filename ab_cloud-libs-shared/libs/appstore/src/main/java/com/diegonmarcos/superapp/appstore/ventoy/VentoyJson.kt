package com.diegonmarcos.superapp.appstore.ventoy

import org.json.JSONArray
import org.json.JSONObject

/**
 * #1405 /ventoy/ventoy.json, the persistence plugin (https://www.ventoy.net/en/plugin_persistence.html):
 *   { "persistence": [ { "image": "/ISO/x.iso", "backend": "/persistence/x.dat" } ] }
 * Every other plugin the owner wrote by hand (theme, menu_alias, ...) is kept as it is. A file that is not JSON is
 * never overwritten: the edit is refused, so a typo on the drive cannot cost the owner their config.
 */
object VentoyJson {
    const val DIR = "/ventoy"
    const val PATH = "/ventoy/ventoy.json"
    private const val KEY = "persistence"

    /** A path on the Ventoy data partition: absolute, no `..`, no backslash. */
    fun isDrivePath(p: String): Boolean = p.startsWith("/") && p.length > 1 && '\\' !in p && p.split('/').none { it == ".." || it == "." }

    private fun parse(existing: String?): JSONObject =
        if (existing.isNullOrBlank()) JSONObject()
        else runCatching { JSONObject(existing) }.getOrElse { throw IllegalArgumentException("ventoy.json is not valid JSON; left untouched") }

    /** [existing] with exactly one persistence entry for [image] -> [backend] (an older one for the image is replaced). */
    fun withPersistence(existing: String?, image: String, backend: String): String {
        require(isDrivePath(image) && isDrivePath(backend)) { "not a drive path: $image / $backend" }
        val root = parse(existing)
        val out = JSONArray()
        root.optJSONArray(KEY)?.let { old -> for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { it.optString("image") != image }?.let(out::put) }
        out.put(JSONObject().put("image", image).put("backend", backend))
        root.put(KEY, out)
        return root.toString(2)
    }

    /** [existing] without any persistence entry for [image]; the key goes when its list is empty. */
    fun withoutImage(existing: String?, image: String): String {
        val root = parse(existing)
        val old = root.optJSONArray(KEY) ?: return root.toString(2)
        val out = JSONArray()
        for (i in 0 until old.length()) old.optJSONObject(i)?.takeIf { it.optString("image") != image }?.let(out::put)
        if (out.length() == 0) root.remove(KEY) else root.put(KEY, out)
        return root.toString(2)
    }

    /** The backend file(s) declared for [image]: `backend` is a string or, for several, an array. */
    fun backendsOf(existing: String?, image: String): List<String> {
        val old = runCatching { parse(existing) }.getOrNull()?.optJSONArray(KEY) ?: return emptyList()
        return (0 until old.length()).mapNotNull { old.optJSONObject(it) }.filter { it.optString("image") == image }.flatMap { e ->
            when (val b = e.opt("backend")) {
                is String -> listOf(b)
                is JSONArray -> (0 until b.length()).map { b.optString(it) }
                else -> emptyList()
            }
        }.filter { it.isNotEmpty() }
    }
}

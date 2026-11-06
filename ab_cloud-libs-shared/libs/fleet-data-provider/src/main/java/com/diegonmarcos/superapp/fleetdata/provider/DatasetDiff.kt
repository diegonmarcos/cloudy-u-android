package com.diegonmarcos.superapp.fleetdata.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The Data page's "diff against the previous version": the JSON paths added, removed and changed between two copies. */
object DatasetDiff {
    data class Change(val path: String, val kind: Kind, val before: String?, val after: String?)
    enum class Kind { ADDED, REMOVED, CHANGED }

    fun diff(before: String?, after: String): List<Change> {
        val a = before?.let { flatten(it) } ?: emptyMap()
        val b = flatten(after)
        val out = ArrayList<Change>()
        for ((k, v) in b) { val o = a[k]; if (o == null) out += Change(k, Kind.ADDED, null, v) else if (o != v) out += Change(k, Kind.CHANGED, o, v) }
        for ((k, v) in a) if (k !in b) out += Change(k, Kind.REMOVED, v, null)
        return out.sortedBy { it.path }
    }

    fun flatten(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(p: String, e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> walk("$p.$k", v) }
                is JsonArray -> e.forEachIndexed { i, v -> walk("$p[$i]", v) }
                is JsonPrimitive -> out[p] = e.toString()
            }
        }
        runCatching { walk("$", Json.parseToJsonElement(text)) }
        return out
    }
}

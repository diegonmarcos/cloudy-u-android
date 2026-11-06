package com.diegonmarcos.superapp.fleetdata.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One shared dataset: who may read it (`*` = every fleet app), its tier and, optionally, the repo path it is pulled from. */
data class ManifestEntry(val tier: Int, val consumers: List<String>, val source: String?) {
    fun grants(caller: String): Boolean = "*" in consumers || caller in consumers
}

/**
 * The served data manifest (shared dataset `manifest`; granting access is a data push, never a rebuild):
 * `{"schemaVersion":1,"shared":{"<dataset>":{"tier":3,"consumers":["*"|"<pkg>"],"source":"<repo path>"}},"apps":{"<pkg>":["<dataset>"]}}`.
 * `apps` lists the datasets served at app/<pkg>/<dataset>, readable by that package alone.
 */
class FleetManifest(val shared: Map<String, ManifestEntry>, val apps: Map<String, List<String>>) {
    companion object {
        val EMPTY = FleetManifest(emptyMap(), emptyMap())
        const val DATASET = "manifest"

        fun parse(text: String): FleetManifest? = runCatching {
            val root = Json.parseToJsonElement(text) as JsonObject
            val sh = (root["shared"] as? JsonObject).orEmpty().mapValues { (_, v) ->
                val o = v as JsonObject
                ManifestEntry(
                    (o["tier"] as? JsonPrimitive)?.intOrNull ?: 3,
                    (o["consumers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList(),
                    (o["source"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            val ap = (root["apps"] as? JsonObject).orEmpty().mapValues { (_, v) -> (v as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
            if (root["shared"] == null && root["apps"] == null) null else FleetManifest(sh, ap)
        }.getOrNull()
    }
}

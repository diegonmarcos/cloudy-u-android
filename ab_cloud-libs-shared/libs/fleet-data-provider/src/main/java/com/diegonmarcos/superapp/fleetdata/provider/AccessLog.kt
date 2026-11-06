package com.diegonmarcos.superapp.fleetdata.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** Who read what (the Data page's Consumers tab): one JSON line per request, the newest [max] kept. */
class AccessLog(private val file: File, private val max: Int = 200) {
    data class Row(val at: Long, val caller: String, val path: String, val granted: Boolean)

    @Synchronized fun add(caller: String?, path: String, granted: Boolean, now: Long) {
        val line = buildJsonObject { put("at", now); put("caller", caller ?: "?"); put("path", path); put("granted", granted) }.toString()
        val old = if (file.isFile) file.readLines() else emptyList()
        file.parentFile?.mkdirs()
        file.writeText((old + line).takeLast(max).joinToString("\n") + "\n")
    }

    @Synchronized fun rows(): List<Row> = (if (file.isFile) file.readLines() else emptyList()).mapNotNull { l ->
        runCatching {
            val o = Json.parseToJsonElement(l) as JsonObject
            Row((o["at"] as JsonPrimitive).longOrNull ?: 0, (o["caller"] as JsonPrimitive).contentOrNull ?: "?", (o["path"] as JsonPrimitive).contentOrNull ?: "", (o["granted"] as JsonPrimitive).boolean)
        }.getOrNull()
    }.reversed()
}

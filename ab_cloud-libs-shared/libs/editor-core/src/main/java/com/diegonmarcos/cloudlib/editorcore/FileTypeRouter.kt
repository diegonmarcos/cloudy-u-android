package com.diegonmarcos.cloudlib.editorcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The viewer ids of editor-types.json. */
object Viewers {
    const val WYSIWYG = "wysiwyg"
    const val MEDIA = "media"
    const val PDF = "pdf"
    const val ODF = "odf"
    const val UNSUPPORTED = "unsupported"
}

/** Where a file goes: the [viewer] lib, the [format] it is read as, and whether that viewer has an Edit mode. */
data class Route(val viewer: String, val format: String, val editable: Boolean, val extension: String) {
    val supported: Boolean get() = viewer != Viewers.UNSUPPORTED
}

/** Routes a path to its viewer by the declared data. Built from the JSON text so a test (or another app) can hand its own. */
class FileTypeRouter(json: String) {
    private class Entry(val viewer: String, val format: String, val editable: Boolean)

    private val byExtension = HashMap<String, Entry>()
    private val refused = HashSet<String>()
    val unsupportedReason: String

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        for (v in root["viewers"]?.jsonArray.orEmpty()) {
            val o: JsonObject = v.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val editable = o["editable"]?.jsonPrimitive?.boolean ?: false
            for ((format, exts) in o["formats"]!!.jsonObject) {
                for (e in exts.jsonArray) byExtension[(e as JsonPrimitive).content.lowercase()] = Entry(id, format, editable)
            }
        }
        val un = root["unsupported"]!!.jsonObject
        unsupportedReason = un["reason"]?.jsonPrimitive?.content ?: "unsupported format"
        for (e in (un["extensions"] ?: JsonArray(emptyList())).jsonArray) refused += e.jsonPrimitive.content.lowercase()
    }

    fun route(path: String): Route {
        val name = path.substringAfterLast('/')
        val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        if (ext in refused) return Route(Viewers.UNSUPPORTED, ext, false, ext)
        val e = byExtension[ext] ?: return Route(Viewers.UNSUPPORTED, ext, false, ext)
        return Route(e.viewer, e.format, e.editable, ext)
    }

    /** Every extension a supported viewer takes, for a file picker's filter. */
    fun supportedExtensions(): Set<String> = byExtension.keys

    companion object {
        /** The router of this lib's own editor-types.json. */
        fun default(): FileTypeRouter {
            val text = FileTypeRouter::class.java.classLoader!!.getResourceAsStream("editor-types.json")!!.use { it.readBytes().decodeToString() }
            return FileTypeRouter(text)
        }
    }
}

package com.diegonmarcos.cloudlib.editorcore

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The viewer ids of editor-types.json. */
object Viewers {
    const val WYSIWYG = "wysiwyg"
    const val MEDIA = "media"
    const val PDF = "pdf"
    const val ODF = "odf"
    const val JSON = "json"
    const val UNSUPPORTED = "unsupported"
}

/** Where a file goes: the [viewer] lib, the [format] it is read as, and whether that viewer has an Edit mode. */
data class Route(val viewer: String, val format: String, val modes: List<EditorMode>, val extension: String) {
    val supported: Boolean get() = viewer != Viewers.UNSUPPORTED

    /** Whether the viewer has an Edit mode (false for view only types and for unsupported ones). */
    val editable: Boolean get() = EditorMode.Edit in modes
}

/** Routes a path to its viewer by the declared data. Built from the JSON text so a test (or another app) can hand its own. */
class FileTypeRouter(json: String) {
    private class Entry(val viewer: String, val format: String, val modes: List<EditorMode>)

    private val byExtension = HashMap<String, Entry>()
    private val refused = HashSet<String>()
    val unsupportedReason: String

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        for (v in root["viewers"]?.jsonArray.orEmpty()) {
            val o: JsonObject = v.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val modes = modesOf(o["modes"]) ?: listOf(EditorMode.View)
            val perFormat = o["format_modes"]?.jsonObject
            for ((format, exts) in o["formats"]!!.jsonObject) {
                val m = modesOf(perFormat?.get(format)) ?: modes
                for (e in exts.jsonArray) byExtension[(e as JsonPrimitive).content.lowercase()] = Entry(id, format, m)
            }
        }
        val un = root["unsupported"]!!.jsonObject
        unsupportedReason = un["reason"]?.jsonPrimitive?.content ?: "unsupported format"
        for (e in (un["extensions"] ?: JsonArray(emptyList())).jsonArray) refused += e.jsonPrimitive.content.lowercase()
    }

    private fun modesOf(e: kotlinx.serialization.json.JsonElement?): List<EditorMode>? =
        e?.jsonArray?.map { EditorMode.of(it.jsonPrimitive.content) }?.distinct()?.takeIf { it.isNotEmpty() }

    fun route(path: String): Route {
        val name = path.substringAfterLast('/')
        val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        if (ext in refused) return Route(Viewers.UNSUPPORTED, ext, emptyList(), ext)
        val e = byExtension[ext] ?: return Route(Viewers.UNSUPPORTED, ext, emptyList(), ext)
        return Route(e.viewer, e.format, e.modes, ext)
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

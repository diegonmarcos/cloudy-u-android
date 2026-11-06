package com.diegonmarcos.cloudlib.editorcore

import java.io.File
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
    const val HEX = "hex"
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
    private val modesByFormat = HashMap<String, List<EditorMode>>()
    private val duplicates = ArrayList<String>()
    private val sniffRules: SniffRules?
    val unsupportedReason: String

    /** Extensions two formats both claim (a data mistake: the later one would win silently); the suite holds this empty. */
    val duplicateExtensions: List<String> get() = duplicates

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        for (v in root["viewers"]?.jsonArray.orEmpty()) {
            val o: JsonObject = v.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            val modes = modesOf(o["modes"]) ?: listOf(EditorMode.View)
            val perFormat = o["format_modes"]?.jsonObject
            for ((format, exts) in o["formats"]!!.jsonObject) {
                val m = modesOf(perFormat?.get(format)) ?: modes
                modesByFormat["$id/$format"] = m
                for (e in exts.jsonArray) {
                    val ext = (e as JsonPrimitive).content.lowercase()
                    if (ext in byExtension) duplicates += ext
                    byExtension[ext] = Entry(id, format, m)
                }
            }
        }
        sniffRules = root["sniff"]?.jsonObject?.let { parseSniff(it) }
        val un = root["unsupported"]!!.jsonObject
        unsupportedReason = un["reason"]?.jsonPrimitive?.content ?: "unsupported format"
        for (e in (un["extensions"] ?: JsonArray(emptyList())).jsonArray) refused += e.jsonPrimitive.content.lowercase()
    }

    private fun parseSniff(o: JsonObject): SniffRules {
        val text = o["text"]!!.jsonObject
        val binary = o["binary"]!!.jsonObject
        val magic = (o["magic"] ?: JsonArray(emptyList())).jsonArray.map { m ->
            val mo = m.jsonObject
            Magic(
                mo["name"]?.jsonPrimitive?.content ?: "",
                mo["viewer"]!!.jsonPrimitive.content,
                mo["format"]!!.jsonPrimitive.content,
                mo["match"]!!.jsonArray.map { p -> p.jsonObject["offset"]!!.jsonPrimitive.content.toInt() to hex(p.jsonObject["hex"]!!.jsonPrimitive.content) },
            )
        }
        return SniffRules(
            sampleBytes = o["sample_bytes"]?.jsonPrimitive?.content?.toInt() ?: 4096,
            maxControlRatio = o["max_control_ratio"]?.jsonPrimitive?.content?.toDouble() ?: 0.02,
            minPrintableAscii = o["min_printable_ascii_ratio"]?.jsonPrimitive?.content?.toDouble() ?: 0.95,
            textViewer = text["viewer"]!!.jsonPrimitive.content, textFormat = text["format"]!!.jsonPrimitive.content,
            binaryViewer = binary["viewer"]!!.jsonPrimitive.content, binaryFormat = binary["format"]!!.jsonPrimitive.content,
            magic = magic,
        )
    }

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun modesOf(e: kotlinx.serialization.json.JsonElement?): List<EditorMode>? =
        e?.jsonArray?.map { EditorMode.of(it.jsonPrimitive.content) }?.distinct()?.takeIf { it.isNotEmpty() }

    /** By name alone: a known extension routes by the table, an Office format is refused, anything else is text (no bytes to sniff yet). */
    fun route(path: String): Route = route(path, null)

    /**
     * #1537 By name and leading bytes. A known extension routes by the table; an Office format is refused. A name with no (or an unknown)
     * extension is NEVER unsupported: [head] (the file's first bytes, null = unknown) is sniffed by magic number, then as text, else it is
     * binary and goes to the hex view. A table without a `sniff` block (a custom one) still answers unsupported for such a name.
     */
    fun route(path: String, head: ByteArray?): Route {
        val name = path.substringAfterLast('/')
        val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        if (ext in refused) return Route(Viewers.UNSUPPORTED, ext, emptyList(), ext)
        byExtension[ext]?.let { return Route(it.viewer, it.format, it.modes, ext) }
        val rules = sniffRules ?: return Route(Viewers.UNSUPPORTED, ext, emptyList(), ext)
        val r = if (head != null) FileSniffer.sniff(head, rules) else FileSniffer.Result(rules.textViewer, rules.textFormat, FileSniffer.Kind.TEXT, "text")
        return Route(r.viewer, r.format, modesByFormat["${r.viewer}/${r.format}"] ?: listOf(EditorMode.View), ext)
    }

    /** [route] for a file on disk: the first bytes are read only when the name alone does not decide. */
    fun routeFile(file: File): Route {
        val name = file.name
        val ext = if (name.contains('.')) name.substringAfterLast('.').lowercase() else ""
        val rules = sniffRules
        if (rules == null || ext in refused || ext in byExtension) return route(file.path, null)
        return route(file.path, readHead(file, rules.sampleBytes))
    }

    private fun readHead(file: File, max: Int): ByteArray? = runCatching {
        file.inputStream().use { s ->
            val buf = ByteArray(max)
            var n = 0
            while (n < max) { val r = s.read(buf, n, max - n); if (r <= 0) break; n += r }
            buf.copyOf(n)
        }
    }.getOrNull()

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

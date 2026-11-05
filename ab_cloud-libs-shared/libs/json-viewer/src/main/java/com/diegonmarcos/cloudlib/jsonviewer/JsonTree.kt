package com.diegonmarcos.cloudlib.jsonviewer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class JsonKind { Object, Array, String, Number, Boolean, Null }

/** One visible line of the tree. [path] is the copyable JSONPath ($.a.b[0]); [text] is the value of a leaf, or the "{n}" / "[n]" summary of a container. */
data class JsonRow(val path: String, val key: String?, val depth: Int, val kind: JsonKind, val text: String, val expandable: Boolean, val expanded: Boolean)

/** The parsed document, its rows and the copy values. Pure Kotlin: the Compose view only draws what this says. */
class JsonTree private constructor(private val nodes: List<Node>) {
    private class Node(val path: String, val parent: String?, val key: String?, val depth: Int, val kind: JsonKind, val text: String, val element: JsonElement, val children: Int)

    private val byPath = nodes.associateBy { it.path }

    /** The paths open at first: the root, so its children (the first level) show closed. */
    fun defaultExpanded(): Set<String> = setOf(ROOT)

    /**
     * The lines to draw. With no [query] a node shows when all its ancestors are in [expanded]. With a query, the nodes whose key or
     * value contains it (case-insensitive) show, with all their ancestors, everything open.
     */
    fun rows(expanded: Set<String>, query: String = ""): List<JsonRow> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            val open = HashSet(expanded)
            val out = ArrayList<JsonRow>()
            val hidden = HashSet<String>()
            for (n in nodes) {
                if (n.parent != null && (n.parent in hidden || n.parent !in open)) { hidden += n.path; continue }
                out += row(n, n.path in open)
            }
            return out
        }
        val keep = HashSet<String>()
        for (n in nodes) {
            if ((n.key?.lowercase()?.contains(q) == true) || (n.children == 0 && n.text.lowercase().contains(q))) {
                var p: String? = n.path
                while (p != null && keep.add(p)) p = byPath[p]?.parent
            }
        }
        return nodes.filter { it.path in keep }.map { row(it, it.children > 0) }
    }

    private fun row(n: Node, open: Boolean) = JsonRow(n.path, n.key, n.depth, n.kind, n.text, n.children > 0, open && n.children > 0)

    /** The path of a row, ready to paste. */
    fun pathOf(path: String): String = path

    /** The value at [path] as JSON text (pretty for containers, the raw token for a leaf; a string keeps its quotes). */
    fun valueAt(path: String): String? = byPath[path]?.element?.let { pretty(it, 0) }

    /** The plain text of a leaf (a string without quotes), else the JSON. */
    fun plainAt(path: String): String? = byPath[path]?.let { n -> if (n.element is JsonPrimitive && n.kind == JsonKind.String) (n.element as JsonPrimitive).content else pretty(n.element, 0) }

    val size: Int get() = nodes.size

    companion object {
        const val ROOT = "$"

        /** The tree of [text], or null when it is not JSON. */
        fun parse(text: String): JsonTree? {
            val root = try { Json.parseToJsonElement(text) } catch (e: Exception) { return null }
            val out = ArrayList<Node>()
            walk(root, ROOT, null, null, 0, out)
            return JsonTree(out)
        }

        private fun walk(e: JsonElement, path: String, parent: String?, key: String?, depth: Int, out: MutableList<Node>) {
            when (e) {
                is JsonObject -> {
                    out += Node(path, parent, key, depth, JsonKind.Object, "{${e.size}}", e, e.size)
                    for ((k, v) in e) walk(v, child(path, k), path, k, depth + 1, out)
                }
                is JsonArray -> {
                    out += Node(path, parent, key, depth, JsonKind.Array, "[${e.size}]", e, e.size)
                    e.forEachIndexed { i, v -> walk(v, "$path[$i]", path, i.toString(), depth + 1, out) }
                }
                is JsonNull -> out += Node(path, parent, key, depth, JsonKind.Null, "null", e, 0)
                is JsonPrimitive -> {
                    val kind = when { e.isString -> JsonKind.String; e.content == "true" || e.content == "false" -> JsonKind.Boolean; else -> JsonKind.Number }
                    out += Node(path, parent, key, depth, kind, if (e.isString) "\"${e.content}\"" else e.content, e, 0)
                }
            }
        }

        /** `$.a`, or `$["a b"]` when the key is not a plain identifier. */
        fun child(parent: String, key: String): String =
            if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' } && !key[0].isDigit()) "$parent.$key"
            else parent + "[\"" + key.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]"

        private fun pretty(e: JsonElement, depth: Int): String {
            val pad = "  ".repeat(depth + 1)
            val end = "  ".repeat(depth)
            return when (e) {
                is JsonObject -> if (e.isEmpty()) "{}" else e.entries.joinToString(",\n", "{\n", "\n$end}") { (k, v) -> "$pad\"$k\": ${pretty(v, depth + 1)}" }
                is JsonArray -> if (e.isEmpty()) "[]" else e.joinToString(",\n", "[\n", "\n$end]") { pad + pretty(it, depth + 1) }
                else -> e.toString()
            }
        }
    }
}

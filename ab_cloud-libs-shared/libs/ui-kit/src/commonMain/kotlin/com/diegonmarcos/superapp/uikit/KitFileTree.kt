package com.diegonmarcos.superapp.uikit

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Text

/**
 * #1459 THE shared file tree: a collapsible folder explorer (root > folders > files) as pure data plus Store-dense rows.
 * Callers hand in [Entry] (a file and the folders above it) and the open set; [rows] flattens that into what is drawn.
 * Pure (JVM-tested through the Cloud Browser suite): no Android types.
 */
object KitFileTree {
    /**
     * #1461 [Entry.type] of a placeholder entry: a folder that has nothing in it yet. It keeps the folder in the tree (a folder only exists
     * through an entry) and draws as a dim "[Entry.name]" line, but is not counted in the folder's files, bytes or newest time.
     */
    const val EMPTY_TYPE = "\u0000empty"

    /** One file: [dirs] are the folders from the root down (dirs[0] is the root, e.g. a site), [payload] is what a tap hands back. */
    data class Entry<T>(val dirs: List<String>, val name: String, val bytes: Long, val modified: Long, val type: String, val payload: T)

    sealed class Row<out T> {
        abstract val key: String
        abstract val depth: Int

        /** A folder: [files] counts every file below it, [bytes] their size, [modified] the newest of them. */
        data class Dir(override val key: String, override val depth: Int, val name: String, val open: Boolean, val files: Int, val bytes: Long, val modified: Long) : Row<Nothing>()
        data class Leaf<T>(override val key: String, override val depth: Int, val entry: Entry<T>) : Row<T>()
    }

    private class Node<T>(val name: String, val key: String) {
        val dirs = LinkedHashMap<String, Node<T>>()
        val files = ArrayList<Entry<T>>()
        var count = 0
        var bytes = 0L
        var modified = 0L
    }

    private fun <T> build(entries: List<Entry<T>>): LinkedHashMap<String, Node<T>> {
        val roots = LinkedHashMap<String, Node<T>>()
        for (e in entries) {
            if (e.dirs.isEmpty()) continue
            var level = roots
            var key = ""
            var node: Node<T>? = null
            val path = ArrayList<Node<T>>()
            for (d in e.dirs) {
                key = if (key.isEmpty()) d else "$key/$d"
                val k = key
                node = level.getOrPut(d) { Node(d, k) }
                path += node
                level = node.dirs
            }
            if (e.type != EMPTY_TYPE) for (n in path) { n.count++; n.bytes += e.bytes; if (e.modified > n.modified) n.modified = e.modified }
            node!!.files += e
        }
        return roots
    }

    /** The key of a folder, as [Row.key] and the open set use it: its dirs joined by "/". */
    fun keyOf(dirs: List<String>): String = dirs.joinToString("/")

    /**
     * The visible rows. Roots come in [rootOrder] (roots it does not name follow, A-Z); inside a folder the sub-folders come first,
     * then the files, both A-Z or, with [newestFirst], by newest. A folder's content shows only while its key is in [open].
     */
    fun <T> rows(entries: List<Entry<T>>, open: Set<String>, rootOrder: List<String> = emptyList(), newestFirst: Boolean = false, declaredOrder: Boolean = false): List<Row<T>> {
        val roots = build(entries)
        val out = ArrayList<Row<T>>()
        val ci = String.CASE_INSENSITIVE_ORDER
        fun walk(n: Node<T>, depth: Int) {
            val isOpen = n.key in open
            out += Row.Dir(n.key, depth, n.name, isOpen, n.count, n.bytes, n.modified)
            if (!isOpen) return
            val subs = if (declaredOrder) n.dirs.values.toList()
            else if (newestFirst) n.dirs.values.sortedWith(compareByDescending<Node<T>> { it.modified }.thenBy(ci) { it.name })
            else n.dirs.values.sortedWith(compareBy(ci) { it.name })
            subs.forEach { walk(it, depth + 1) }
            val fs = if (declaredOrder) n.files.toList()
            else if (newestFirst) n.files.sortedWith(compareByDescending<Entry<T>> { it.modified }.thenBy(ci) { it.name })
            else n.files.sortedWith(compareBy(ci) { it.name })
            fs.forEach { out += Row.Leaf(n.key + "/" + it.name + "#" + it.bytes + "#" + it.modified + "#" + out.size, depth + 1, it) }
        }
        val named = rootOrder.mapNotNull { roots[it] }
        (named + roots.values.filter { it !in named }.let { if (declaredOrder) it else it.sortedWith(compareBy(ci) { n -> n.name }) }).forEach { walk(it, 0) }
        return out
    }

    /** Every folder key that has content (what Expand all opens). */
    fun <T> containers(entries: List<Entry<T>>): Set<String> {
        val s = HashSet<String>()
        for (e in entries) for (i in e.dirs.indices) s += keyOf(e.dirs.subList(0, i + 1))
        return s
    }

    /** A file's type column: its extension, else the subtype of its MIME type, else "file". */
    fun typeOf(name: String, mime: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 6 && ext.all { it.isLetterOrDigit() }) return ext
        val sub = mime.substringAfter('/', "").substringBefore('+').substringBefore(';').trim().lowercase()
        return sub.ifEmpty { "file" }
    }
}

/** The ink of a [kitFileTree]. */
data class KitFileTreeInk(val ink: Color, val dim: Color, val accent: Color)

/**
 * The tree's rows in Cloud Store's dense style (KitStoreRowLine): a folder row is glyph, name, count and size; a file row is
 * name, then size, type and modified time. A tap on a folder calls [onToggle] with its key, on a file [onOpen] with its entry.
 */
fun <T> LazyListScope.kitFileTree(
    rows: List<KitFileTree.Row<T>>, ink: KitFileTreeInk, size: (Long) -> String, date: (Long) -> String,
    onToggle: (String) -> Unit, onOpen: (KitFileTree.Entry<T>) -> Unit, tagPrefix: String = "tree",
) {
    items(rows.size, key = { rows[it].key }) { i ->
        when (val r = rows[i]) {
            is KitFileTree.Row.Dir -> KitStoreRowLine(Modifier.testTag("$tagPrefix:dir:${r.key}"), onClick = { onToggle(r.key) }) {
                Spacer(Modifier.width(KitStoreRow.step(14) * r.depth))
                KitStoreDot(if (r.open) "▾" else "▸", ink.accent)
                KitStoreName(r.name, Modifier.weight(1f), ink.ink)
                Cell("${r.files} · ${size(r.bytes)}", ink.dim, 120)
                Cell(date(r.modified), ink.dim, 96)
            }
            is KitFileTree.Row.Leaf -> if (r.entry.type == KitFileTree.EMPTY_TYPE) KitStoreRowLine(Modifier.testTag("$tagPrefix:empty:${r.key}")) {
                Spacer(Modifier.width(KitStoreRow.step(14) * r.depth + KitStoreRow.step(14)))
                KitStoreCaption(r.entry.name, Modifier.weight(1f), ink.dim)
            } else KitStoreRowLine(Modifier.testTag("$tagPrefix:file:${r.entry.name}"), onClick = { onOpen(r.entry) }) {
                Spacer(Modifier.width(KitStoreRow.step(14) * r.depth + KitStoreRow.step(14)))
                Text(r.entry.name, color = ink.ink, fontSize = KitStoreRow.body, maxLines = 1, softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Cell(size(r.entry.bytes), ink.dim, 52)
                Cell(r.entry.type, ink.dim, 40)
                Cell(date(r.entry.modified), ink.dim, 96)
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun RowScope.Cell(text: String, color: Color, widthDp: Int) =
    KitStoreCaption(text, Modifier.width((widthDp * KitStoreRow.TEXT_SCALE + 8).dp).padding(start = KitStoreRow.tight), color, align = TextAlign.End)

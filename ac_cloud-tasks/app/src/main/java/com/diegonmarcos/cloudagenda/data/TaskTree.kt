package com.diegonmarcos.cloudagenda.data

import com.diegonmarcos.superapp.uikit.KitFileTree
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1461 One tab's tree for the shared KitFileTree: every node of the REAL structure (Structure.paths) is a folder, even when it has no
 * items (it then holds one "Nothing here yet" placeholder, KitFileTree.EMPTY_TYPE), and the mock items hang under the node their
 * `subject` names (the node's path joined by "/"). Pure.
 */
object TaskTree {
    const val EMPTY_NAME = "Nothing here yet"

    fun key(path: List<String>) = path.joinToString("/")

    fun entries(structure: Structure, items: List<MockTask>, kind: String, today: LocalDate): List<KitFileTree.Entry<MockTask?>> {
        val byNode = items.filter { it.kind == kind }.groupBy { it.subject }
        val paths = structure.paths(kind)
        val parents = paths.mapNotNull { it.dropLast(1).takeIf { p -> p.isNotEmpty() } }.map(::key).toSet()
        return paths.flatMap { path ->
            val rows = byNode[key(path)].orEmpty().sortedWith(compareBy({ it.startDay == null }, { it.startDay }, { it.title }))
            val leaves = rows.map { t ->
                KitFileTree.Entry<MockTask?>(path, t.title, 0L, t.start(today)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0L, t.status.label, t)
            }
            // A folder with sub-folders needs no placeholder: its children are its content (and each of those has its own).
            if (leaves.isEmpty() && key(path) !in parents) listOf(KitFileTree.Entry<MockTask?>(path, EMPTY_NAME, 0L, 0L, KitFileTree.EMPTY_TYPE, null)) else leaves
        }
    }

    /** The top-level nodes in declared order (pass with declaredOrder = true so the tree keeps the declaration's order). */
    fun rootOrder(structure: Structure, kind: String): List<String> = structure.tree(kind).map { it.label }
}

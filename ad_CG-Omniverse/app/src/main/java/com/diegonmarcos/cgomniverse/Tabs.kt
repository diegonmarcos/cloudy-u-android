package com.diegonmarcos.cgomniverse

/**
 * Galaxy's tabs, browser style: the worlds open now (by Store id, in tab order) and the one shown. Opening a world adds its tab
 * at the end and selects it, or only selects it when it is open already; closing a tab selects its right neighbour (else the left
 * one). The state is persisted as one string ([encode]/[decode]); the very first launch (nothing stored) opens one tab, the world
 * at [DEFAULT_URL]. Pure, so the JVM suite and its mutants cover it.
 */
object Tabs {
    const val DEFAULT_URL = "https://floor796.com"

    data class State(val open: List<String>, val selected: String?)

    val EMPTY = State(emptyList(), null)

    /** The first-launch state: one tab, the Store world at [DEFAULT_URL] (none when the catalogue lacks it). */
    fun default(worlds: List<World>): State {
        val id = worlds.firstOrNull { it.url.trimEnd('/') == DEFAULT_URL }?.id ?: return EMPTY
        return State(listOf(id), id)
    }

    fun open(s: State, id: String): State = if (id in s.open) s.copy(selected = id) else State(s.open + id, id)

    fun close(s: State, id: String): State {
        val i = s.open.indexOf(id)
        if (i < 0) return s
        val rest = s.open - id
        val sel = if (s.selected != id) s.selected else rest.getOrNull(i) ?: rest.lastOrNull()
        return State(rest, sel)
    }

    /** `selected|id1,id2,...` (ids are slugs: no `|` or `,`). */
    fun encode(s: State): String = (s.selected ?: "") + "|" + s.open.joinToString(",")

    /** Null (never stored) is the first launch: [default]. Ids no longer in the catalogue drop out; a lost selection falls back to the first tab. */
    fun decode(stored: String?, worlds: List<World>): State {
        if (stored == null) return default(worlds)
        val known = worlds.map { it.id }.toSet()
        val sel = stored.substringBefore('|')
        val open = stored.substringAfter('|', "").split(',').filter { it.isNotEmpty() && it in known }.distinct()
        return State(open, sel.takeIf { it in open } ?: open.firstOrNull())
    }
}

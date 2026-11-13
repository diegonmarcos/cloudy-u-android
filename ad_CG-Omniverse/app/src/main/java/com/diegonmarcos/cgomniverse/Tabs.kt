package com.diegonmarcos.cgomniverse

/**
 * Galaxy's tabs, browser style: the worlds open now (by Store id, in tab order) and the one shown. Opening a world adds its tab
 * at the end and selects it, or only selects it when it is open already; closing a tab selects its right neighbour (else the left
 * one). The state is persisted as one string ([encode]/[decode]); the very first launch (nothing stored) opens the declared
 * default tabs (build.json::galaxy_tabs, #1711: Cyber City Orion, floor796, Messenger) or, with none declared, one tab, the Store
 * world [DEFAULT_WORLD] (floor796). Every address lives in data/fwa.md, never in Kotlin. Pure, so the JVM suite and its mutants
 * cover it.
 */
object Tabs {
    const val DEFAULT_WORLD = "floor796"

    data class State(val open: List<String>, val selected: String?)

    val EMPTY = State(emptyList(), null)

    /** The ids of a baked list (`id1,id2,...`), in order, without blanks or repeats. */
    fun ids(baked: String): List<String> = baked.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * The first-launch state: the [declared] default tabs the catalogue holds, in their declared order, the first one shown;
     * with none declared, one tab, the Store world [DEFAULT_WORLD]. No tab when the catalogue holds none of them.
     */
    fun default(worlds: List<World>, declared: List<String> = emptyList()): State {
        val known = worlds.map { it.id }.toSet()
        val tabs = declared.ifEmpty { listOf(DEFAULT_WORLD) }.filter { it in known }.distinct()
        return State(tabs, tabs.firstOrNull())
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
    fun decode(stored: String?, worlds: List<World>, declared: List<String> = emptyList()): State {
        if (stored == null) return default(worlds, declared)
        val known = worlds.map { it.id }.toSet()
        val sel = stored.substringBefore('|')
        val open = stored.substringAfter('|', "").split(',').filter { it.isNotEmpty() && it in known }.distinct()
        return State(open, sel.takeIf { it in open } ?: open.firstOrNull())
    }

    /**
     * #1711 the [declared] default tabs a device was never given are added ONCE to the tabs it already has ([given] = the declared
     * ids it was given before). The declared tabs stand first, in their declared order (the ones already open and the new ones);
     * the user's other tabs follow in their own order; the selection stays (the first tab when there was none). A declared tab the
     * user closed is never brought back: it is in [given].
     */
    fun seed(s: State, declared: List<String>, given: Set<String>, worlds: List<World>): State {
        val known = worlds.map { it.id }.toSet()
        val fresh = declared.filter { it in known && it !in given && it !in s.open }.distinct()
        if (fresh.isEmpty()) return s
        val front = declared.filter { it in s.open || it in fresh }.distinct()
        val tabs = front + s.open.filter { it !in front }
        return State(tabs, s.selected ?: tabs.firstOrNull())
    }

    /** What [restore] hands back: the tabs to show, and the declared ids this device now counts as given (`id1,id2,...`). */
    data class Restored(val state: State, val seeded: String)

    /**
     * The tabs at app start. [stored] = the saved tabs ([encode]; null = the first launch: [default]); [seededStored] = the
     * declared ids given before (null = never recorded: a device from before the declaration was given [DEFAULT_WORLD] only).
     */
    fun restore(stored: String?, seededStored: String?, declared: List<String>, worlds: List<World>): Restored {
        val given: Set<String> = if (seededStored != null) ids(seededStored).toSet() else if (stored == null) emptySet() else setOf(DEFAULT_WORLD)
        val state = if (stored == null) default(worlds, declared) else seed(decode(stored, worlds, declared), declared, given, worlds)
        return Restored(state, (given + declared).joinToString(separator = ","))
    }
}

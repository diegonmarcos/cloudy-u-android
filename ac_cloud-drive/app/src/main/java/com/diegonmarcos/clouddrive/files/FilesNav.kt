package com.diegonmarcos.clouddrive.files

/**
 * #1696 navigation for a NON-ROOT app, PURE Kotlin (FilesNavTest proves it). The Explorer's world is the set of
 * accessible roots (storage volumes, app dirs, declared places, SAF-reached paths), not the raw filesystem parent
 * chain: Up never walks into a folder this app cannot list (/storage/emulated, /storage, /), it stops at the root
 * boundary and says why; a restored path that is no longer listable falls back to Home; the breadcrumb starts at
 * the root the location sits in. Every disk probe is passed in as [canList], so nothing here touches IO.
 */
object FilesNav {

    enum class StopReason { TOP, UNREADABLE, ROOT_BOUNDARY }

    sealed class UpDecision {
        data class Go(val target: Location) : UpDecision()
        data class Stop(val reason: StopReason) : UpDecision()
    }

    private fun norm(p: String): String = p.trimEnd('/').ifEmpty { "/" }

    /** The deepest root [path] sits in (itself or an ancestor), or null when it is outside every root. */
    fun rootOf(path: String, roots: Collection<String>): String? {
        val p = norm(path)
        return roots.map(::norm).filter { r -> p == r || p.startsWith(if (r == "/") "/" else "$r/") }.maxByOrNull { it.length }
    }

    fun isRoot(path: String, roots: Collection<String>): Boolean = roots.any { norm(it) == norm(path) }

    /** Up, checked: the parent must be listable for this app, and a root's parent only when it sits in another root. */
    fun up(loc: Location, roots: Collection<String>, canList: (String) -> Boolean): UpDecision {
        val parent = loc.parentOrNull() ?: return UpDecision.Stop(StopReason.TOP)
        // Inside an archive the parent is in the zip: always readable once the zip opened.
        if (parent is Location.Archive) return UpDecision.Go(parent)
        val local = parent as Location.Local
        val here = (loc as? Location.Local)?.path ?: (loc as Location.Archive).zipPath
        if (loc is Location.Local && isRoot(here, roots) && rootOf(local.path, roots) == null) return UpDecision.Stop(StopReason.ROOT_BOUNDARY)
        if (!safe { canList(local.path) }) return UpDecision.Stop(if (rootOf(local.path, roots) == null) StopReason.ROOT_BOUNDARY else StopReason.UNREADABLE)
        return UpDecision.Go(local)
    }

    /** Whether a location can be shown: a folder this app lists, or an archive whose zip sits in one. */
    fun reachable(loc: Location, canList: (String) -> Boolean): Boolean = when (loc) {
        is Location.Local -> safe { canList(loc.path) }
        is Location.Archive -> Location.Local(loc.zipPath).parent()?.let { safe { canList(it.path) } } ?: false
    }

    /**
     * #1696 recover a restored state: every tab whose location this app can no longer list goes Home, and unreachable
     * history entries are dropped (Back never lands in them). Returns the new state and how many tabs were moved.
     */
    fun recover(s: FilesUiState, home: Location.Local, canList: (String) -> Boolean): Pair<FilesUiState, Int> {
        var moved = 0
        fun fix(p: PaneState): PaneState {
            val tabs = p.tabs.map { t ->
                val ok = reachable(t.location, canList)
                if (!ok) moved++
                t.copy(location = if (ok) t.location else home, history = t.history.filter { reachable(it, canList) })
            }
            return if (tabs == p.tabs) p else p.copy(tabs = tabs, selection = emptySet())
        }
        return s.copy(a = fix(s.a), b = fix(s.b)) to moved
    }

    /** Home from any state: the active tab of [id] opens [home] (its history keeps where it was). */
    fun home(s: FilesUiState, id: PaneId, home: Location.Local): FilesUiState =
        if (s.pane(id).location == home) s else FilesReducer.open(s, id, home)

    /** The crumbs from the root the location sits in (its label prints first); all crumbs when it is in none. */
    fun crumbs(loc: Location, roots: Collection<String>): List<Location> {
        val all = loc.crumbs()
        val path = (loc as? Location.Local)?.path ?: (loc as Location.Archive).zipPath
        // The SHALLOWEST root holding the location: the breadcrumb starts at the volume, not at a declared subfolder.
        val p = norm(path)
        val root = roots.map(::norm).filter { r -> r != "/" && (p == r || p.startsWith("$r/")) }.minByOrNull { it.length } ?: return all
        val i = all.indexOfFirst { it is Location.Local && norm(it.path) == root }
        return if (i <= 0) all else all.drop(i)
    }

    private inline fun safe(f: () -> Boolean): Boolean = try { f() } catch (_: Throwable) { false }
}

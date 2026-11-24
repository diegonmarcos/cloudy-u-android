package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.bottomnav.ChromeScrollState
import com.diegonmarcos.superapp.bottomnav.NavGroup
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection

/**
 * #1891 one node of the library tree: a parent (Home, Modern Science, Arcanum Science, Projects), a hub, a topic, a dataset page (a topic's
 * tab), a Chronos simulator, a project or a view (Home, Graph). [page] is the side-menu page a tap opens (null for a parent heading) and
 * [tab] the dataset page selected on it. [words], [chars], [updated], [tags] and [path] are what the dataset says about it (zero/blank when
 * nothing). Pure, so the JVM suite covers the side menu and the Home visualiser without drawing.
 */
data class TreeNode(
    val id: String,
    val label: String,
    val icon: String,
    val level: Int,
    val parent: String?,
    val children: List<String>,
    val kind: Kind,
    val group: String,
    val page: String? = null,
    val tab: String? = null,
    val tags: List<String> = emptyList(),
    val words: Int = 0,
    val chars: Int = 0,
    val updated: String = "",
    val path: String = "",
) {
    enum class Kind { PARENT, HUB, TOPIC, PAGE, SIMULATOR, PROJECT, VIEW }
}

/** The tree in depth-first order (a node before its children, siblings in declared order). */
class WikiTree(val nodes: List<TreeNode>) {
    private val byId = nodes.associateBy { it.id }
    fun node(id: String?): TreeNode? = if (id == null) null else byId[id]
    val roots: List<TreeNode> get() = nodes.filter { it.parent == null }
    fun kids(id: String): List<TreeNode> = node(id)?.children.orEmpty().mapNotNull { byId[it] }

    /** The ids above [id], outermost first. */
    fun ancestors(id: String): List<String> = generateSequence(node(id)?.parent) { node(it)?.parent }.toList().asReversed()

    /** The labels of [id]'s ancestors joined by " / " (blank for a root): the Table's parent path. */
    fun parentPath(id: String): String = ancestors(id).mapNotNull { node(it)?.label }.joinToString(" / ")

    /** Every node below [id], at any depth. */
    fun descendants(id: String): Int = kids(id).sumOf { 1 + descendants(it.id) }

    val maxLevel: Int get() = nodes.maxOfOrNull { it.level } ?: 0
}

object LibraryTree {
    /** The Home page and its parent: the library's index visualiser, first in the side menu. */
    const val HOME = "home"
    const val HOME_ICON = "home"

    /** A parent heading's node id (the group of build.json::ui). */
    const val GROUP = "group:"

    /**
     * [section] with Home first: a page of its own at the same level as the declared parents (Modern Science, Arcanum Science, Projects),
     * its own group ahead of theirs. Nothing declared is touched; idempotent.
     */
    fun withHome(section: NavSection, label: String): NavSection {
        if (section.pages.any { it.id == HOME }) return section
        val home = NavPage(HOME, label, HOME_ICON)
        return section.copy(
            pages = listOf(home) + section.pages,
            groups = if (section.groups.isEmpty()) section.groups else listOf(NavGroup(HOME, label, listOf(home), HOME_ICON)) + section.groups,
        )
    }

    /**
     * The library tree of [section] (the side menu's declaration): each declared group a parent node, its pages below it, a hub's topics
     * below the hub, and each topic's dataset pages ([data], its tabs) below the topic. A group that only holds a page of its own id (Home)
     * is that page, at the parents' level. [chronos] (the Home visualiser) hangs the Chronos simulators under Chronos; the side menu leaves
     * them out (#1831, they are Chronos's own tabs). [home] false leaves Home out (the visualiser's index of the library).
     */
    fun build(section: NavSection, data: WikiData?, chronos: ChronosTree? = null, home: Boolean = true): WikiTree {
        val out = ArrayList<TreeNode>()
        fun add(n: TreeNode): Int { out += n; return out.size - 1 }
        fun setKids(at: Int, kids: List<String>) { out[at] = out[at].copy(children = kids) }

        fun sim(p: ChronosPage, level: Int, parent: String, group: String): String {
            val at = add(TreeNode(p.id, p.label, p.icon, level, parent, emptyList(), TreeNode.Kind.SIMULATOR, group, page = p.id, path = p.asset.substringBeforeLast("/")))
            setKids(at, p.children.mapNotNull { chronos?.page(it) }.map { sim(it, level + 1, p.id, group) })
            return p.id
        }

        fun dataPage(pg: WikiPage, level: Int, parent: String, group: String): String {
            add(TreeNode(pg.id, pg.title, "description", level, parent, emptyList(), TreeNode.Kind.PAGE, group, page = pg.topic, tab = pg.id,
                tags = pg.tags, words = words(pg.md), chars = pg.md.length, updated = pg.updated, path = pg.path))
            return pg.id
        }

        fun page(p: NavPage, level: Int, parent: String?, group: String): String {
            val topic = data?.topic(p.id)
            val hub = data?.hub(p.id)
            val kind = when {
                p.id == HOME || p.id == Decl.PAGE_GRAPH -> TreeNode.Kind.VIEW
                topic != null || p.id == Chronos.PARENT -> TreeNode.Kind.TOPIC
                p.pages.isNotEmpty() -> TreeNode.Kind.HUB
                else -> TreeNode.Kind.PROJECT
            }
            val intro = topic?.intro ?: hub?.intro.orEmpty()
            val at = add(TreeNode(p.id, p.label, p.icon, level, parent, emptyList(), kind, group, page = p.id,
                tags = topic?.pages.orEmpty().flatMap { it.tags }.distinct(), words = words(intro), chars = intro.length, path = topic?.path.orEmpty()))
            val kids = ArrayList<String>()
            if (p.id == Chronos.PARENT && chronos != null) chronos.pages.filter { it.parent == null }.forEach { kids += sim(it, level + 1, p.id, group) }
            else p.pages.forEach { kids += page(it, level + 1, p.id, group) }
            topic?.pages?.forEach { kids += dataPage(it, level + 1, p.id, group) }
            setKids(at, kids)
            return p.id
        }

        val groups = section.rowGroups()
        for (g in groups) {
            if (g.pages.size == 1 && g.pages[0].id == g.id) {
                if (g.id == HOME && !home) continue
                page(g.pages[0], 0, null, g.id)
                continue
            }
            val gid = GROUP + g.id
            val intro = data?.hub(g.id)?.intro.orEmpty()
            val at = add(TreeNode(gid, g.label, g.icon, 0, null, emptyList(), TreeNode.Kind.PARENT, g.id, words = words(intro), chars = intro.length))
            setKids(at, g.pages.map { page(it, 1, gid, g.id) })
        }
        return WikiTree(out)
    }

    fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotBlank() }
}

/**
 * #1891 the side menu's state: which nodes are open ([expanded]), which parents the filter hides ([hidden], only Modern Science and
 * Arcanum Science can be), and the search [query]. Every change is a pure function of the tree, so the JVM suite drives it.
 */
data class MenuState(val expanded: Set<String> = emptySet(), val hidden: Set<String> = emptySet(), val query: String = "")

object WikiMenu {
    /** The open menu takes this share of the screen width. */
    const val WIDTH_FRACTION = 0.75f

    /** The parents the Filter toggles (the groups of build.json::ui); Home and Projects always stay. */
    val FILTERABLE = listOf("modern", "arcanum")

    enum class Presence { Rail, Open }

    /**
     * How the menu is on screen: what the user chose, and NOTHING else. The page's scroll ([pageScroll], the chrome's scroll machine that
     * libs:ui-nav-bars's side menu follows, HIDDEN taking it away) is ignored: scrolling the page never collapses the menu.
     */
    fun presence(open: Boolean, @Suppress("UNUSED_PARAMETER") pageScroll: ChromeScrollState): Presence = if (open) Presence.Open else Presence.Rail

    /** The open menu's width for a screen [screenWidth] wide (any unit). */
    fun width(screenWidth: Float): Float = screenWidth * WIDTH_FRACTION

    fun toggle(state: MenuState, id: String): MenuState =
        state.copy(expanded = if (id in state.expanded) state.expanded - id else state.expanded + id)

    /** Every node with children open: the whole tree shows. */
    fun expandAll(tree: WikiTree): Set<String> = tree.nodes.filter { it.children.isNotEmpty() }.map { it.id }.toSet()

    /** The tree shown [levels] levels below the parents: the nodes above that depth open, the rest closed. */
    fun expandLevels(tree: WikiTree, levels: Int): Set<String> =
        tree.nodes.filter { it.children.isNotEmpty() && it.level < levels }.map { it.id }.toSet()

    fun collapseAll(): Set<String> = emptySet()

    /** Folds the deepest open level, [levels] times: Collapse 1 level closes the innermost open nodes, 2 levels the two innermost. */
    fun collapseLevels(tree: WikiTree, expanded: Set<String>, levels: Int): Set<String> {
        var open = expanded
        repeat(levels) {
            val deepest = open.mapNotNull { tree.node(it)?.level }.maxOrNull() ?: return open
            open = open.filter { (tree.node(it)?.level ?: -1) != deepest }.toSet()
        }
        return open
    }

    /** Hides [group] when shown, shows it when hidden; only [FILTERABLE] parents can be hidden. */
    fun toggleFilter(state: MenuState, group: String): MenuState = when {
        group !in FILTERABLE -> state
        group in state.hidden -> state.copy(hidden = state.hidden - group)
        else -> state.copy(hidden = state.hidden + group)
    }

    /** One drawn row: the node, whether it is open, and the [match] of the search in its label (null: not a match) to highlight. */
    data class Row(val node: TreeNode, val open: Boolean, val match: IntRange?)

    /** Where [query] sits in [label] (case-insensitive), or null. */
    fun match(label: String, query: String): IntRange? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val at = label.indexOf(q, ignoreCase = true)
        return if (at < 0) null else at until at + q.length
    }

    /**
     * The rows the menu draws for [state]: the parents the filter shows, depth-first, a node's children only when it is open. With a search
     * the expansion is ignored: every node whose label matches shows with ALL its ancestors (so the path to it is visible), and nothing else.
     */
    fun rows(tree: WikiTree, state: MenuState): List<Row> {
        val shown = tree.nodes.filter { it.group !in state.hidden }
        val q = state.query.trim()
        if (q.isNotEmpty()) {
            val hits = shown.filter { match(it.label, q) != null }.map { it.id }
            val keep = (hits + hits.flatMap { tree.ancestors(it) }).toSet()
            return shown.filter { it.id in keep }.map { Row(it, it.children.any { c -> c in keep }, match(it.label, q)) }
        }
        val out = ArrayList<Row>()
        fun walk(n: TreeNode) {
            val open = n.id in state.expanded
            out += Row(n, open, null)
            if (open) tree.kids(n.id).forEach(::walk)
        }
        tree.roots.filter { it.group !in state.hidden }.forEach(::walk)
        return out
    }

    /** The nodes to open so [pageId] (and the tab [tab]) shows in the menu: its ancestors. */
    fun reveal(tree: WikiTree, state: MenuState, pageId: String?): MenuState {
        val id = pageId ?: return state
        if (tree.node(id) == null) return state
        return state.copy(expanded = state.expanded + tree.ancestors(id))
    }
}

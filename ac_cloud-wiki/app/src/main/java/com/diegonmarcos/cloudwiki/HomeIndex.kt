package com.diegonmarcos.cloudwiki

import com.diegonmarcos.superapp.graphview.GEdge
import com.diegonmarcos.superapp.graphview.GNode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * #1891 Home, the index visualiser of the whole library: one [WikiTree] (every parent, hub, topic, dataset page, Chronos simulator and
 * project) drawn four ways. The models are pure so the JVM suite holds each of them to the tree: List (indented rows), Table (one row per
 * node), Graph (node-link of parent and child) and 3D (planetary: each parent a body, its children orbiting it, recursively).
 */
object HomeIndex {
    enum class Mode(val id: String, val icon: String) { Outline("list", "checklist"), Table("table", "table_chart"), Graph("graph", "hub"), Planet("planet", "public") }

    /**
     * #1984 the view tabs in the declared order ([csv] = build.json::ui.home.view_modes): an unknown or repeated id is dropped and a view the
     * declaration forgot is appended, so every view always stays.
     */
    fun order(csv: String): List<Mode> {
        val named = csv.split(',').mapNotNull { id -> Mode.entries.firstOrNull { it.id == id.trim() } }.distinct()
        return named + Mode.entries.filter { it !in named }
    }

    /** #1984 the view selected at a cold start: the declared [id] when it is one of [modes], else the first tab. */
    fun default(id: String, modes: List<Mode>): Mode = modes.firstOrNull { it.id == id } ?: modes.first()

    /** #1984 the page a cold start opens: the declared [start] when [pages] holds it, else the first page. */
    fun startPage(start: String, pages: List<String>): String = pages.firstOrNull { it == start } ?: pages.first()

    /** One Table row: every column the dataset can fill for the node. */
    data class Row(
        val id: String,
        val title: String,
        val parentPath: String,
        val level: Int,
        val children: Int,
        val descendants: Int,
        val words: Int,
        val chars: Int,
        val updated: String,
        val tags: List<String>,
        val kind: TreeNode.Kind,
        val path: String,
    )

    enum class Column { Title, Parent, Level, Children, Words, Size, Updated, Tags, Kind, Path }

    /** One row per node of [tree], in the tree's depth-first order. */
    fun rows(tree: WikiTree): List<Row> = tree.nodes.map { n ->
        Row(n.id, n.label, tree.parentPath(n.id), n.level, n.children.size, tree.descendants(n.id), n.words, n.chars, n.updated, n.tags, n.kind, n.path)
    }

    /** [rows] ordered by [column] ([ascending] or not); ties keep the tree order. */
    fun sorted(rows: List<Row>, column: Column?, ascending: Boolean = true): List<Row> {
        if (column == null) return rows
        val c: Comparator<Row> = when (column) {
            Column.Title -> compareBy { it.title.lowercase() }
            Column.Parent -> compareBy { it.parentPath.lowercase() }
            Column.Level -> compareBy { it.level }
            Column.Children -> compareBy { it.children }
            Column.Words -> compareBy { it.words }
            Column.Size -> compareBy { it.chars }
            Column.Updated -> compareBy { it.updated }
            Column.Tags -> compareBy { it.tags.size }
            Column.Kind -> compareBy { it.kind.ordinal }
            Column.Path -> compareBy { it.path }
        }
        return rows.sortedWith(if (ascending) c else c.reversed())
    }

    /** A byte count as the Table prints it: 812, 1.4k, 2.1M. */
    fun size(chars: Int): String = when {
        chars < 1000 -> chars.toString()
        chars < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fk", chars / 1000.0)
        else -> String.format(java.util.Locale.ROOT, "%.1fM", chars / 1_000_000.0)
    }

    /** The graph's nodes: one per tree node, clustered by its parent group, carrying its tags. */
    fun graphNodes(tree: WikiTree): List<GNode> = tree.nodes.map { GNode(it.id, it.label, it.group, it.tags) }

    /** The graph's edges: one LINK edge per parent and child. */
    fun graphEdges(tree: WikiTree): List<GEdge> = tree.nodes.flatMap { n -> n.children.map { GEdge(n.id, it, GEdge.Kind.LINK) } }
}

/** #1891 one body of the planetary view: where it is now, its size, the body it orbits and the radius of that orbit. */
data class Body(
    val id: String,
    val label: String,
    val level: Int,
    val group: String,
    val parent: String?,
    val x: Double,
    val y: Double,
    val z: Double,
    val radius: Double,
    val orbit: Double,
)

/**
 * #1891 the planetary layout: the library a star at the centre, each parent orbiting it, each node's children orbiting the node, recursively.
 * An orbit's radius shrinks by [SHRINK] per level and grows with the number of moons so they never touch; each level's plane tilts the other
 * way. [t] (seconds) turns every orbit, inner ones faster. Pure: the same tree and [t] always give the same bodies.
 */
object Planetary {
    const val ROOT_ORBIT = 420.0
    const val SHRINK = 0.36
    const val ROOT_RADIUS = 26.0
    const val TILT = 0.42

    fun bodyRadius(level: Int): Double = max(2.0, ROOT_RADIUS * Math.pow(0.55, level.toDouble()))

    /** The orbit radius of the children of a node at [level] with [count] of them: wide enough for them to sit apart. */
    fun orbitRadius(level: Int, count: Int): Double {
        val base = ROOT_ORBIT * Math.pow(SHRINK, (level + 1).toDouble())
        val needed = count * bodyRadius(level + 1) * 3.0 / (2 * PI)
        return max(base, needed)
    }

    fun layout(tree: WikiTree, t: Double = 0.0): List<Body> {
        val out = ArrayList<Body>(tree.nodes.size)
        fun place(ids: List<String>, level: Int, parent: String?, cx: Double, cy: Double, cz: Double) {
            if (ids.isEmpty()) return
            val r = if (parent == null) max(ROOT_ORBIT, orbitRadius(-1, ids.size)) else orbitRadius(level - 1, ids.size)
            val tilt = if (level % 2 == 0) TILT else -TILT
            val speed = 0.08 * (level + 1)
            ids.forEachIndexed { i, id ->
                val n = tree.node(id) ?: return@forEachIndexed
                val a = 2 * PI * i / ids.size + t * speed
                val lx = cos(a) * r
                val lz = sin(a) * r
                // the orbit's plane turned about the x axis by the level's tilt
                val x = cx + lx
                val y = cy + lz * sin(tilt)
                val z = cz + lz * cos(tilt)
                out += Body(n.id, n.label, n.level, n.group, parent, x, y, z, bodyRadius(level), r)
                place(n.children, level + 1, n.id, x, y, z)
            }
        }
        place(tree.roots.map { it.id }, 0, null, 0.0, 0.0, 0.0)
        return out
    }

    /** [steps] points of the orbit of radius [r] that the bodies of [level] run around a parent at ([cx], [cy], [cz]): the ring the view draws. */
    fun ring(cx: Double, cy: Double, cz: Double, r: Double, level: Int, steps: Int = 48): List<Triple<Double, Double, Double>> {
        val tilt = if (level % 2 == 0) TILT else -TILT
        return (0..steps).map { i ->
            val a = 2 * PI * i / steps
            Triple(cx + cos(a) * r, cy + sin(a) * r * sin(tilt), cz + sin(a) * r * cos(tilt))
        }
    }

    /** The extent of [bodies]: the largest distance of any body's edge from the centre. */
    fun extent(bodies: List<Body>): Double = bodies.maxOfOrNull { Math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z) + it.radius } ?: 1.0
}

/** #1891 the orbit camera of the planetary view (the approach of #1861's Inventory 3D, Compose Canvas): yaw turns, pitch tilts, zoom scales. */
data class PlanetCamera(val yawDeg: Double = 30.0, val pitchDeg: Double = 25.0, val zoom: Double = 1.0, val cx: Double = 0.0, val cy: Double = 0.0) {
    private val yaw get() = yawDeg * PI / 180
    private val pitch get() = pitchDeg * PI / 180

    /** Screen x, screen y (down) and nearness (larger is closer to the viewer) of a world point. */
    fun project(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        val x1 = x * cos(yaw) - z * sin(yaw)
        val z1 = x * sin(yaw) + z * cos(yaw)
        return Triple(cx + zoom * x1, cy + zoom * (z1 * sin(pitch) - y * cos(pitch)), z1 * cos(pitch) + y * sin(pitch))
    }

    /** The bodies far to near, so the painter draws the near ones over the far ones. */
    fun sorted(bodies: List<Body>): List<Body> = bodies.sortedBy { project(it.x, it.y, it.z).third }

    /** The id of the nearest body under the screen point (within [slop] px of its disc), or null. */
    fun pick(bodies: List<Body>, px: Double, py: Double, slop: Double = 10.0): String? {
        var best: String? = null; var near = Double.NEGATIVE_INFINITY
        for (b in bodies) {
            val (sx, sy, d) = project(b.x, b.y, b.z)
            if (hypot(sx - px, sy - py) <= b.radius * zoom + slop && d > near) { near = d; best = b.id }
        }
        return best
    }
}

package com.diegonmarcos.superapp.jsoncanvas

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Pt(val x: Double, val y: Double)

data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
}

/** screen = world * scale + (tx, ty). */
data class ViewTransform(val scale: Double, val tx: Double, val ty: Double) {
    fun toScreen(p: Pt) = Pt(p.x * scale + tx, p.y * scale + ty)
    fun toWorld(sx: Double, sy: Double) = Pt((sx - tx) / scale, (sy - ty) / scale)
}

/** A cubic Bezier between two node anchors, with the sides it leaves and enters by. */
data class EdgeCurve(val p0: Pt, val c1: Pt, val c2: Pt, val p3: Pt, val fromSide: Side, val toSide: Side) {
    fun at(t: Double): Pt {
        val u = 1 - t
        return Pt(
            u * u * u * p0.x + 3 * u * u * t * c1.x + 3 * u * t * t * c2.x + t * t * t * p3.x,
            u * u * u * p0.y + 3 * u * u * t * c1.y + 3 * u * t * t * c2.y + t * t * t * p3.y,
        )
    }
    val mid: Pt get() = at(0.5)
    /** Unit direction of travel at the start / the end, for arrow heads. */
    val startDir: Pt get() = unit(p0, c1.takeIf { it != p0 } ?: p3)
    val endDir: Pt get() = unit(c2.takeIf { it != p3 } ?: p0, p3)
    private fun unit(a: Pt, b: Pt): Pt { val dx = b.x - a.x; val dy = b.y - a.y; val l = sqrt(dx * dx + dy * dy); return if (l == 0.0) Pt(1.0, 0.0) else Pt(dx / l, dy / l) }
}

object CanvasGeometry {
    const val MIN_SCALE = 0.05
    const val MAX_SCALE = 4.0

    /** The box around every node; null for an empty canvas. */
    fun bounds(c: Canvas): Bounds? = if (c.nodes.isEmpty()) null else Bounds(
        c.nodes.minOf { it.x }, c.nodes.minOf { it.y }, c.nodes.maxOf { it.right }, c.nodes.maxOf { it.bottom },
    )

    /** The transform that shows all of [b] inside a [viewW] x [viewH] view with [padding] pixels around it, centred; never zooms in past [maxFit]. */
    fun fit(b: Bounds?, viewW: Double, viewH: Double, padding: Double = 24.0, maxFit: Double = 1.5): ViewTransform {
        if (b == null || viewW <= 0 || viewH <= 0) return ViewTransform(1.0, 0.0, 0.0)
        val w = max(b.width, 1.0); val h = max(b.height, 1.0)
        val s = min(min((viewW - 2 * padding) / w, (viewH - 2 * padding) / h), maxFit).coerceIn(MIN_SCALE, MAX_SCALE)
        val cx = (b.minX + b.maxX) / 2; val cy = (b.minY + b.maxY) / 2
        return ViewTransform(s, viewW / 2 - cx * s, viewH / 2 - cy * s)
    }

    /** Zooms by [factor] keeping the world point under the screen point ([fx], [fy]) fixed. */
    fun zoom(t: ViewTransform, factor: Double, fx: Double, fy: Double): ViewTransform {
        val s = (t.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val k = s / t.scale
        return ViewTransform(s, fx - (fx - t.tx) * k, fy - (fy - t.ty) * k)
    }

    fun pan(t: ViewTransform, dx: Double, dy: Double) = t.copy(tx = t.tx + dx, ty = t.ty + dy)

    /** Draw order: groups first (largest first, so nested groups stay visible), then the other nodes in file order. */
    fun drawOrder(c: Canvas): List<CanvasNode> =
        c.nodes.filterIsInstance<CanvasNode.Group>().sortedByDescending { it.width * it.height } + c.nodes.filter { it !is CanvasNode.Group }

    /** The node under a world point: the topmost non-group node, else the smallest group; null on empty space. */
    fun hitTest(c: Canvas, wx: Double, wy: Double): CanvasNode? {
        fun inside(n: CanvasNode) = wx >= n.x && wx <= n.right && wy >= n.y && wy <= n.bottom
        return drawOrder(c).asReversed().firstOrNull { it !is CanvasNode.Group && inside(it) }
            ?: c.nodes.filterIsInstance<CanvasNode.Group>().filter(::inside).minByOrNull { it.width * it.height }
    }

    fun anchor(n: CanvasNode, s: Side): Pt = when (s) {
        Side.Top -> Pt(n.x + n.width / 2, n.y)
        Side.Bottom -> Pt(n.x + n.width / 2, n.bottom)
        Side.Left -> Pt(n.x, n.y + n.height / 2)
        Side.Right -> Pt(n.right, n.y + n.height / 2)
    }

    private fun dir(s: Side) = when (s) { Side.Top -> Pt(0.0, -1.0); Side.Bottom -> Pt(0.0, 1.0); Side.Left -> Pt(-1.0, 0.0); Side.Right -> Pt(1.0, 0.0) }

    /** When a side is omitted, leave by the side of the node that faces the other one. */
    fun autoSide(from: CanvasNode, to: CanvasNode): Pair<Side, Side> {
        val dx = (to.x + to.width / 2) - (from.x + from.width / 2)
        val dy = (to.y + to.height / 2) - (from.y + from.height / 2)
        return if (abs(dx) >= abs(dy)) (if (dx >= 0) Side.Right to Side.Left else Side.Left to Side.Right)
        else (if (dy >= 0) Side.Bottom to Side.Top else Side.Top to Side.Bottom)
    }

    fun curve(c: Canvas, e: CanvasEdge): EdgeCurve? {
        val a = c.node(e.fromNode) ?: return null
        val b = c.node(e.toNode) ?: return null
        val auto = autoSide(a, b)
        val fs = e.fromSide ?: auto.first; val ts = e.toSide ?: auto.second
        val p0 = anchor(a, fs); val p3 = anchor(b, ts)
        val dx = p3.x - p0.x; val dy = p3.y - p0.y
        val k = max(40.0, sqrt(dx * dx + dy * dy) * 0.4)
        val d0 = dir(fs); val d1 = dir(ts)
        return EdgeCurve(p0, Pt(p0.x + d0.x * k, p0.y + d0.y * k), Pt(p3.x + d1.x * k, p3.y + d1.y * k), p3, fs, ts)
    }
}

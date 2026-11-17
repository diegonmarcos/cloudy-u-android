package com.diegonmarcos.superapp.graphview

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A force-directed layout (repulsion between every pair, springs along the edges, a weak pull to the centre) that cools as it runs.
 * Deterministic for a given seed. [x] and [y] hold the position of [ids] in the same order; call [step] per frame until it returns a small energy.
 */
class ForceLayout(val ids: List<String>, edges: List<GEdge>, seed: Long = 7L) {
    val n: Int = ids.size
    val x = FloatArray(n)
    val y = FloatArray(n)
    private val vx = FloatArray(n)
    private val vy = FloatArray(n)
    private val ea: IntArray
    private val eb: IntArray
    private val ew: FloatArray

    /** The cooling factor of the forces; it settles at [ALPHA_MIN]. */
    var alpha = 1f
        private set

    init {
        val idx = ids.withIndex().associate { it.value to it.index }
        val kept = edges.filter { idx.containsKey(it.a) && idx.containsKey(it.b) && it.a != it.b }
        ea = IntArray(kept.size) { idx.getValue(kept[it].a) }
        eb = IntArray(kept.size) { idx.getValue(kept[it].b) }
        ew = FloatArray(kept.size) { if (kept[it].kind == GEdge.Kind.LINK) 1f else 0.5f }
        val rnd = java.util.Random(seed)
        for (i in 0 until n) {
            val ang = 2.0 * PI * i / max(n, 1)
            val rad = 80f + 160f * rnd.nextFloat()
            x[i] = (cos(ang) * rad).toFloat()
            y[i] = (sin(ang) * rad).toFloat()
        }
    }

    /** One tick; returns the total kinetic energy (it falls towards 0 as the layout settles). */
    fun step(): Float {
        if (n == 0) return 0f
        for (i in 0 until n) for (j in i + 1 until n) {
            val dx = x[i] - x[j]
            val dy = y[i] - y[j]
            val d2 = dx * dx + dy * dy + 0.01f
            if (d2 > CUTOFF2) continue
            val d = sqrt(d2)
            val f = REPEL * alpha / d2
            val fx = f * dx / d
            val fy = f * dy / d
            vx[i] += fx; vy[i] += fy
            vx[j] -= fx; vy[j] -= fy
        }
        for (k in ea.indices) {
            val a = ea[k]
            val b = eb[k]
            val dx = x[b] - x[a]
            val dy = y[b] - y[a]
            val d = sqrt(dx * dx + dy * dy + 0.01f)
            val f = (d - REST) * SPRING * ew[k]
            val fx = f * dx / d
            val fy = f * dy / d
            vx[a] += fx; vy[a] += fy
            vx[b] -= fx; vy[b] -= fy
        }
        var energy = 0f
        for (i in 0 until n) {
            vx[i] = (vx[i] - x[i] * GRAVITY) * DAMP
            vy[i] = (vy[i] - y[i] * GRAVITY) * DAMP
            vx[i] = vx[i].coerceIn(-MAX_V, MAX_V)
            vy[i] = vy[i].coerceIn(-MAX_V, MAX_V)
            x[i] += vx[i]
            y[i] += vy[i]
            energy += vx[i] * vx[i] + vy[i] * vy[i]
        }
        alpha = max(ALPHA_MIN, alpha * COOL)
        return energy
    }

    /** Runs [steps] ticks (or until settled); for tests and for a first layout before the first frame. */
    fun settle(steps: Int = 400, energy: Float = 0.05f): Int {
        var s = 0
        while (s < steps) { s++; if (step() < energy && alpha <= ALPHA_MIN + 1e-4f) break }
        return s
    }

    companion object {
        const val ALPHA_MIN = 0.02f
        private const val REPEL = 2600f
        private const val SPRING = 0.04f
        private const val REST = 70f
        private const val GRAVITY = 0.012f
        private const val DAMP = 0.82f
        private const val COOL = 0.985f
        private const val MAX_V = 40f
        private const val CUTOFF2 = 400f * 400f
    }
}

package com.diegonmarcos.superapp.launcher.themes.cloud

import kotlin.math.sqrt

/**
 * #1621 — the background shape engine, PURE (no Android, JVM-testable).
 *
 * The launcher background used to be one hard-wired rotating wireframe cube
 * ([Home3DFragment]'s old `CubeMath`: 8 vertices, 12 edges, a 3D perspective
 * projection on the Compose frame clock). This generalises that one shape to
 * the full set of regular polytopes the owner asked for and keeps the SAME
 * render pipeline — wireframe edges, the launcher palette, one `withFrameNanos`
 * loop — so the default (the cube) looks exactly as it did.
 *
 *   3D Platonic solids : Tetrahedron, Cube (default), Octahedron,
 *                        Dodecahedron, Icosahedron.
 *   4D regular polytopes: 5-cell, 8-cell / Tesseract, 16-cell, 120-cell,
 *                        600-cell (the 3D solids' analogues) and the 24-cell
 *                        (the self-dual 4D polytope with no regular 3D twin).
 *
 * A 4D polytope is rendered as an animated 4D→3D perspective projection
 * (a Schlegel-style "cell within a cell") that rotates in two 4-space planes,
 * then goes through the same 3D→2D projection the cube uses — so it lands in
 * the very slot the cube occupies.
 *
 * Everything here is pure maths on plain arrays: no vertex/edge list is
 * hand-typed beyond the generating coordinate sets, edges are DERIVED by the
 * nearest-neighbour rule (a regular polytope's edges are exactly the vertex
 * pairs at the minimum distance), and the whole thing is unit-tested in
 * PolytopeMathTest. The renderer owns only the drawing; the geometry lives
 * here and is proven here.
 */
object PolytopeMath {

    // ── Shape ids (declared in build.json::ui.launcher_settings.background.shapes) ──
    const val TETRAHEDRON = "tetrahedron"
    const val CUBE = "cube"
    const val OCTAHEDRON = "octahedron"
    const val DODECAHEDRON = "dodecahedron"
    const val ICOSAHEDRON = "icosahedron"
    const val CELL5 = "cell5"            // 4-simplex, Tetrahedron's analogue
    const val TESSERACT = "tesseract"    // 8-cell, Cube's analogue
    const val CELL16 = "cell16"          // 4-orthoplex, Octahedron's analogue
    const val CELL120 = "cell120"        // Dodecahedron's analogue
    const val CELL600 = "cell600"        // Icosahedron's analogue
    const val CELL24 = "cell24"          // self-dual, no regular 3D twin

    /** Declared order of the selectable shapes (3D first, then 4D). */
    val SHAPE_IDS: List<String> = listOf(
        TETRAHEDRON, CUBE, OCTAHEDRON, DODECAHEDRON, ICOSAHEDRON,
        CELL5, TESSERACT, CELL16, CELL120, CELL600, CELL24,
    )

    const val DEFAULT_SHAPE = CUBE

    // ── Rotation clocks (ms). Two 3D axes + two 4D planes, each its own
    //    period off the SAME monotonic clock, so angles never skip or snap
    //    (the #773 lesson the cube already carried). ──
    const val PERIOD_Y_MS = 16_000.0
    const val PERIOD_X_MS = 23_500.0
    const val PERIOD_4A_MS = 19_000.0
    const val PERIOD_4B_MS = 31_000.0
    val ROT_Y0 = (-Math.PI / 5.5).toFloat()
    val ROT_X0 = (Math.PI / 7.0).toFloat()

    // ── Projection constants (match the historical cube so the default is pixel-identical). ──
    private const val SCALE_DENOM = 4.6f     // min(w,h) / this = world→screen scale
    private const val PERSP_3D = 4.5f        // 3D→2D perspective camera distance
    private const val W_DIST_4D = 2.6f       // 4D→3D perspective (4-space camera distance)
    private val CUBE_R = sqrt(3.0).toFloat() // the cube's circumradius (±1,±1,±1)
    private const val FIT_4D = 0.82f         // margin so a breathing 4D hull stays in frame

    private val PHI = ((1.0 + sqrt(5.0)) / 2.0)

    /** A polytope: 4D vertices (3D shapes carry w=0), derived edges, and the
     *  world-space scale that makes it fill the cube's slot. */
    class Mesh(
        val id: String,
        val dim: Int,
        /** Each is FloatArray(4): x,y,z,w. 3D shapes have w=0. */
        val verts: Array<FloatArray>,
        /** Vertex-index pairs; the polytope's edges. */
        val edges: Array<IntArray>,
        /** Multiply post-projection coords by this so the hull fills the cube's box. */
        val fit: Float,
    )

    private val cache = HashMap<String, Mesh>()

    /** The mesh for [id], built once and cached. Unknown id falls back to the cube. */
    fun mesh(id: String): Mesh = cache.getOrPut(id) { build(if (id in SHAPE_IDS) id else DEFAULT_SHAPE) }

    private fun build(id: String): Mesh {
        val (dim, raw) = coordsFor(id)
        // Normalise to circumradius 1 so every shape shares one coordinate scale.
        var maxN = 0.0
        for (v in raw) maxN = maxOf(maxN, norm(v).toDouble())
        val inv = if (maxN == 0.0) 1f else (1.0 / maxN).toFloat()
        val verts = Array(raw.size) { i -> FloatArray(4) { j -> raw[i][j] * inv } }
        val edges = deriveEdges(verts)
        val fit = fitScale(dim, verts)
        return Mesh(id, dim, verts, edges, fit)
    }

    /** The world→hull scale. 3D: circumradius back to the cube's √3 (so the cube
     *  is unchanged). 4D: the largest 3D radius the projected hull reaches over a
     *  sweep of 4-rotations, shrunk by [FIT_4D] so the "breathing" never clips. */
    private fun fitScale(dim: Int, verts: Array<FloatArray>): Float {
        if (dim == 3) return CUBE_R
        var maxR = 1e-4f
        val samples = 12
        for (s in 0 until samples) {
            val a = (2.0 * Math.PI * s / samples)
            val b = a * 0.7 + 0.3
            for (v in verts) {
                val p = project4to3(v, a, b)
                maxR = maxOf(maxR, sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]))
            }
        }
        return CUBE_R * FIT_4D / maxR
    }

    private fun norm(v: FloatArray): Float {
        var s = 0f; for (x in v) s += x * x; return sqrt(s)
    }

    /** Edges = all vertex pairs at the minimum pairwise distance (±2%). For a
     *  regular polytope that is exactly its edge set. O(n²) once, then cached. */
    private fun deriveEdges(verts: Array<FloatArray>): Array<IntArray> {
        val n = verts.size
        var minD2 = Double.MAX_VALUE
        for (i in 0 until n) for (j in i + 1 until n) {
            val d2 = dist2(verts[i], verts[j])
            if (d2 > 1e-9 && d2 < minD2) minD2 = d2
        }
        val tol = minD2 * 1.04 + 1e-9   // 2% on distance ≈ 4% on squared
        val out = ArrayList<IntArray>()
        for (i in 0 until n) for (j in i + 1 until n) {
            if (dist2(verts[i], verts[j]) <= tol) out.add(intArrayOf(i, j))
        }
        return out.toTypedArray()
    }

    private fun dist2(a: FloatArray, b: FloatArray): Double {
        var s = 0.0
        for (k in 0..3) { val d = (a[k] - b[k]).toDouble(); s += d * d }
        return s
    }

    /** The public projection used by the renderer. [elapsedMs] drives all four
     *  rotation clocks; result is screen-space points + a per-vertex depth
     *  (larger = further back) for edge fade. */
    fun project(
        mesh: Mesh, elapsedMs: Double, width: Float, height: Float,
        rotXOverride: Float? = null, rotYOverride: Float? = null,
    ): Result {
        val cx = width / 2f; val cy = height / 2f
        val scale = minOf(width, height) / SCALE_DENOM
        val rY = rotYOverride ?: angleY(elapsedMs); val rX = rotXOverride ?: angleX(elapsedMs)
        val a4 = ROT_Y0 + (elapsedMs / PERIOD_4A_MS) * (2.0 * Math.PI)
        val b4 = ROT_X0 + (elapsedMs / PERIOD_4B_MS) * (2.0 * Math.PI)
        val cyR = Math.cos(rY.toDouble()).toFloat(); val syR = Math.sin(rY.toDouble()).toFloat()
        val cxR = Math.cos(rX.toDouble()).toFloat(); val sxR = Math.sin(rX.toDouble()).toFloat()

        val n = mesh.verts.size
        val px = FloatArray(n); val py = FloatArray(n); val pz = FloatArray(n)
        for (i in 0 until n) {
            val v = mesh.verts[i]
            // 4D → 3D (perspective) for 4D shapes; a 3D shape passes straight through.
            val p3 = if (mesh.dim == 4) project4to3(v, a4, b4) else floatArrayOf(v[0], v[1], v[2])
            var x = p3[0] * mesh.fit; var y = p3[1] * mesh.fit; var z = p3[2] * mesh.fit
            // 3D rotation about Y then X (identical to the old cube).
            val x1 = x * cyR + z * syR
            val z1 = -x * syR + z * cyR
            val y2 = y * cxR - z1 * sxR
            val z2 = y * sxR + z1 * cxR
            val k = PERSP_3D / (PERSP_3D + z2)
            px[i] = cx + x1 * scale * k
            py[i] = cy + y2 * scale * k
            pz[i] = z2
        }
        return Result(px, py, pz, mesh.edges)
    }

    /** Rotate a 4-vector in the (x,w) and (y,z) planes, then perspective-project to 3D. */
    private fun project4to3(v: FloatArray, aXW: Double, bYZ: Double): FloatArray {
        val ca = Math.cos(aXW); val sa = Math.sin(aXW)
        val cb = Math.cos(bYZ); val sb = Math.sin(bYZ)
        var x = v[0].toDouble(); var y = v[1].toDouble(); var z = v[2].toDouble(); var w = v[3].toDouble()
        // (x,w) plane
        val x1 = x * ca - w * sa
        val w1 = x * sa + w * ca
        // (y,z) plane
        val y1 = y * cb - z * sb
        val z1 = y * sb + z * cb
        val k = W_DIST_4D / (W_DIST_4D - w1)   // 4-space camera in front of the hull
        return floatArrayOf((x1 * k).toFloat(), (y1 * k).toFloat(), (z1 * k).toFloat())
    }

    class Result(val x: FloatArray, val y: FloatArray, val depth: FloatArray, val edges: Array<IntArray>)

    fun angleY(elapsedMs: Double): Float = (ROT_Y0 + (elapsedMs / PERIOD_Y_MS) * (2.0 * Math.PI)).toFloat()
    fun angleX(elapsedMs: Double): Float = (ROT_X0 + (elapsedMs / PERIOD_X_MS) * (2.0 * Math.PI)).toFloat()

    /** Edges in the back fade: alpha 255 at the front down to 135 (the cube's rule). */
    fun edgeAlpha(avgDepth: Float): Float = (255 - (avgDepth * 80f).coerceIn(0f, 120f)) / 255f

    // ── Auto-cycle ───────────────────────────────────────────────────────────
    /** Which index of a [count]-shape cycle is showing at [clockMs], rotating
     *  every [minutes] minutes. Reads the SAME monotonic clock the rotation
     *  uses, so no second timer runs. [minutes] < 1 or [count] < 1 → index 0. */
    fun cycleIndex(count: Int, minutes: Int, clockMs: Double): Int {
        if (count <= 1 || minutes < 1) return 0
        val period = minutes.toLong() * 60_000L
        val n = (clockMs.toLong() / period) % count
        return (if (n < 0) n + count else n).toInt()
    }

    // ── Generating coordinate sets ─────────────────────────────────────────
    private fun coordsFor(id: String): Pair<Int, Array<FloatArray>> = when (id) {
        TETRAHEDRON -> 3 to v3(
            f(1, 1, 1), f(1, -1, -1), f(-1, 1, -1), f(-1, -1, 1),
        )
        CUBE -> 3 to v3(
            f(-1, -1, -1), f(1, -1, -1), f(1, 1, -1), f(-1, 1, -1),
            f(-1, -1, 1), f(1, -1, 1), f(1, 1, 1), f(-1, 1, 1),
        )
        OCTAHEDRON -> 3 to v3(
            f(1, 0, 0), f(-1, 0, 0), f(0, 1, 0), f(0, -1, 0), f(0, 0, 1), f(0, 0, -1),
        )
        DODECAHEDRON -> {
            val a = (1.0 / PHI).toFloat(); val b = PHI.toFloat()
            val list = ArrayList<FloatArray>()
            for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1))
                list.add(f3(sx.toFloat(), sy.toFloat(), sz.toFloat()))
            for (s1 in intArrayOf(-1, 1)) for (s2 in intArrayOf(-1, 1)) {
                list.add(f3(0f, s1 * a, s2 * b))
                list.add(f3(s2 * b, 0f, s1 * a))
                list.add(f3(s1 * a, s2 * b, 0f))
            }
            3 to list.toTypedArray()
        }
        ICOSAHEDRON -> {
            val b = PHI.toFloat()
            val list = ArrayList<FloatArray>()
            for (s1 in intArrayOf(-1, 1)) for (s2 in intArrayOf(-1, 1)) {
                list.add(f3(0f, s1 * 1f, s2 * b))
                list.add(f3(s1 * 1f, s2 * b, 0f))
                list.add(f3(s2 * b, 0f, s1 * 1f))
            }
            3 to list.toTypedArray()
        }
        CELL5 -> {
            val c = (1.0 / sqrt(5.0)).toFloat()
            4 to v4(
                f4(1f, 1f, 1f, -c), f4(1f, -1f, -1f, -c), f4(-1f, 1f, -1f, -c),
                f4(-1f, -1f, 1f, -c), f4(0f, 0f, 0f, 4 * c),
            )
        }
        TESSERACT -> {
            val list = ArrayList<FloatArray>()
            for (a in intArrayOf(-1, 1)) for (b in intArrayOf(-1, 1)) for (c in intArrayOf(-1, 1)) for (d in intArrayOf(-1, 1))
                list.add(f4(a.toFloat(), b.toFloat(), c.toFloat(), d.toFloat()))
            4 to list.toTypedArray()
        }
        CELL16 -> 4 to v4(
            f4(1f, 0f, 0f, 0f), f4(-1f, 0f, 0f, 0f), f4(0f, 1f, 0f, 0f), f4(0f, -1f, 0f, 0f),
            f4(0f, 0f, 1f, 0f), f4(0f, 0f, -1f, 0f), f4(0f, 0f, 0f, 1f), f4(0f, 0f, 0f, -1f),
        )
        CELL24 -> {
            // All permutations of (±1,±1,0,0): pick 2 of 4 slots for the ±1s.
            val slots = listOf(intArrayOf(0, 1), intArrayOf(0, 2), intArrayOf(0, 3),
                intArrayOf(1, 2), intArrayOf(1, 3), intArrayOf(2, 3))
            val list = ArrayList<FloatArray>()
            for (pair in slots) for (s1 in intArrayOf(-1, 1)) for (s2 in intArrayOf(-1, 1)) {
                val p = FloatArray(4); p[pair[0]] = s1.toFloat(); p[pair[1]] = s2.toFloat(); list.add(p)
            }
            4 to list.toTypedArray()
        }
        CELL600 -> {
            val list = ArrayList<FloatArray>()
            // 16 × (±½,±½,±½,±½)
            for (a in intArrayOf(-1, 1)) for (b in intArrayOf(-1, 1)) for (c in intArrayOf(-1, 1)) for (d in intArrayOf(-1, 1))
                list.add(f4(a * 0.5f, b * 0.5f, c * 0.5f, d * 0.5f))
            // 8 × permutations of (±1,0,0,0)
            for (axis in 0..3) for (s in intArrayOf(-1, 1)) {
                val p = FloatArray(4); p[axis] = s.toFloat(); list.add(p)
            }
            // 96 × even permutations of (±φ/2, ±1/2, ±1/(2φ), 0)
            val t = doubleArrayOf(PHI / 2.0, 0.5, 1.0 / (2.0 * PHI), 0.0)
            list.addAll(evenPermsWithSigns(t))
            4 to dedup(list)
        }
        CELL120 -> {
            val list = ArrayList<FloatArray>()
            val r5 = sqrt(5.0)
            val p = PHI; val ip = 1.0 / PHI; val ip2 = 1.0 / (PHI * PHI); val p2 = PHI * PHI
            // all permutations (with signs) of these:
            list.addAll(allPermsWithSigns(doubleArrayOf(0.0, 0.0, 2.0, 2.0)))
            list.addAll(allPermsWithSigns(doubleArrayOf(1.0, 1.0, 1.0, r5)))
            list.addAll(allPermsWithSigns(doubleArrayOf(ip2, p, p, p)))
            list.addAll(allPermsWithSigns(doubleArrayOf(ip, ip, ip, p2)))
            // even permutations (with signs) of these:
            list.addAll(evenPermsWithSigns(doubleArrayOf(0.0, ip2, 1.0, p2)))
            list.addAll(evenPermsWithSigns(doubleArrayOf(0.0, ip, p, r5)))
            list.addAll(evenPermsWithSigns(doubleArrayOf(ip, 1.0, p, 2.0)))
            4 to dedup(list)
        }
        else -> coordsFor(CUBE)
    }

    // ── Permutation helpers (4 elements) ─────────────────────────────────────
    private val PERMS_4: List<IntArray> = buildList {
        val base = intArrayOf(0, 1, 2, 3)
        permute(base, 0, this)
    }
    private fun permute(a: IntArray, k: Int, out: MutableList<IntArray>) {
        if (k == a.size) { out.add(a.copyOf()); return }
        for (i in k until a.size) {
            val b = a.copyOf(); val t = b[k]; b[k] = b[i]; b[i] = t
            permute(b, k + 1, out)
        }
    }
    private fun parity(perm: IntArray): Int {
        var inv = 0
        for (i in perm.indices) for (j in i + 1 until perm.size) if (perm[i] > perm[j]) inv++
        return inv and 1
    }

    private fun allPermsWithSigns(t: DoubleArray): List<FloatArray> = permsWithSigns(t, false)
    private fun evenPermsWithSigns(t: DoubleArray): List<FloatArray> = permsWithSigns(t, true)

    private fun permsWithSigns(t: DoubleArray, evenOnly: Boolean): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (perm in PERMS_4) {
            if (evenOnly && parity(perm) != 0) continue
            val permuted = DoubleArray(4) { t[perm[it]] }
            for (mask in 0 until 16) {
                val p = FloatArray(4)
                for (k in 0..3) {
                    val sign = if ((mask shr k) and 1 == 1) -1.0 else 1.0
                    p[k] = (permuted[k] * sign).toFloat()
                }
                out.add(p)
            }
        }
        return out
    }

    /** Drop coincident points (sign flips on a 0 coord, repeated-value perms). */
    private fun dedup(list: List<FloatArray>): Array<FloatArray> {
        val seen = HashSet<String>()
        val out = ArrayList<FloatArray>()
        for (p in list) {
            val key = buildString {
                for (k in 0..3) { append(Math.round(p[k] * 100000f)); append(',') }
            }
            if (seen.add(key)) out.add(p)
        }
        return out.toTypedArray()
    }

    // tiny builders
    private fun f(a: Int, b: Int, c: Int) = floatArrayOf(a.toFloat(), b.toFloat(), c.toFloat(), 0f)
    private fun f3(a: Float, b: Float, c: Float) = floatArrayOf(a, b, c, 0f)
    private fun f4(a: Float, b: Float, c: Float, d: Float) = floatArrayOf(a, b, c, d)
    private fun v3(vararg vs: FloatArray) = arrayOf(*vs)
    private fun v4(vararg vs: FloatArray) = arrayOf(*vs)
}

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
 * #1915 the catalogue grew from the eleven regular polytopes to 27 shapes, grouped
 * 3D Polyhedra / 4D Polytopes and by face type. The new ones are generated here the
 * same way (no hand-typed edge tables): the Kepler-Poinsot solids and the Schlaefli-Hess
 * star polychora reuse the icosahedron / dodecahedron / 600-cell vertex sets and differ
 * only in WHICH distance class is drawn as an edge ([edgeRanks]); the truncations,
 * the hexagonal prism / pyramid and the truncated 5-/16-cell have their own generators.
 * [FACES] declares each shape's face data; [countFaces] re-derives it from the geometry
 * so PolytopeMathTest can prove vertex, edge AND face counts.
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

    // #1915 new 3D shapes
    const val GREAT_ICOSAHEDRON = "great_icosahedron"
    const val GREAT_DODECAHEDRON = "great_dodecahedron"
    const val SMALL_STELLATED_DODECAHEDRON = "small_stellated_dodecahedron"
    const val GREAT_STELLATED_DODECAHEDRON = "great_stellated_dodecahedron"
    const val HEXAGONAL_PRISM = "hexagonal_prism"
    const val HEXAGONAL_PYRAMID = "hexagonal_pyramid"
    const val TRUNCATED_TETRAHEDRON = "truncated_tetrahedron"
    const val TRUNCATED_OCTAHEDRON = "truncated_octahedron"
    const val TRUNCATED_ICOSAHEDRON = "truncated_icosahedron"
    // #1915 new 4D shapes
    const val ICOSAHEDRAL_120_CELL = "icosahedral_120_cell"
    const val GREAT_120_CELL = "great_120_cell"
    const val GRAND_120_CELL = "grand_120_cell"
    const val SMALL_STELLATED_120_CELL = "small_stellated_120_cell"
    const val GREAT_STELLATED_120_CELL = "great_stellated_120_cell"
    const val TRUNCATED_5_CELL = "truncated_5_cell"
    const val TRUNCATED_16_CELL = "truncated_16_cell"

    /** Declared order of the selectable shapes: 3D Polyhedra then 4D Polytopes, each by face type
     *  (Triangle, Square, Pentagon, Pentagram, Hexagon hybrid) — the order of build.json's catalogue. */
    val SHAPE_IDS: List<String> = listOf(
        TETRAHEDRON, OCTAHEDRON, ICOSAHEDRON, GREAT_ICOSAHEDRON,
        CUBE,
        DODECAHEDRON, GREAT_DODECAHEDRON,
        SMALL_STELLATED_DODECAHEDRON, GREAT_STELLATED_DODECAHEDRON,
        HEXAGONAL_PRISM, HEXAGONAL_PYRAMID, TRUNCATED_TETRAHEDRON, TRUNCATED_OCTAHEDRON, TRUNCATED_ICOSAHEDRON,
        CELL5, CELL16, CELL24, CELL600, ICOSAHEDRAL_120_CELL,
        TESSERACT,
        CELL120, GREAT_120_CELL, GRAND_120_CELL,
        SMALL_STELLATED_120_CELL, GREAT_STELLATED_120_CELL,
        TRUNCATED_5_CELL, TRUNCATED_16_CELL,
    )

    /** Old / alternative names that still resolve to a shape (the ids themselves never changed). */
    val ALIASES: Map<String, String> = mapOf(
        "hypercube" to TESSERACT, "cell8" to TESSERACT, "pentachoron" to CELL5,
        "hexadecachoron" to CELL16, "icositetrachoron" to CELL24,
        "hecatonicosachoron" to CELL120, "hexacosichoron" to CELL600,
        "soccer_ball" to TRUNCATED_ICOSAHEDRON, "truncated_hexadecachoron" to TRUNCATED_16_CELL,
    )

    /** One kind of face: [sides]-gon, [star] = pentagram, [count] of them. */
    class FaceSpec(val sides: Int, val star: Boolean, val count: Int)
    private fun tri(n: Int) = FaceSpec(3, false, n)
    private fun sq(n: Int) = FaceSpec(4, false, n)
    private fun pent(n: Int) = FaceSpec(5, false, n)
    private fun pgram(n: Int) = FaceSpec(5, true, n)
    private fun hex(n: Int) = FaceSpec(6, false, n)

    /** Declared face data per shape (the owner's "{N} faces"); PolytopeMathTest re-derives it from the geometry. */
    val FACES: Map<String, List<FaceSpec>> = mapOf(
        TETRAHEDRON to listOf(tri(4)), OCTAHEDRON to listOf(tri(8)), ICOSAHEDRON to listOf(tri(20)),
        GREAT_ICOSAHEDRON to listOf(tri(20)), CUBE to listOf(sq(6)),
        DODECAHEDRON to listOf(pent(12)), GREAT_DODECAHEDRON to listOf(pent(12)),
        SMALL_STELLATED_DODECAHEDRON to listOf(pgram(12)), GREAT_STELLATED_DODECAHEDRON to listOf(pgram(12)),
        HEXAGONAL_PRISM to listOf(hex(2), sq(6)), HEXAGONAL_PYRAMID to listOf(hex(1), tri(6)),
        TRUNCATED_TETRAHEDRON to listOf(hex(4), tri(4)), TRUNCATED_OCTAHEDRON to listOf(hex(8), sq(6)),
        TRUNCATED_ICOSAHEDRON to listOf(hex(20), pent(12)),
        CELL5 to listOf(tri(10)), CELL16 to listOf(tri(32)), CELL24 to listOf(tri(96)),
        CELL600 to listOf(tri(1200)), ICOSAHEDRAL_120_CELL to listOf(tri(1200)),
        TESSERACT to listOf(sq(24)),
        CELL120 to listOf(pent(720)), GREAT_120_CELL to listOf(pent(720)), GRAND_120_CELL to listOf(pent(720)),
        SMALL_STELLATED_120_CELL to listOf(pgram(720)), GREAT_STELLATED_120_CELL to listOf(pgram(720)),
        TRUNCATED_5_CELL to listOf(hex(10), tri(20)), TRUNCATED_16_CELL to listOf(hex(32), tri(64)),
    )

    /** Which distance class(es) of the vertex set are drawn as edges: 0 = the shortest distance (every
     *  shape before #1915), 1 = the next, ... The star shapes share a vertex set and differ only here.
     *  The pyramid / prism draw two classes (base edges + the apex / vertical edges). */
    private fun edgeRanks(id: String): IntArray = when (id) {
        GREAT_ICOSAHEDRON, SMALL_STELLATED_DODECAHEDRON -> intArrayOf(1)   // icosahedron's 2nd neighbours
        GREAT_STELLATED_DODECAHEDRON -> intArrayOf(3)                     // dodecahedron's 4th distance class
        HEXAGONAL_PRISM, HEXAGONAL_PYRAMID -> intArrayOf(0, 1)
        SMALL_STELLATED_120_CELL, GREAT_STELLATED_120_CELL -> intArrayOf(4) // 600-cell vertices at 108 degrees
        else -> intArrayOf(0)
    }

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
    fun mesh(id: String): Mesh {
        val key = ALIASES[id] ?: id
        return cache.getOrPut(key) { build(if (key in SHAPE_IDS) key else DEFAULT_SHAPE) }
    }

    private fun build(id: String): Mesh {
        val (dim, raw0) = coordsFor(id)
        // Centre on the vertex centroid (a no-op for the symmetric shapes; the pyramid is not).
        val cen = FloatArray(4) { k -> (raw0.sumOf { it[k].toDouble() } / raw0.size).toFloat() }
        val raw = Array(raw0.size) { i -> FloatArray(4) { k -> raw0[i][k] - cen[k] } }
        // Normalise to circumradius 1 so every shape shares one coordinate scale.
        var maxN = 0.0
        for (v in raw) maxN = maxOf(maxN, norm(v).toDouble())
        val inv = if (maxN == 0.0) 1f else (1.0 / maxN).toFloat()
        val verts = Array(raw.size) { i -> FloatArray(4) { j -> raw[i][j] * inv } }
        val edges = deriveEdges(verts, edgeRanks(id))
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

    /** Edges = all vertex pairs in the given distance classes ([ranks]: 0 = the minimum pairwise
     *  distance, 1 = the next distinct one, ...; distances within 1% of each other are one class).
     *  For a regular polytope rank 0 is exactly its edge set. O(n^2) once, then cached. */
    private fun deriveEdges(verts: Array<FloatArray>, ranks: IntArray = intArrayOf(0)): Array<IntArray> {
        val n = verts.size
        val all = DoubleArray(n * (n - 1) / 2)
        var m = 0
        for (i in 0 until n) for (j in i + 1 until n) all[m++] = dist2(verts[i], verts[j])
        all.sort()
        // Cluster the sorted squared distances into classes: [lo, hi] each.
        val lo = ArrayList<Double>(); val hi = ArrayList<Double>()
        for (d in all) {
            if (d < 1e-9) continue
            if (hi.isNotEmpty() && d <= hi.last() * 1.01 + 1e-9) hi[hi.size - 1] = d
            else { lo.add(d); hi.add(d) }
        }
        val out = ArrayList<IntArray>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = dist2(verts[i], verts[j])
            for (r in ranks) if (r < lo.size && d >= lo[r] * 0.995 && d <= hi[r] * 1.005 + 1e-9) { out.add(intArrayOf(i, j)); break }
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
        a4Override: Double? = null, b4Override: Double? = null,
    ): Result {
        val cx = width / 2f; val cy = height / 2f
        val scale = minOf(width, height) / SCALE_DENOM
        val rY = rotYOverride ?: angleY(elapsedMs); val rX = rotXOverride ?: angleX(elapsedMs)
        // #1685 the 4D planes take an override too, so the home model's accumulated
        // orientation (ModelSpin) drives them and auto-rotate can stop them.
        val a4 = a4Override ?: (ROT_Y0 + (elapsedMs / PERIOD_4A_MS) * (2.0 * Math.PI))
        val b4 = b4Override ?: (ROT_X0 + (elapsedMs / PERIOD_4B_MS) * (2.0 * Math.PI))
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
        DODECAHEDRON, GREAT_STELLATED_DODECAHEDRON -> 3 to dodecahedronCoords()
        ICOSAHEDRON, GREAT_ICOSAHEDRON, GREAT_DODECAHEDRON, SMALL_STELLATED_DODECAHEDRON -> 3 to icosahedronCoords()
        HEXAGONAL_PRISM -> {
            val list = ArrayList<FloatArray>()
            for (k in 0 until 6) for (z in floatArrayOf(0.75f, -0.75f))
                list.add(f3(Math.cos(k * Math.PI / 3).toFloat(), Math.sin(k * Math.PI / 3).toFloat(), z))
            3 to list.toTypedArray()
        }
        HEXAGONAL_PYRAMID -> {
            val list = ArrayList<FloatArray>()
            for (k in 0 until 6) list.add(f3(Math.cos(k * Math.PI / 3).toFloat(), Math.sin(k * Math.PI / 3).toFloat(), 0f))
            list.add(f3(0f, 0f, 1.2f))
            3 to list.toTypedArray()
        }
        TRUNCATED_TETRAHEDRON -> {
            // permutations of (3,1,1) with an even number of minus signs
            val list = ArrayList<FloatArray>()
            for (big in 0..2) for (mask in 0 until 8) {
                if (Integer.bitCount(mask) % 2 != 0) continue
                val p = FloatArray(4)
                for (k in 0..2) p[k] = (if (k == big) 3f else 1f) * (if ((mask shr k) and 1 == 1) -1f else 1f)
                list.add(p)
            }
            3 to list.toTypedArray()
        }
        TRUNCATED_OCTAHEDRON -> {
            // all permutations of (0, +-1, +-2)
            val list = ArrayList<FloatArray>()
            val base = intArrayOf(0, 1, 2)
            for (perm in listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2),
                intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0)))
                for (mask in 0 until 8) {
                    val p = FloatArray(4)
                    for (k in 0..2) p[k] = base[perm[k]].toFloat() * (if ((mask shr k) and 1 == 1) -1f else 1f)
                    list.add(p)
                }
            3 to dedup(list)
        }
        TRUNCATED_ICOSAHEDRON -> {
            // even (cyclic) permutations of (0,+-1,+-3phi), (+-2,+-(1+2phi),+-phi), (+-1,+-(2+phi),+-2phi)
            val list = ArrayList<FloatArray>()
            val ph = PHI
            for (t in listOf(doubleArrayOf(0.0, 1.0, 3 * ph), doubleArrayOf(2.0, 1 + 2 * ph, ph), doubleArrayOf(1.0, 2 + ph, 2 * ph)))
                for (c in 0..2) for (mask in 0 until 8) {
                    val p = FloatArray(4)
                    for (k in 0..2) p[k] = (t[(k + c) % 3] * (if ((mask shr k) and 1 == 1) -1.0 else 1.0)).toFloat()
                    list.add(p)
                }
            3 to dedup(list)
        }
        TRUNCATED_5_CELL -> {
            // The 20 permutations of (0,0,0,1,2) in 5-space (sum-zero hyperplane after centring), expressed in an
            // orthonormal basis of that hyperplane = a uniform truncated 4-simplex in R^4.
            val basis = ArrayList<DoubleArray>()
            for (k in 0..3) {
                val w = DoubleArray(5); w[k] = 1.0; w[k + 1] = -1.0
                for (o in basis) { var d = 0.0; for (i in 0..4) d += w[i] * o[i]; for (i in 0..4) w[i] -= d * o[i] }
                var nn = 0.0; for (x in w) nn += x * x; nn = sqrt(nn)
                for (i in 0..4) w[i] /= nn
                basis.add(w)
            }
            val list = ArrayList<FloatArray>()
            for (one in 0..4) for (two in 0..4) {
                if (one == two) continue
                val v = DoubleArray(5); v[one] = 1.0; v[two] = 2.0
                for (i in 0..4) v[i] -= 0.6
                val p = FloatArray(4)
                for (k in 0..3) { var d = 0.0; for (i in 0..4) d += v[i] * basis[k][i]; p[k] = d.toFloat() }
                list.add(p)
            }
            4 to list.toTypedArray()
        }
        TRUNCATED_16_CELL -> 4 to dedup(allPermsWithSigns(doubleArrayOf(2.0, 1.0, 0.0, 0.0)))
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
        CELL600, ICOSAHEDRAL_120_CELL, GREAT_120_CELL, GRAND_120_CELL,
        SMALL_STELLATED_120_CELL, GREAT_STELLATED_120_CELL -> 4 to cell600Coords()
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

    /** The 20 vertices (+-1,+-1,+-1) and (0,+-1/phi,+-phi) cyclic. */
    private fun dodecahedronCoords(): Array<FloatArray> {
        val a = (1.0 / PHI).toFloat(); val b = PHI.toFloat()
        val list = ArrayList<FloatArray>()
        for (sx in intArrayOf(-1, 1)) for (sy in intArrayOf(-1, 1)) for (sz in intArrayOf(-1, 1))
            list.add(f3(sx.toFloat(), sy.toFloat(), sz.toFloat()))
        for (s1 in intArrayOf(-1, 1)) for (s2 in intArrayOf(-1, 1)) {
            list.add(f3(0f, s1 * a, s2 * b))
            list.add(f3(s2 * b, 0f, s1 * a))
            list.add(f3(s1 * a, s2 * b, 0f))
        }
        return list.toTypedArray()
    }

    /** The 12 vertices (0,+-1,+-phi) cyclic. */
    private fun icosahedronCoords(): Array<FloatArray> {
        val b = PHI.toFloat()
        val list = ArrayList<FloatArray>()
        for (s1 in intArrayOf(-1, 1)) for (s2 in intArrayOf(-1, 1)) {
            list.add(f3(0f, s1 * 1f, s2 * b))
            list.add(f3(s1 * 1f, s2 * b, 0f))
            list.add(f3(s2 * b, 0f, s1 * 1f))
        }
        return list.toTypedArray()
    }

    /** The 120 vertices of the 600-cell; the five star polychora and the icosahedral 120-cell share them. */
    private fun cell600Coords(): Array<FloatArray> {
        val list = ArrayList<FloatArray>()
        // 16 x (+-1/2,+-1/2,+-1/2,+-1/2)
        for (a in intArrayOf(-1, 1)) for (b in intArrayOf(-1, 1)) for (c in intArrayOf(-1, 1)) for (d in intArrayOf(-1, 1))
            list.add(f4(a * 0.5f, b * 0.5f, c * 0.5f, d * 0.5f))
        // 8 x permutations of (+-1,0,0,0)
        for (axis in 0..3) for (s in intArrayOf(-1, 1)) {
            val p = FloatArray(4); p[axis] = s.toFloat(); list.add(p)
        }
        // 96 x even permutations of (+-phi/2, +-1/2, +-1/(2phi), 0)
        val t = doubleArrayOf(PHI / 2.0, 0.5, 1.0 / (2.0 * PHI), 0.0)
        list.addAll(evenPermsWithSigns(t))
        return dedup(list)
    }

    /** #1915 Number of [sides]-gon faces in [mesh], re-derived from the geometry: chordless, planar cycles of
     *  the edge graph. For 5 sides, [star] picks pentagrams (the step-2 chord is SHORTER than the side)
     *  over convex pentagons. Pure and only used by the tests (the renderer never needs faces). */
    fun countFaces(mesh: Mesh, sides: Int, star: Boolean = false): Int {
        val n = mesh.verts.size
        val adj = Array(n) { HashSet<Int>() }
        for (e in mesh.edges) { adj[e[0]].add(e[1]); adj[e[1]].add(e[0]) }
        val path = IntArray(sides)
        var count = 0
        fun close(): Boolean {
            if (path[1] >= path[sides - 1]) return false   // each cycle once (direction)
            if (path[0] !in adj[path[sides - 1]]) return false
            for (i in 0 until sides) for (j in i + 2 until sides) {
                if (i == 0 && j == sides - 1) continue
                if (path[j] in adj[path[i]]) return false   // a chord: not a face boundary
            }
            if (!coplanar(Array(sides) { mesh.verts[path[it]] })) return false
            if (sides == 5) {
                val side = dist2(mesh.verts[path[0]], mesh.verts[path[1]])
                val diag = dist2(mesh.verts[path[0]], mesh.verts[path[2]])
                if ((diag < side) != star) return false
            }
            return true
        }
        fun rec(len: Int) {
            if (len == sides) { if (close()) count++; return }
            for (w in adj[path[len - 1]]) {
                if (w <= path[0]) continue
                var seen = false
                for (i in 0 until len) if (path[i] == w) { seen = true; break }
                if (seen) continue
                path[len] = w; rec(len + 1)
            }
        }
        for (v in 0 until n) { path[0] = v; rec(1) }
        return count
    }

    private fun coplanar(pts: Array<FloatArray>): Boolean {
        val p0 = pts[0]
        val vs = (1 until pts.size).map { i -> DoubleArray(4) { k -> (pts[i][k] - p0[k]).toDouble() } }
        val scale = vs.maxOf { v -> sqrt(v.sumOf { it * it }) }
        val ob = ArrayList<DoubleArray>()
        for (v in vs) {
            val w = v.copyOf()
            for (o in ob) { var d = 0.0; for (k in 0..3) d += w[k] * o[k]; for (k in 0..3) w[k] -= d * o[k] }
            val nn = sqrt(w.sumOf { it * it })
            if (nn > 1e-4 * scale) {
                if (ob.size == 2) return false
                ob.add(DoubleArray(4) { w[it] / nn })
            }
        }
        return true
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

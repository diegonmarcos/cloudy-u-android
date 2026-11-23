package com.diegonmarcos.cloudcanvas.painter

import java.io.File
import org.json.JSONObject

/**
 * The Painter page's storage (#1808): one JSON file, `<dir>/painter.json`, holding what the page encodes (assets/painter/grid.js
 * `encode`): `{v:1, shape, mode, layout, scale, color, frames?, painted:{<shape>:{...}}}`, the painted cells per shape (cell key ->
 * #rrggbb) plus the colour, mode, grid scale, 2D/3D choice and (#1823) the optional Hide-frames mode. Written whole or not at all (temp file + rename), so a kill mid-save
 * keeps the previous state. Only a well-formed state is accepted, so the page never loads garbage. Pure JVM: the page's bridge
 * (PainterScreen) calls it, the unit suite drives it directly.
 */
class PainterStore(private val dir: File) {
    private val file = File(dir, FILE)

    init { dir.mkdirs() }

    /** The stored state, or null when nothing was saved yet (or the file is unreadable: the page then starts from its defaults). */
    @Synchronized fun read(): String? = file.takeIf { it.isFile }?.let { f -> runCatching { f.readText() }.getOrNull()?.takeIf { valid(it) } }

    /** Saves [state] after checking its shape (see [valid]). */
    @Synchronized fun write(state: String) {
        require(state.length <= MAX_CHARS) { "painter state too large" }
        require(valid(state)) { "not a painter state" }
        dir.mkdirs()
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(state)
        if (!tmp.renameTo(file)) { file.delete(); require(tmp.renameTo(file)) { "could not replace $FILE" } }
    }

    /** Drops the stored state (the page then shows the defaults). */
    @Synchronized fun reset() { file.delete() }

    companion object {
        const val FILE = "painter.json"
        const val MAX_CHARS = 4_000_000
        /** The four prototype grids first (same order), then the #1823 tilings. */
        val SHAPES = listOf("hex", "square", "triangle", "octagon", "rhombille", "trihexagonal", "snubsquare", "cairo", "elongated")
        val FRAMES = listOf("off", "between", "outside", "all")
        val MODES = listOf("paint", "rubber", "view")
        val LAYOUTS = listOf("2d", "3d")
        private val PREFIX = mapOf(
            "hex" to setOf("H"), "square" to setOf("S"), "triangle" to setOf("T"), "octagon" to setOf("O", "OS"),
            "rhombille" to setOf("RA", "RB", "RC"), "trihexagonal" to setOf("XH", "XT", "XU"),
            "snubsquare" to setOf("NA", "NB", "NC", "ND", "NE", "NF"), "cairo" to setOf("CA", "CB", "CC", "CD"), "elongated" to setOf("EQ", "EU", "ED"),
        )
        private val KEY = Regex("^[A-Z]{1,2},-?\\d{1,3},-?\\d{1,3}$")
        private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

        /** Version 1, a known shape, mode and layout, a scale 15..100, a #rrggbb colour, an optional Hide-frames mode, and every painted key of its own shape with a colour. */
        fun valid(state: String): Boolean = runCatching {
            val o = JSONObject(state)
            if (o.optInt("v") != 1) return false
            if (o.optString("shape") !in SHAPES || o.optString("mode") !in MODES || o.optString("layout") !in LAYOUTS) return false
            val scale = o.optDouble("scale", Double.NaN)
            if (scale.isNaN() || scale < 15 || scale > 100) return false
            if (!COLOR.matches(o.optString("color"))) return false
            if (o.has("frames") && o.optString("frames") !in FRAMES) return false
            val painted = o.optJSONObject("painted") ?: return false
            for (s in painted.keys()) {
                if (s !in SHAPES) return false
                val m = painted.optJSONObject(s) ?: return false
                for (k in m.keys()) {
                    if (!KEY.matches(k) || k.substringBefore(',') !in PREFIX.getValue(s)) return false
                    if (!COLOR.matches(m.optString(k))) return false
                }
            }
            true
        }.getOrDefault(false)

        /** How many cells of [shape] are painted in [state] (0 for an invalid state). */
        fun activeCount(state: String, shape: String): Int = runCatching {
            if (!valid(state)) 0 else JSONObject(state).getJSONObject("painted").optJSONObject(shape)?.length() ?: 0
        }.getOrDefault(0)
    }
}

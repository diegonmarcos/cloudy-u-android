package com.diegonmarcos.superapp.vision

import org.json.JSONArray
import org.json.JSONObject

enum class FilterKind { HAT, GLASSES, MASK, BACKGROUND, LUT, STICKER }

/** Where a piece of art sits: a landmark of the face (or of the object's box for a sticker), an offset and a scale in units of [unit], a pivot in the art. */
data class AnchorDecl(
    val landmark: String, val offsetX: Float, val offsetY: Float, val scale: Float, val unit: String,
    val rotate: Boolean, val rotationDegrees: Float, val pivotX: Float, val pivotY: Float,
)

/** One vector shape in the art's own viewport; [fill] / [stroke] are ARGB ints, null for none. */
data class ArtPath(val d: String, val fill: Int?, val stroke: Int?, val strokeWidth: Float, val evenOdd: Boolean)

/** Filter art: vector paths in a [width] x [height] viewport, or a bundled PNG asset ([png]) drawn into the same viewport. */
data class Art(val width: Float, val height: Float, val paths: List<ArtPath>, val png: String?)

/** Background effect: blur (the frame shrunk by [blurDownscale] and stretched back), a flat colour, or a vertical gradient from [color] to [color2]. */
data class BackgroundDecl(val mode: String, val blurDownscale: Int, val color: Int, val color2: Int)

/** One declared filter. Overlays (hat, glasses, mask, sticker) carry [anchor] and [art]; a sticker also lists the object [labels] it follows (empty = any); background and lut carry their own block. */
data class FilterDecl(
    val id: String, val label: String, val kind: FilterKind,
    val anchor: AnchorDecl?, val art: Art?, val labels: List<String>,
    val background: BackgroundDecl?, val lut: LutDecl?,
)

/** The declared filters, read from data (assets/vr-filters.json): nothing about a filter is typed into code. */
object FilterCatalog {
    /** Parses and VALIDATES a catalogue; an unknown kind or landmark, a missing anchor or art, a duplicate id throws IllegalArgumentException naming it. */
    fun parse(json: String): List<FilterDecl> {
        val arr = JSONObject(json).getJSONArray("filters")
        val out = ArrayList<FilterDecl>()
        for (i in 0 until arr.length()) out += filter(arr.getJSONObject(i))
        val dup = out.groupBy { it.id }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate filter ids: $dup" }
        require(out.isNotEmpty()) { "the catalogue declares no filters" }
        return out
    }

    fun load(stream: java.io.InputStream): List<FilterDecl> = parse(stream.bufferedReader().use { it.readText() })

    private fun filter(o: JSONObject): FilterDecl {
        val id = o.optString("id").also { require(it.isNotBlank()) { "a filter has no id" } }
        val kind = runCatching { FilterKind.valueOf(o.optString("kind").uppercase()) }
            .getOrElse { throw IllegalArgumentException("filter '$id': unknown kind '${o.optString("kind")}'") }
        val label = o.optString("label", id)
        return when (kind) {
            FilterKind.HAT, FilterKind.GLASSES, FilterKind.MASK, FilterKind.STICKER -> {
                val anchor = anchor(id, kind, o.optJSONObject("anchor") ?: throw IllegalArgumentException("filter '$id': no anchor"))
                val art = art(id, o.optJSONObject("art") ?: throw IllegalArgumentException("filter '$id': no art"))
                val labels = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
                FilterDecl(id, label, kind, anchor, art, labels, null, null)
            }
            FilterKind.BACKGROUND -> {
                val b = o.optJSONObject("background") ?: throw IllegalArgumentException("filter '$id': no background block")
                val mode = b.optString("mode")
                require(mode in setOf("blur", "color", "gradient")) { "filter '$id': unknown background mode '$mode'" }
                FilterDecl(id, label, kind, null, null, emptyList(),
                    BackgroundDecl(mode, b.optInt("blur_downscale", 14).coerceAtLeast(2), color(b.optString("color", "#FF000000")), color(b.optString("color2", b.optString("color", "#FF000000")))), null)
            }
            FilterKind.LUT -> {
                val l = o.optJSONObject("lut") ?: throw IllegalArgumentException("filter '$id': no lut block")
                fun three(name: String, def: Float): List<Float> {
                    val a = l.optJSONArray(name) ?: return listOf(def, def, def)
                    require(a.length() == 3) { "filter '$id': lut.$name needs 3 values" }
                    return (0 until 3).map { a.getDouble(it).toFloat() }
                }
                val gamma = l.optDouble("gamma", 1.0).toFloat()
                require(gamma > 0f) { "filter '$id': lut.gamma must be > 0" }
                FilterDecl(id, label, kind, null, null, emptyList(), null,
                    LutDecl(gamma, l.optDouble("contrast", 1.0).toFloat(), three("gain", 1f), three("lift", 0f), l.optBoolean("mono", false)))
            }
        }
    }

    private fun anchor(id: String, kind: FilterKind, a: JSONObject): AnchorDecl {
        val landmark = a.optString("landmark")
        val valid = if (kind == FilterKind.STICKER) FilterAnchors.OBJECT_LANDMARKS else FilterAnchors.FACE_LANDMARKS
        require(landmark in valid) { "filter '$id': landmark '$landmark' is not one of $valid" }
        val unit = a.optString("unit", "face")
        require(unit in FilterAnchors.UNITS) { "filter '$id': unit '$unit' is not one of ${FilterAnchors.UNITS}" }
        val scale = a.optDouble("scale", 0.0).toFloat()
        require(scale > 0f) { "filter '$id': anchor.scale must be > 0" }
        val off = a.optJSONArray("offset")
        val pivot = a.optJSONArray("pivot")
        return AnchorDecl(
            landmark, off?.optDouble(0, 0.0)?.toFloat() ?: 0f, off?.optDouble(1, 0.0)?.toFloat() ?: 0f, scale, unit,
            a.optBoolean("rotate", true), a.optDouble("rotation", 0.0).toFloat(),
            pivot?.optDouble(0, 0.5)?.toFloat() ?: 0.5f, pivot?.optDouble(1, 0.5)?.toFloat() ?: 0.5f,
        )
    }

    private fun art(id: String, a: JSONObject): Art {
        val vp = a.optJSONArray("viewport") ?: throw IllegalArgumentException("filter '$id': art has no viewport")
        val w = vp.optDouble(0, 0.0).toFloat(); val h = vp.optDouble(1, 0.0).toFloat()
        require(w > 0f && h > 0f) { "filter '$id': art viewport must be positive" }
        val png = a.optString("png").takeIf { it.isNotBlank() }
        val paths = a.optJSONArray("paths")?.let { p ->
            (0 until p.length()).map { i ->
                val o = p.getJSONObject(i)
                val d = o.optString("d")
                require(d.isNotBlank()) { "filter '$id': an art path has no d" }
                ArtPath(d, o.optString("fill").takeIf { it.isNotBlank() }?.let { color(it) }, o.optString("stroke").takeIf { it.isNotBlank() }?.let { color(it) },
                    o.optDouble("stroke_width", 0.0).toFloat(), o.optString("fill_rule") == "evenodd")
            }
        }.orEmpty()
        require(png != null || paths.isNotEmpty()) { "filter '$id': art has neither paths nor a png" }
        return Art(w, h, paths, png)
    }

    /** "#RRGGBB" or "#AARRGGBB" to an ARGB int (no android.graphics.Color: this runs on the JVM). */
    fun color(s: String): Int {
        val hex = s.removePrefix("#")
        require(hex.length == 6 || hex.length == 8) { "bad colour '$s'" }
        val v = hex.toLong(16)
        return (if (hex.length == 6) 0xFF000000L or v else v).toInt()
    }
}

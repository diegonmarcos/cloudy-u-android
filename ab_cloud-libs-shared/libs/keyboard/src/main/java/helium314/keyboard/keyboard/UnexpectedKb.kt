// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The "Unexpected" theme's keyboard. The keys, their centre glyph and their four corner glyphs (and the compass key's eight
 * directions) are DATA: assets/unexpected_kb/layout.json, two layers (base, fn). This object parses that file and derives from it
 * (1) the HeliBoard main and functional layout JSON the keyboard is built from, (2) the corner-legend / swipe table the key view and
 * the pointer tracker use. Nothing about a key is written in a view. Original implementation.
 */
object UnexpectedKb {
    const val ASSET = "unexpected_kb/layout.json"
    /** Prefs: the theme is on (the CornerSwipe flag is on with it). */
    const val PREF = "pref_unexpected_kb"
    const val MAIN_BASE = "unexpected_kb"
    const val MAIN_FN = "unexpected_kb_fn"
    const val FUNCTIONAL = "functional_keys_unexpected"
    const val COMPASS = "⦶"
    /**
     * Key height of the design relative to the stock keyboard. Measured on the 1080 px wide comparison: the design's five rows span 818 px,
     * the first pass (factor 1.16) spanned 690 px, so 1.16 * 818 / 690.
     */
    const val HEIGHT_FACTOR = 1.375f
    /** Centre glyph size relative to the stock key text, taking the taller keys into account (the design's 't' is 40 px tall; the pass before measured 42 px). */
    const val TEXT_FACTOR = 1.14f
    /** Corner legend size relative to the stock hint text (the design's '$' is 18 x 34 px; measured 17 x 30 at 1.52). */
    const val LEGEND_FACTOR = 1.66f

    /** Theme on (mirrored from the prefs by [CornerSwipe.refresh]). */
    @JvmField @Volatile var active = false
    /** Fn layer showing (Fn modifier on). */
    @JvmField @Volatile var fn = false

    // slots of a key's corner array
    const val NW = 0; const val NE = 1; const val SW = 2; const val SE = 3; const val N = 4; const val S = 5; const val W = 6; const val E = 7
    const val SLOTS = 8
    private val slotNames = listOf("nw", "ne", "sw", "se", "n", "s", "w", "e")

    /** A corner: [label] drawn, [action] sent ("" = display only). */
    data class Corner(val label: String, val action: String)

    data class KeyDef(val id: String, val center: String, val action: String?, val width: Float, val corners: List<Corner?>, val centerLegend: String?)

    data class Layer(val rows: List<List<KeyDef>>, val special: List<KeyDef>) {
        val all: List<KeyDef> get() = rows.flatten() + special
        fun special(id: String): KeyDef? = special.firstOrNull { it.id == id }
    }

    data class Layout(val name: String, val layers: Map<String, Layer>) {
        fun layer(fn: Boolean): Layer = layers.getValue(if (fn) "fn" else "base")
    }

    /** Action names a corner may carry besides a plain glyph: label shown by default. */
    val defaultLabels = mapOf(
        "esc" to "Esc", "tab" to "⇥", "left" to "←", "right" to "→", "up" to "↑", "down" to "↓", "insert" to "Ins",
        "home" to "⇱", "end" to "⇲", "pgup" to "⇞", "pgdn" to "⇟", "fdel" to "⌦", "clipboard" to "⎘", "symbols" to "123+",
        "emoji" to "☺", "settings" to "⚙", "voice" to "🎤", "ime_picker" to "⌨", "select" to "⇔",
    ) + (1..12).associate { "f$it" to "F$it" }

    const val ICON_PREFIX = "!icon/"

    /** Text stand-ins for the icon legends ("!icon/<name>"), used where no icon can be drawn (the Themes preview, a missing drawable). */
    val iconFallback = mapOf(
        "clipboard" to "⎘", "emoji" to "☺", "settings" to "⚙", "voice" to "♪", "language_switch_key" to "⌨",
        "unexp_keyboard" to "⌨", "unexp_home" to "⇱", "unexp_end" to "⇲", "unexp_pgup" to "⇞", "unexp_pgdn" to "⇟", "unexp_select" to "⇔",
    )

    /** Icons of this theme's own (res/drawable/ic_unexp_*), by legend name; the other legends use the keyboard's icon set. */
    @JvmStatic fun ownIconRes(name: String): Int = when (name) {
        "unexp_keyboard" -> helium314.keyboard.latin.R.drawable.ic_unexp_keyboard
        "unexp_home" -> helium314.keyboard.latin.R.drawable.ic_unexp_home
        "unexp_end" -> helium314.keyboard.latin.R.drawable.ic_unexp_end
        "unexp_pgup" -> helium314.keyboard.latin.R.drawable.ic_unexp_pgup
        "unexp_pgdn" -> helium314.keyboard.latin.R.drawable.ic_unexp_pgdn
        "unexp_select" -> helium314.keyboard.latin.R.drawable.ic_unexp_select
        else -> 0
    }

    /** [label] as plain text: an icon legend becomes its stand-in glyph. */
    @JvmStatic fun textOf(label: String): String =
        if (label.startsWith(ICON_PREFIX)) iconFallback[label.removePrefix(ICON_PREFIX)] ?: "" else label

    private fun corner(e: JsonElement?): Corner? {
        if (e == null) return null
        if (e is JsonPrimitive) return e.content.takeIf { it.isNotEmpty() }?.let { Corner(defaultLabels[it] ?: it, it) }
        val o = e.jsonObject
        val a = o["a"]?.jsonPrimitive?.content ?: ""
        val l = o["l"]?.jsonPrimitive?.content ?: defaultLabels[a] ?: a
        return Corner(l, a)
    }

    private fun key(o: JsonObject): KeyDef {
        val center = o["c"]?.jsonPrimitive?.content ?: ""
        val id = o["id"]?.jsonPrimitive?.content ?: center.lowercase()
        return KeyDef(
            id = id, center = center, action = o["a"]?.jsonPrimitive?.content,
            width = o["width"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
            corners = slotNames.map { corner(o[it]) },
            centerLegend = o["center"]?.jsonPrimitive?.content,
        )
    }

    /** Parse the layout data (pure: the unit tests and the runtime read the same text). */
    fun parse(text: String): Layout {
        val root = Json.parseToJsonElement(text).jsonObject
        val layers = root.getValue("layers").jsonObject.mapValues { (_, l) ->
            val lo = l.jsonObject
            Layer(
                rows = lo.getValue("rows").jsonArray.map { r -> r.jsonArray.map { key(it.jsonObject) } },
                special = lo["special"]?.jsonArray?.map { key(it.jsonObject) }.orEmpty(),
            )
        }
        return Layout(root["name"]?.jsonPrimitive?.content ?: "unexpected", layers)
    }

    @Volatile private var cached: Layout? = null
        set(v) { field = v; tables = if (v == null) emptyList() else listOf(false, true).map { l ->
            val all = v.layer(l).all
            // a key is found by its id and, for the special keys, by the glyph it carries as its label (the compass key's)
            all.associateBy { it.id } + all.filter { it.center.isNotEmpty() && it.id != it.center.lowercase() }.associateBy { it.center.lowercase() }
        } }
    /** id -> key per layer (base, fn), rebuilt when the layout is set: the key view asks per key per draw. */
    @Volatile private var tables: List<Map<String, KeyDef>> = emptyList()

    /** The layout read from the app's assets once; null if the file is unreadable (callers then fall back, never crash). */
    fun load(context: Context): Layout? = cached ?: runCatching {
        parse(context.applicationContext.assets.open(ASSET).reader().readText())
    }.getOrNull()?.also { cached = it }

    /** Install an already parsed layout (the unit tests; the app uses [load]). */
    @JvmStatic fun use(layout: Layout) { cached = layout }

    /** The layout loaded so far, for the views (set by [load] on the first layout build). */
    @JvmStatic fun loaded(): Layout? = cached

    private fun table(fnLayer: Boolean): Map<String, KeyDef> = tables.getOrNull(if (fnLayer) 1 else 0).orEmpty()

    /** Key id the table knows for a key with this [label] / [code], or null. */
    @JvmStatic fun idOf(label: String?, code: Int): String? {
        val t = table(fn)
        if (!label.isNullOrEmpty()) return label.lowercase().takeIf { it in t }
        return when (code) { 32 -> "space"; -7 -> "delete"; 10 -> "enter"; else -> null }?.takeIf { it in t }
    }

    @JvmStatic fun keyDef(id: String?): KeyDef? = id?.let { table(fn)[it] }

    /** Display labels of the corner slots (8, "" = none) of [id]. */
    @JvmStatic fun labelsOf(id: String?): List<String> = keyDef(id)?.corners?.map { it?.label ?: "" } ?: emptyList()

    /** Fn layer changed; true if it differs from before. */
    @JvmStatic fun setFn(on: Boolean): Boolean { val changed = fn != on; fn = on; return changed }

    /** The 8 sectors of a move (compass keys) or the 4 quadrants (letter keys), as a slot, or -1 if the move is too short. */
    @JvmStatic fun slotOf(dx: Int, dy: Int, minDistance: Float, eightWay: Boolean): Int {
        if (kotlin.math.hypot(dx.toFloat(), dy.toFloat()) < minDistance) return -1
        if (!eightWay) return if (dy < 0) (if (dx < 0) NW else NE) else (if (dx < 0) SW else SE)
        // angle in degrees, 0 = east, counter-clockwise positive (screen y grows downwards)
        val deg = Math.toDegrees(kotlin.math.atan2(-dy.toDouble(), dx.toDouble()))
        val sector = Math.floorMod(Math.round(deg / 45.0).toInt(), 8)
        return intArrayOf(E, NE, N, NW, W, SW, S, SE)[sector]
    }

    /** The theme's key background (design: ~5 dp corners, thin gaps); sizes in dp scaled by the screen density. */
    @JvmStatic fun keyBackground(): android.graphics.drawable.Drawable {
        val d = android.content.res.Resources.getSystem().displayMetrics.density
        val h = Math.round(1.2f * d); val v = Math.round(0.6f * d)
        return UnexpectedKeyDrawable(5f * d, android.graphics.Rect(h, v, h, v))
    }

    // ---- derived HeliBoard layouts --------------------------------------------------------------------------------------

    private fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun keyJson(k: KeyDef): String {
        val code = k.action?.let { CornerSwipe.codeOf(it) }
        val b = StringBuilder("{\"label\":").append(q(k.center))
        if (code != null && code < 0) b.append(",\"code\":").append(code)
        if (k.width > 0f) b.append(",\"width\":").append(k.width)
        return b.append("}").toString()
    }

    /** HeliBoard main-layout JSON for a layer: the four letter rows (number row first). */
    fun mainLayoutJson(layout: Layout, fnLayer: Boolean): String =
        layout.layer(fnLayer).rows.joinToString(",\n", "[\n", "\n]") { r -> r.joinToString(",", " [", "]") { keyJson(it) } }

    /** HeliBoard functional-layout JSON: shift / delete flanking the last letter row, and the bottom row of the layer. */
    fun functionalJson(layout: Layout, fnLayer: Boolean): String {
        val l = layout.layer(fnLayer)
        fun fk(id: String, label: String, code: String? = null, width: Float? = null) = buildString {
            append("{\"label\":").append(q(label))
            if (code != null) append(",\"code\":").append(code)
            val w = width ?: l.special(id)?.width ?: 0f
            if (w > 0f) append(",\"width\":").append(w)
            append(",\"type\":\"function\"}")
        }
        val bottom = listOf(
            fk("ctrl", "Ctrl", CornerSwipe.codeOf("ctrl").toString()),
            fk("fn", "Fn", helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.FN.toString()),
            "{\"label\":\"space\"}",
            fk("compass", COMPASS),
            "{\"label\":\"action\",\"width\":${l.special("enter")?.width ?: 0.13f},\"labelFlags\":1073741824}",
        )
        return "[\n [{\"label\":\"shift\",\"width\":0.15},{\"type\":\"placeholder\"},{\"label\":\"delete\",\"width\":0.15}],\n [" +
            bottom.joinToString(",") + "]\n]"
    }

    /** Layout text for a built-in generated layout name, or null if [name] is not one of ours. */
    fun contentFor(name: String, context: Context): String? {
        val fnLayer = when (name) { MAIN_BASE, FUNCTIONAL -> false; MAIN_FN -> true; else -> return null }
        val layout = load(context) ?: return null
        return if (name == FUNCTIONAL) functionalJson(layout, fn) else mainLayoutJson(layout, fnLayer)
    }
}

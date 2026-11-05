package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.BuildConfig
import org.json.JSONObject

/**
 * The home status bar as DATA (build.json::home_status_bar). [LauncherStatusStrip] draws exactly what [StatusBarDecl.layout]
 * returns, cluster by cluster, so the declaration is the single source of truth for what is on the bar, where, in which
 * order and whether it is visible. No element is named in the drawing code's loops any more.
 */
data class StatusElement(
    val id: String, val label: String,
    /** network | clock | metric | battery: picks the drawing. */
    val kind: String,
    /** left | centre | right. */
    val position: String,
    val order: Int, val visible: Boolean,
    /** The Zoomies pet tool above the element, or null. */
    val pet: String?,
    /** What a tap opens: network_popup | system_popup | battery_popup | tasks_popup. */
    val opens: String,
    /** The clock's SimpleDateFormat pattern (clock only). */
    val format: String? = null,
)

/** #1508 The speed rows of the network dropdown (build.json::home_status_bar.network_speed): `rows` are ids now | avg24. */
data class NetworkSpeedDecl(val sampleMs: Long, val rows: List<Pair<String, String>>, val perInterface: Boolean)

data class StatusBarDeclaration(
    val rowHeightDp: Int, val elements: List<StatusElement>,
    /** Read-only: the toolbar island's pills, listed for the settings section; the strip does not draw them. */
    val toolbarRow: List<StatusElement>,
    /** The speed meter rows inside the network dropdown; null = none declared. */
    val networkSpeed: NetworkSpeedDecl? = null,
)

object StatusBarDecl {
    const val LEFT = "left"; const val CENTRE = "centre"; const val RIGHT = "right"
    val POSITIONS: List<String> = listOf(LEFT, CENTRE, RIGHT)

    /** Parse build.json::home_status_bar. A malformed declaration throws: an empty status bar must not ship silently. */
    fun parse(json: String): StatusBarDeclaration {
        val o = JSONObject(json)
        val arr = o.getJSONArray("elements")
        val els = (0 until arr.length()).map { i ->
            val e = arr.getJSONObject(i)
            StatusElement(
                id = e.getString("id"), label = e.getString("label"), kind = e.getString("kind"),
                position = e.getString("position").also { require(it in POSITIONS) { "status element ${e.getString("id")}: position '$it'" } },
                order = e.getInt("order"), visible = e.optBoolean("visible", true),
                pet = if (e.isNull("pet")) null else e.getString("pet"),
                opens = e.getString("opens"),
                format = if (e.has("format")) e.getString("format") else null,
            )
        }
        require(els.map { it.id }.toSet().size == els.size) { "home_status_bar: duplicate element id" }
        val tb = o.optJSONArray("toolbar_row")
        val toolbar = if (tb == null) emptyList() else (0 until tb.length()).map { i ->
            val e = tb.getJSONObject(i)
            StatusElement(e.getString("id"), e.getString("label"), "pill", e.getString("position"), e.getInt("order"), true, null, "")
        }
        val ns = o.optJSONObject("network_speed")?.let { n ->
            val r = n.getJSONArray("rows")
            NetworkSpeedDecl(n.getLong("sample_ms"), (0 until r.length()).map { i -> r.getJSONObject(i).let { it.getString("id") to it.getString("label") } },
                n.optBoolean("per_interface", true))
        }
        return StatusBarDeclaration(o.getInt("row_height_dp"), els, toolbar, ns)
    }

    /**
     * The bar as it is drawn: position to its visible elements, in order. [hidden] = ids the owner switched off (or on, with
     * false) over the declared visibility; [orders] = a cluster's ids in the owner's order, which lead their cluster, any
     * element the list does not name following in declared order.
     */
    fun layout(
        decl: StatusBarDeclaration, hidden: Map<String, Boolean> = emptyMap(), orders: Map<String, List<String>> = emptyMap(),
    ): Map<String, List<StatusElement>> = POSITIONS.associateWith { pos ->
        ordered(decl, pos, orders).filter { !(hidden[it.id] ?: !it.visible) }
    }

    /** Every element of [position], visible or not, in the order the bar uses (the settings list shows them all). */
    fun ordered(decl: StatusBarDeclaration, position: String, orders: Map<String, List<String>> = emptyMap()): List<StatusElement> {
        val declared = decl.elements.filter { it.position == position }.sortedBy { it.order }
        val mine = orders[position].orEmpty()
        val first = mine.mapNotNull { id -> declared.firstOrNull { it.id == id } }
        return first + declared.filter { e -> first.none { it.id == e.id } }
    }

    /** [ids] with the one at [index] moved by [delta] (-1 up, +1 down); unchanged at either end. */
    fun move(ids: List<String>, index: Int, delta: Int): List<String> {
        val to = index + delta
        if (index !in ids.indices || to !in ids.indices) return ids
        return ids.toMutableList().also { val x = it.removeAt(index); it.add(to, x) }
    }

    /** The declaration baked into the build. */
    val declared: StatusBarDeclaration by lazy {
        parse(String(Base64.decode(BuildConfig.STATUS_BAR_B64, Base64.DEFAULT), Charsets.UTF_8))
    }
}

/**
 * The owner's runtime overrides of the declared bar: a visibility switch per element and an order per cluster, in the
 * `status_bar_prefs` store. [version] is Compose state, bumped on every write, so the strip redraws the moment the Status
 * Bar section changes a switch.
 */
object StatusBarPrefs {
    private const val STORE = "status_bar_prefs"
    private const val VISIBLE = "visible."     // + id : Boolean (the owner's choice)
    private const val ORDER = "order."         // + position : "id,id,id"

    var version by mutableIntStateOf(0)
        private set

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** id to "hidden" (true) for every element the owner has set; the layout inverts the declared default with it. */
    fun hidden(ctx: Context): Map<String, Boolean> =
        sp(ctx).all.filterKeys { it.startsWith(VISIBLE) }.mapNotNull { (k, v) -> (v as? Boolean)?.let { k.removePrefix(VISIBLE) to !it } }.toMap()

    fun orders(ctx: Context): Map<String, List<String>> =
        sp(ctx).all.filterKeys { it.startsWith(ORDER) }
            .mapNotNull { (k, v) -> (v as? String)?.let { k.removePrefix(ORDER) to it.split(',').filter(String::isNotBlank) } }.toMap()

    fun setVisible(ctx: Context, id: String, on: Boolean) { sp(ctx).edit().putBoolean(VISIBLE + id, on).apply(); version++ }

    fun setOrder(ctx: Context, position: String, ids: List<String>) {
        sp(ctx).edit().putString(ORDER + position, ids.joinToString(",")).apply(); version++
    }

    fun reset(ctx: Context) { sp(ctx).edit().clear().apply(); version++ }

    /** The layout the strip draws: the declaration plus these overrides. Reads [version], so a composable calling it redraws. */
    fun layout(ctx: Context): Map<String, List<StatusElement>> {
        @Suppress("UNUSED_VARIABLE") val v = version
        return StatusBarDecl.layout(StatusBarDecl.declared, hidden(ctx), orders(ctx))
    }
}

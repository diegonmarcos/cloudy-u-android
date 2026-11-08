package com.diegonmarcos.superapp.appstore

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Store ▸ Cloud: which buttons a fleet row's detail and its About sheet draw,
 * in which order, under which caption — read from [ASSET], never written in the
 * fragment. The code only maps an [Action.id] to what it does, and an id it has
 * no handler for is drawn as a button that says so rather than dropped.
 *
 * #redesign the row is TWO grouped lines now ([rows]: install/lifecycle, then
 * open/inspect), the install button is a state toggle ([Action.labelInstalled]
 * /[Action.colorInstalled]), and a button may carry a `│` [Action.dividerBefore]
 * rule to set the Open/Stop pair apart from the Perms/Routes/Libs/About set.
 */
object FleetActions {

    const val ASSET = "appstore-fleet-actions.json"

    class Action(
        val id: String, val label: String, val color: Int, val detail: String,
        /** The toggle's caption while the package is installed (install_toggle only), else null. */
        val labelInstalled: String? = null,
        /** The toggle's fill while the package is installed (install_toggle only), else [color]. */
        val colorInstalled: Int = color,
        /** Draw a `│` rule before this button, grouping it apart from the one before. */
        val dividerBefore: Boolean = false,
    )

    /** The row's two lines, in order: [0] install/lifecycle, [1] open/inspect. */
    fun rows(ctx: Context): List<List<Action>> {
        val doc = open(ctx)
        val lines = doc.getJSONArray("rows")
        return (0 until lines.length()).map { parse(lines.getJSONArray(it)) }
    }

    /** The About sheet's own actions (App settings). */
    fun details(ctx: Context): List<Action> = parse(open(ctx).getJSONArray("details"))

    /** The declared caption for [id], wherever it is drawn — so a dialog that
     *  offers "App settings" names it exactly as the sheet does. */
    fun label(ctx: Context, id: String): String =
        (rows(ctx).flatten() + details(ctx)).firstOrNull { it.id == id }?.label ?: id

    private fun open(ctx: Context): JSONObject =
        ctx.assets.open(ASSET).use { JSONObject(it.readBytes().decodeToString()) }

    private fun color(s: String, fallback: String = "0xFF2A2A33"): Int =
        s.ifEmpty { fallback }.removePrefix("0x").toLong(16).toInt()

    private fun parse(arr: JSONArray): List<Action> = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val base = color(o.optString("color"))
        Action(
            o.getString("id"), o.getString("label"), base, o.optString("detail"),
            labelInstalled = o.optString("label_installed").ifEmpty { null },
            colorInstalled = o.optString("color_installed").ifEmpty { null }?.let { color(it) } ?: base,
            dividerBefore = o.optBoolean("divider_before", false),
        )
    }
}

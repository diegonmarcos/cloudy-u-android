package com.diegonmarcos.superapp.appstore

import com.diegonmarcos.superapp.fleetdata.FleetData
import android.content.Context
import org.json.JSONObject

/**
 * #732 — how the Store's three kinds of control LOOK, read from
 * assets/appstore-controls.json. `tab` selects a subset, `page` opens a page,
 * `action` acts on one app. This object reads the declared styles; Modifier.storeSurface (StoreBar.kt) maps one onto a control; it
 * names no style token, colour, icon or caption — those are the asset.
 */
object StoreControls {

    /** #1562 the declaration is a served dataset of Cloud Store (shared), never an APK asset. */
    const val DATASET = "appstore-controls"

    /** One declared style. Colours are ARGB; null means "none". */
    class Style(
        val fill: Int?, val fillActive: Int?,
        val stroke: Int?, val strokeActive: Int?,
        val text: Int, val textActive: Int,
        val radiusDp: Int, val stretch: Boolean, val bold: Boolean, val chevron: String,
    )

    /** One drawn tab or page entry: caption, icon ('' = none) and the style it wears. */
    class Control(val label: String, val icon: String, val style: Style)

    class Decl(private val styles: Map<String, Style>, private val pages: JSONObject,
               groupTabStyle: String, actionStyle: String, filterStyle: String = groupTabStyle,
               pageStyle: String = "") {
        val groupTab: Style = style(groupTabStyle)
        val action: Style = style(actionStyle)
        /** #793 what a filter chip wears: it selects a subset, so it is a tab. */
        val filter: Style = style(filterStyle)
        /** #809 what a page button wears outside line 2 (Apps Mesh's page buttons). */
        val page: Style = style(pageStyle)

        fun style(token: String): Style = styles[token] ?: PLAIN

        /** A line-2 entry by id. [caption] is the feed's own label when the
         *  entry is a feed; the page's own entries (mesh, perms) declare theirs
         *  here. An undeclared id still draws, as plain text with no icon. */
        fun page(id: String, caption: String? = null): Control {
            val p = pages.optJSONObject(id)
            return Control(caption ?: p?.optString("label")?.takeIf { it.isNotEmpty() } ?: id,
                p?.optString("icon").orEmpty(), style(p?.optString("style").orEmpty()))
        }
    }

    /** What a style token nothing declares draws as: a caption, no chrome. */
    private val PLAIN = Style(null, null, null, null, 0x99FFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0, false, false, "")

    fun load(ctx: Context): Decl = runCatching {
        FleetData.get(ctx, DATASET) { parse(JSONObject(it)) }
    }.getOrNull() ?: parse(JSONObject())

    fun parse(decl: JSONObject): Decl {
        val s = decl.optJSONObject("styles") ?: JSONObject()
        val styles = s.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
            val o = s.getJSONObject(k)
            Style(argb(o, "fill"), argb(o, "fill_active"), argb(o, "stroke"), argb(o, "stroke_active"),
                argb(o, "text") ?: PLAIN.text, argb(o, "text_active") ?: argb(o, "text") ?: PLAIN.textActive,
                o.optInt("radius_dp"), o.optBoolean("stretch"), o.optBoolean("bold"), o.optString("chevron"))
        }
        return Decl(styles, decl.optJSONObject("pages") ?: JSONObject(),
            decl.optString("group_tab_style"), decl.optString("action_style"),
            decl.optString("filter_style", decl.optString("group_tab_style")),
            decl.optString("page_style"))
    }

    private fun argb(o: JSONObject, key: String): Int? =
        o.optString(key).takeIf { it.startsWith("0x") }?.removePrefix("0x")?.toLongOrNull(16)?.toInt()
}

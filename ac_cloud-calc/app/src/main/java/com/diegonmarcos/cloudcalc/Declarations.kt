package com.diegonmarcos.cloudcalc

import com.diegonmarcos.superapp.bottomnav.NavDecl
import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::ui, decoded once. app/build.gradle bakes ui.bottom_nav + ui.sections (with their
 * `pages`) + ui.default_section and ui.modes into BuildConfig; this object is their only reader,
 * so no section id, page id, mode id, key, form or category is spelled anywhere in Kotlin. #868
 * the navigation is libs:bottomnav's [NavDecl]: the sections are the island, a section's pages
 * are the old "tabs". [parseNav] / [parseModes] are pure so the JVM suite runs them against this
 * repository's own build.json.
 */
object Declarations {
    /** #770/#868 a top-level section (Calculator, Measure, Jev): a bottom-nav item; its pages (tabs) are the top strip. */
    data class Section(val id: String, val label: String, val icon: String)
    data class Tab(val id: String, val label: String, val icon: String, val section: String)

    /** A keypad key: [label] is drawn, [insert] is typed. AC, DEL and = are the three actions. */
    data class Key(val label: String, val insert: String) {
        val action: Action? get() = when (label) {
            "AC" -> Action.CLEAR
            "DEL" -> Action.DELETE
            "=" -> Action.EVALUATE
            else -> null
        }
    }
    enum class Action { CLEAR, DELETE, EVALUATE }

    /** A choice field's option: [value] is what the form holds (and an expression sees), [label] what the menu shows. */
    data class Option(val value: String, val label: String)
    /**
     * A form field. [type] picks its widget: text (default), date (a tappable calendar; [rangeEnd] names the field that ends a RANGE
     * started here, [calendarOpen] opens the calendar at once), toggle ("true"/"false"), choice ([options], or [optionsFrom] a
     * data source the screen resolves; [defaultFrom] a device value the default comes from).
     */
    data class Field(
        val id: String, val label: String, val default: String,
        val type: String = "text", val options: List<Option> = emptyList(), val optionsFrom: String = "",
        val defaultFrom: String = "", val rangeEnd: String = "", val calendarOpen: Boolean = false,
    )
    /** An engine output has an [expr]; one the app counts has a [calc] (and a [metric]); [labelOn]/[labelOff] follow the form's end toggle. */
    data class Output(
        val label: String, val expr: String,
        val calc: String = "", val metric: String = "", val labelOn: String = "", val labelOff: String = "",
    )
    /** #1377 a form with a [view] draws that screen (ui/ToolScreens.kt) instead of its fields and outputs. */
    data class Form(val id: String, val label: String, val fields: List<Field>, val outputs: List<Output>, val bind: Map<String, String> = emptyMap(), val view: String = "")

    /** One chip of a choice row: picking it sets eval option [key] to [value]. */
    data class Choice(val label: String, val key: String, val value: Int)
    data class CatalogSource(val kind: String, val category: String)
    data class Plot(val default: String, val xmin: Double, val xmax: Double, val steps: Int)
    /** A unit the app converts itself (Network / Data): [factor] is how many base units (bit, bit/s) one of it is. */
    data class DataUnit(val name: String, val label: String, val factor: java.math.BigDecimal)
    /** One category of a data_converter: its units in picker order and the pair it opens on. */
    data class UnitSet(val category: String, val from: String, val to: String, val units: List<DataUnit>) {
        fun unit(name: String): DataUnit? = units.firstOrNull { it.name == name }
    }
    /** The "how long to transfer" chip: a size from the [size] set over a rate from the [rate] set. */
    data class Transfer(val category: String, val size: String, val rate: String, val sizeValue: String, val sizeUnit: String, val rateValue: String, val rateUnit: String)
    /** #772 the sample rate and the calibration offset are build.json::sound's, shared by every sound mode. */
    data class Meter(val fftSize: Int, val refreshMs: Int, val aWeighting: Boolean)

    data class Mode(
        val id: String,
        val label: String,
        val tab: String,
        val kind: String,
        /** The engine's eval options, as the JSON the engine parses (libs/calc EvalOptions). */
        val options: String,
        val keys: List<List<Key>>,
        val angleChoices: List<Choice>,
        val baseChoices: List<Choice>,
        val showBases: List<Choice>,
        val forms: List<Form>,
        val categories: List<String>,
        val rates: Boolean,
        /** The currencies pinned first, shown in the matrix and kept fresh (build.json::ui.modes[currency].favourites). */
        val favourites: List<String>,
        val defaults: Map<String, String>,
        val catalog: List<CatalogSource>,
        val plot: Plot?,
        val meter: Meter?,
        val historyMax: Int,
        /** A Clock mode's `clock` object as JSON (#768); ClockDecl reads it, nothing else does. */
        val clock: String,
        /** The mode-row icon name (IconCatalog) and its short label; blank = the page's icon and the label. */
        val icon: String = "",
        val short: String = "",
        /** A data_converter's own units (build.json::ui.modes[].unit_sets), exact factors, no engine. */
        val unitSets: List<UnitSet> = emptyList(),
        val transfer: Transfer? = null,
        /** Search terms of a Convert mode ("term", "term|unit" or "term|unit|category"): what ConvertSearch finds besides its label, categories and units. */
        val search: List<String> = emptyList(),
        /** The mode's own `spec` object as JSON: what a Toolkit or Sensors mode reads (its group, sensor ids, limits, deep-link pages); "{}" when it declares none. */
        val spec: String = "{}",
    )

    val nav: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }
    /** The AI section's id in ui.sections: the first row's AI button opens it (the AI page is on the island). */
    const val AI_SECTION = "ai"
    /** The bar's sections, in `ui.bottom_nav` order. */
    val sections: List<Section> by lazy { sectionsOf(nav) }
    /** Every page of every section, in declared order (what #770 called ui.tabs). */
    val tabs: List<Tab> by lazy { tabsIn(nav) }
    val modes: List<Mode> by lazy { parseModes(decode(BuildConfig.UI_MODES_B64)) }
    /** The page of the section that is NOT on the bar (Configs): the round top-right button opens it. Null when every section is on the bar. */
    val configsTab: Tab? get() = nav.sections.firstOrNull { it.id !in nav.bottomNav }?.let { s -> tabs.firstOrNull { it.section == s.id } }
    /** Where the app opens: the default section's first page. */
    val defaultTab: String get() = nav.default()?.pages?.firstOrNull()?.id ?: tabs.first().id

    fun modesOf(tab: String): List<Mode> = modes.filter { it.tab == tab }
    fun tabsOf(section: String): List<Tab> = tabs.filter { it.section == section }
    fun sectionOf(tab: String): String = tabs.firstOrNull { it.id == tab }?.section ?: sections.first().id
    fun mode(id: String): Mode? = modes.firstOrNull { it.id == id }

    private fun decode(b64: String): String = String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)

    fun sectionsOf(nav: NavDecl): List<Section> = nav.bottomSections().map { Section(it.id, it.label, it.icon) }

    fun tabsIn(nav: NavDecl): List<Tab> =
        nav.sections.flatMap { s -> s.pages.map { Tab(it.id, it.label, it.icon, s.id) } }

    /** [sections] is ui.sections' JSON, [bottomNav] ui.bottom_nav as a comma list. */
    fun parseNav(sections: String, bottomNav: String, defaultSection: String): NavDecl =
        NavDecl.parse(JSONArray(sections), bottomNav, defaultSection)

    fun parseModes(json: String): List<Mode> = JSONArray(json).objects().map { m ->
        Mode(
            id = m.getString("id"),
            label = m.getString("label"),
            tab = m.getString("tab"),
            kind = m.getString("kind"),
            options = (m.optJSONObject("options") ?: JSONObject()).toString(),
            keys = (m.optJSONArray("keys") ?: JSONArray()).let { rows ->
                (0 until rows.length()).map { r -> rows.getJSONArray(r).let { row -> (0 until row.length()).map { key(row.get(it)) } } }
            },
            angleChoices = choices(m.optJSONArray("angle_choices")),
            baseChoices = choices(m.optJSONArray("base_choices")),
            showBases = choices(m.optJSONArray("show_bases")),
            forms = (m.optJSONArray("forms") ?: JSONArray()).objects().map { f ->
                Form(
                    f.getString("id"), f.getString("label"),
                    f.getJSONArray("fields").objects().map { fld ->
                        Field(
                            fld.getString("id"), fld.getString("label"), fld.optString("default"),
                            type = fld.optString("type", "text"),
                            options = (fld.optJSONArray("options") ?: JSONArray()).objects().map { Option(it.getString("value"), it.getString("label")) },
                            optionsFrom = fld.optString("options_from"), defaultFrom = fld.optString("default_from"),
                            rangeEnd = fld.optString("range_end"), calendarOpen = fld.optBoolean("calendar_open", false),
                        )
                    },
                    f.getJSONArray("outputs").objects().map {
                        Output(it.getString("label"), it.optString("expr"), it.optString("calc"), it.optString("metric"), it.optString("label_on"), it.optString("label_off"))
                    },
                    (f.optJSONObject("bind") ?: JSONObject()).let { b -> b.keys().asSequence().associateWith { k -> b.getString(k) } },
                    f.optString("view"),
                )
            },
            categories = (m.optJSONArray("categories") ?: JSONArray()).strings(),
            rates = m.optBoolean("rates", false),
            favourites = (m.optJSONArray("favourites") ?: JSONArray()).strings(),
            defaults = (m.optJSONObject("default") ?: JSONObject()).let { d ->
                d.keys().asSequence().associateWith { d.get(it).toString() }
            },
            catalog = (m.optJSONArray("catalog") ?: JSONArray()).objects().map {
                CatalogSource(it.getString("kind"), it.getString("category"))
            },
            plot = m.optJSONObject("plot")?.let {
                Plot(it.optString("default"), it.optDouble("xmin", -10.0), it.optDouble("xmax", 10.0), it.optInt("steps", 200))
            },
            meter = m.optJSONObject("meter")?.let {
                Meter(it.optInt("fft_size", 4096), it.optInt("refresh_ms", 100), it.optBoolean("a_weighting", true))
            },
            historyMax = m.optJSONObject("history")?.optInt("max_entries", 200) ?: 200,
            clock = (m.optJSONObject("clock") ?: JSONObject()).toString(),
            icon = m.optString("icon"),
            short = m.optString("short"),
            unitSets = (m.optJSONArray("unit_sets") ?: JSONArray()).objects().map { u ->
                UnitSet(
                    u.getString("category"), u.getString("from"), u.getString("to"),
                    u.getJSONArray("units").objects().map { DataUnit(it.getString("name"), it.getString("label"), java.math.BigDecimal(it.getString("factor"))) },
                )
            },
            search = (m.optJSONArray("search") ?: JSONArray()).strings(),
            spec = (m.optJSONObject("spec") ?: JSONObject()).toString(),
            transfer = m.optJSONObject("transfer")?.let { t ->
                val d = t.optJSONObject("default") ?: JSONObject()
                Transfer(
                    t.getString("category"), t.getString("size"), t.getString("rate"),
                    d.optString("size", "1"), d.optString("size_unit"), d.optString("rate", "1"), d.optString("rate_unit"),
                )
            },
        )
    }

    private fun key(v: Any): Key =
        if (v is JSONObject) Key(v.getString("label"), v.optString("insert")) else Key(v.toString(), v.toString())

    /** A choice object is {label, <option>: value}; the one non-label field names the option. */
    private fun choices(a: JSONArray?): List<Choice> = (a ?: JSONArray()).objects().map { c ->
        val k = c.keys().asSequence().first { it != "label" }
        Choice(c.getString("label"), k, c.getInt(k))
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
}

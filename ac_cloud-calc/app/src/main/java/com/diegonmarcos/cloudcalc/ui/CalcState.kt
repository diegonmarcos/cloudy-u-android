package com.diegonmarcos.cloudcalc.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.diegonmarcos.cloudcalc.ConvertSearch
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.engine.CalcApi

/** What every tab shares: the selected tab and mode per tab, the history, and a pending insert. */
class CalcState(private val prefs: SharedPreferences?, private val now: () -> Long = System::currentTimeMillis) {
    private var shown by mutableStateOf(Declarations.defaultTab.takeIf { d -> Declarations.tabs.any { it.id == d } } ?: Declarations.tabs.first().id)
    /** The shown page; an old page id (#1409 Tools) lands on the page that replaced it. */
    var tab: String
        get() = shown
        set(v) { shown = Declarations.resolve(v) }
    val modeByTab = mutableStateMapOf<String, String>()
    /** #770 the tab last shown in each section, so switching sections comes back to it. */
    val tabBySection = mutableStateMapOf<String, String>()

    /** The section [tab] belongs to: the segmented row at the top shows it selected. */
    val section: String get() = Declarations.sectionOf(tab)

    /** Show section [id]: its last tab, else its first. An unknown or empty section changes nothing. */
    fun showSection(id: String) {
        val tabs = Declarations.tabsOf(id)
        if (tabs.isEmpty()) return
        tabBySection[section] = tab
        tab = tabBySection[id]?.takeIf { t -> tabs.any { it.id == t } } ?: tabs.first().id
    }
    /** The page the round Configs button came from, so a second press goes back to it. */
    private var configsFrom: String? = null
    val onConfigs: Boolean get() = Declarations.configsTab?.id == tab

    /** The top bar's Settings > Configs: open Configs, remembering the page to go back to; already there changes nothing. */
    fun openConfigs() {
        val c = Declarations.configsTab ?: return
        if (tab != c.id) toggleConfigs()
    }

    /** Open Configs, or from Configs go back to the page it was opened from (Back does the latter). */
    fun toggleConfigs() {
        val c = Declarations.configsTab ?: return
        if (tab == c.id) {
            tab = configsFrom?.takeIf { t -> Declarations.tabs.any { it.id == t } } ?: Declarations.defaultTab
            configsFrom = null
        } else {
            configsFrom = tab
            tab = c.id
        }
    }
    /** #1329 the LaTeX modes' shared state: saved formulas, favourites, the Library -> Editor draft. */
    val latex = LatexModel(prefs, now)

    /** Open the LaTeX Editor on [draft] (the Library's Edit, a LaTeX History entry's tap). */
    fun openLatexEditor(draft: LatexModel.Draft) {
        val m = Declarations.modes.firstOrNull { it.kind == KIND_LATEX_EDITOR } ?: return
        latex.draft = draft
        modeByTab[m.tab] = m.id
        tab = m.tab
    }

    /** #1336 Open the Graph page on [target] (the detail's Graph button). */
    fun openLatexGraph(target: LatexModel.GraphTarget) {
        val m = Declarations.modes.firstOrNull { it.kind == KIND_LATEX_GRAPH } ?: return
        latex.graphTarget = target
        modeByTab[m.tab] = m.id
        tab = m.tab
    }

    val history = mutableStateListOf<Logic.Entry>().apply { addAll(Logic.decode(prefs?.getString(KEY, null))) }

    /** Text a history tap sends to an expression mode: (mode id, text). */
    var pending by mutableStateOf<Pair<String, String>?>(null)

    fun remember(e: Logic.Entry, max: Int) {
        save(Logic.remember(history.toList(), e.copy(ts = if (e.ts != 0L) e.ts else now()), max))
    }

    /** Drop the entry at [index] (newest first); an index outside the list changes nothing. */
    fun deleteHistory(index: Int) {
        if (index !in history.indices) return
        save(history.toList().filterIndexed { i, _ -> i != index })
    }

    private fun save(next: List<Logic.Entry>) {
        history.clear(); history.addAll(next)
        prefs?.edit()?.putString(KEY, Logic.encode(next))?.apply()
    }

    fun clearHistory() {
        history.clear()
        prefs?.edit()?.remove(KEY)?.apply()
    }

    /** A Convert search hit being opened: the converter takes [category] and [unit] (a unit, or a food id) once, then clears it. */
    data class Jump(val modeId: String, val category: String, val unit: String)
    var jump by mutableStateOf<Jump?>(null)

    /** Open the converter a search hit names, on its category and unit. */
    fun open(e: ConvertSearch.Entry) {
        val m = Declarations.mode(e.modeId) ?: return
        modeByTab[m.tab] = m.id
        tab = m.tab
        jump = Jump(e.modeId, e.category, e.unit)
    }

    /** Show [modeId] and hand it [text]: an expression mode, else the first one declared. */
    fun send(modeId: String, text: String) {
        val target = Declarations.mode(modeId)?.takeIf { it.kind == KIND_EXPRESSION }
            ?: Declarations.modes.firstOrNull { it.kind == KIND_EXPRESSION } ?: return
        modeByTab[target.tab] = target.id
        tab = target.tab
        pending = target.id to text
    }

    /** Open mode [modeId] on its tab (a Clock notification tap); an unknown id changes nothing. */
    fun show(modeId: String?) {
        val m = Declarations.mode(modeId ?: return) ?: return
        modeByTab[m.tab] = m.id
        tab = m.tab
    }

    companion object {
        const val KEY = "history"
        const val KIND_EXPRESSION = "expression"
        const val KIND_LATEX_EDITOR = "latex_editor"
        const val KIND_LATEX_GRAPH = "latex_graph"
    }
}

val LocalCalcApi = staticCompositionLocalOf<CalcApi> { error("no CalcApi provided") }
val LocalCalcState = staticCompositionLocalOf<CalcState> { error("no CalcState provided") }

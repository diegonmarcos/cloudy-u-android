@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.bottomnav.PageTabsTags
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudcalc.ConvertSearch
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.themebg.themeBackground
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import androidx.activity.compose.BackHandler

/**
 * The shell (#868, INVERTED from #770): the fleet's bottom-nav island carries the sections
 * (Calc, Sheet, AI, Measure, Jev - build.json::ui.bottom_nav), the section's PAGES (what #770 called
 * tabs, ui.sections[].pages) are the second row, and a page shows its modes (ui.modes
 * whose `tab` is its id) as the sub-strip under it when it has more than one. Above all of it is the
 * fleet's shared first row (#1154, #1157: the breadcrumb title dropdown, AI, Profile, Configs; no hamburger) in
 * NavBarTopFirstRowScaffold, which owns the ONE scroll collapse of the three bars. What the Sheet page used to put in the
 * drawer (its sheets and workbooks, [CalcDrawer]) is listed at the end of the title dropdown.
 */
@Composable
fun CalcShell(api: CalcApi, state: CalcState) {
    CompositionLocalProvider(LocalCalcApi provides api, LocalCalcState provides state) {
        val nav = Declarations.nav
        val sec = nav.section(state.section)
        val entries = nav.islandEntries()
        Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
            NavBarTopFirstRowScaffold(
                title = sec?.label ?: stringResource(R.string.app_name),
                hierarchy = sec?.let { titleHierarchy(it, state.tab) }.orEmpty(),
                onNavigate = { e -> state.showSection(e.sectionId); if (e.depth > 0) state.tab = e.id },
                onAccount = rememberAccountLauncher(),
                onConfigs = { state.openConfigs() },
                onAi = { state.showSection(Declarations.AI_SECTION) },
                dark = true,
            ) {
                NavBarBottomHost(
                    entries = entries,
                    selectedId = state.section,
                    onSelect = { state.showSection(it.id) },
                    modifier = Modifier.testTag(CalcTags.SHELL),
                ) {
                    // The Sheet page's sheets and workbooks go to the end of the title dropdown.
                    if (Declarations.modesOf(state.tab).any { it.kind == "sheet" }) TopBarDrawer { close -> CalcDrawer(close) }
                    // Configs is off the island (the first row's Configs opens it), so Back leaves it.
                    BackHandler(enabled = state.onConfigs) { state.toggleConfigs() }
                    Column(Modifier.fillMaxSize()) {
                        TopRow(state)
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            key(state.tab) { TabContent(state.tab) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The second row: the selected section's pages as the shared pill strip (a section with ONE page draws none). The first
 * row above it, with Configs behind its Configs button, is the fleet's NavBarTopFirstRow (see [CalcShell]).
 */
@Composable
private fun TopRow(state: CalcState) {
    val pages = Declarations.nav.section(state.section)?.pages.orEmpty()
    if (pages.size > 1) {
        NavBarTopSecondRow(
            pages = pages,
            selectedId = state.tab,
            onSelect = { state.tab = it.id },
            modifier = Modifier.fillMaxWidth().testTag(CalcTags.PAGES),
            underTopChrome = false,
        )
    }
}

@Composable
private fun TabContent(tabId: String) {
    val state = LocalCalcState.current
    val modes = Declarations.modesOf(tabId)
    if (modes.isEmpty()) {
        Text(stringResource(R.string.no_modes, tabId), Modifier.padding(CalcMetrics.gutter))
        return
    }
    val selected = state.modeByTab[tabId]?.takeIf { id -> modes.any { it.id == id } } ?: modes.first().id
    Column(Modifier.fillMaxSize().testTag(CalcTags.tab(tabId))) {
        // A page of converters starts with the box that finds any category, unit, alias or food.
        if (modes.any { it.kind in ConvertSearch.KINDS }) ConvertSearchBox(modes)
        if (modes.size > 1) {
            // The page's modes are a dense icon row: icon over a short label, no minimum height.
            ModeIcons(modes, selected, Declarations.tabs.firstOrNull { it.id == tabId }?.icon.orEmpty()) { state.modeByTab[tabId] = it }
        }
        val mode = modes.first { it.id == selected }
        key(mode.id) { ModeScreen(mode) }
    }
}

/** One mode per cell: [Declarations.Mode.icon] (else the page's [pageIcon]) over its short label; scrolls when it must. */
@Composable
private fun ModeIcons(modes: List<Declarations.Mode>, selectedId: String, pageIcon: String, onSelect: (String) -> Unit) {
    val listState = rememberLazyListState()
    // A mode picked elsewhere (a search hit) is scrolled into the row.
    LaunchedEffect(selectedId) { modes.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }?.let { listState.scrollToItem(it) } }
    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth().testTag(CalcTags.STRIP),
        contentPadding = PaddingValues(horizontal = CalcMetrics.small),
        horizontalArrangement = Arrangement.spacedBy(CalcMetrics.hairline),
    ) {
        items(modes, key = { it.id }) { m ->
            val on = m.id == selectedId
            val tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Column(
                Modifier.clip(RoundedCornerShape(CalcMetrics.small))
                    .background(if (on) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                    .clickable { onSelect(m.id) }
                    .padding(horizontal = CalcMetrics.gap, vertical = CalcMetrics.small)
                    .testTag(PageTabsTags.tab(m.id)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(IconCatalog.vector(m.icon.ifBlank { pageIcon }), contentDescription = m.label, tint = tint, modifier = Modifier.size(CalcMetrics.modeIcon))
                Text(m.short.ifBlank { m.label }, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
            }
        }
    }
}

/** The test tags the Robolectric smoke test drives the shell by. */
object CalcTags {
    const val SHELL = "calc_shell"
    const val STRIP = "calc_mode_strip"
    const val PAGES = "calc_page_strip"
    const val AI_PAGE = "calc_ai_page"
    const val INPUT = "calc_input"
    const val RESULT = "calc_result"
    const val DISPLAY = "calc_display"
    const val CONVERT_EQ = "calc_convert_eq"
    const val CONVERT_SWAP = "calc_convert_swap"
    const val MATRIX = "calc_rates_matrix"
    const val CONVERT_SEARCH = "calc_convert_search"
    fun searchHit(i: Int) = "calc_search_hit_$i"
    const val NUTRITION_FOOD = "calc_nutrition_food"
    fun matrixCell(row: String, col: String) = "calc_rates_cell_${row}_$col"
    fun pickerItem(name: String) = "calc_picker_$name"
    const val DATA_HINT = "calc_data_hint"
    const val TRANSFER_SIZE = "calc_transfer_size"
    const val TRANSFER_RATE = "calc_transfer_rate"
    const val HISTORY_LIST = "calc_history_list"
    fun historyExpr(i: Int) = "calc_history_expr_$i"
    fun historyResult(i: Int) = "calc_history_result_$i"
    fun historyDelete(i: Int) = "calc_history_delete_$i"
    const val HISTORY_CLEAR = "calc_history_clear"
    fun tab(id: String) = "calc_tab_$id"
    fun mode(id: String) = "calc_mode_$id"
    /** #1377 a form chip of a form mode (Tools ▸ Time & Date ▸ Calendars …). */
    fun form(id: String) = "calc_form_$id"
    fun key(label: String) = "calc_key_$label"

    // #770 the Jev section and the follow-up question box.
    const val JEV_INPUT = "jev_input"
    const val JEV_ASK = "jev_ask"
    const val JEV_VERDICT = "jev_verdict"
    const val JEV_TOKEN = "jev_token"
    const val JEV_TEST_TOKEN = "jev_test_token"
    const val JEV_NOTE = "jev_note"
    const val JEV_ROUTING_JSON = "jev_routing_json"
    const val JEV_ROUTING_SAVE = "jev_routing_save"
    const val JEV_TEST_INPUT = "jev_test_input"
    const val JEV_TEST_RUN = "jev_test_run"
    const val ASK_TOGGLE = "ask_toggle"
    const val ASK_BOX = "ask_box"
    fun jevCandidate(id: String) = "jev_candidate_$id"
    fun askPreset(id: String) = "ask_preset_$id"
    fun jevModel(slug: String) = "jev_model_$slug"
    fun jevPicker(use: String) = "jev_picker_$use"
    fun jevTestColumn(model: String) = "jev_test_column_$model"
}

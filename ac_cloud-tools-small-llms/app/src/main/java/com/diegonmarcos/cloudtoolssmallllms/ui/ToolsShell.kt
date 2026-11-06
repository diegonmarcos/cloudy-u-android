package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.cloudtoolssmallllms.Decl
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiStrings
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.materialAiTheme
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.themebg.themeBackground
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width

/**
 * The shell: the fleet's shared bars (#1154, #1157) - the first row (Profile at the top right opens Cloud Account, the
 * gear opens Settings), the second row with the view icons of S-Models and M-Models, the bottom nav, the page gradient
 * and the one scroll collapse the scaffold owns - around the page build.json::ui declares for the section.
 */
@Composable
fun ToolsShell(model: ToolsModel) {
    val nav = Decl.nav
    val sec = nav.section(model.section)
    val pageId = model.page?.takeIf { p -> sec?.pages?.any { it.id == p } == true } ?: sec?.pages?.firstOrNull()?.id
    val entries = nav.islandEntries()
    val theme = toolsAiTheme()
    LaunchedEffect(Unit) { model.refreshStore() }
    // Apps' Try in AI sets the task from outside: the composer's Task control follows it.
    LaunchedEffect(model.chatTask) { if (model.chat.task != model.chatTask) model.chat.chooseTask(model.chatTask) }
    fun open(sectionId: String, page: String?) { model.section = sectionId; model.page = page }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, pageId) }.orEmpty(),
            onNavigate = { e -> open(e.sectionId, if (e.depth == 0) null else e.id) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { open(Decl.SECTION_SETTINGS, null) },
            onAi = { open(Decl.SECTION_AI, null) },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = entries,
                selectedId = model.section,
                onSelect = { open(it.id, null) },
                modifier = Modifier.testTag(Tags.SHELL),
            ) {
                val pageBody: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        when (model.section) {
                            Decl.SECTION_APPS -> AppsPage(model, pageId ?: Decl.PAGE_APPS_GRID)
                            Decl.SECTION_MODELS -> ModelsPage(model.models, pageId ?: Decl.PAGE_SMALL, model.view) { model.view = it }
                            Decl.SECTION_AI -> AiChatPage(model.chat, theme, Modifier.imePadding().testTag(Tags.CHAT), aboveInput = { TaskRow(model) }, header = {
                                Chip(stringResource(R.string.backend_auto), selected = model.backend == ToolsModel.AUTO, tag = Tags.BACKEND_AUTO) { model.chooseBackend(ToolsModel.AUTO) }
                                Spacer(Modifier.width(ToolsMetrics.small))
                                Chip(stringResource(R.string.backend_openrouter), selected = model.backend == ToolsModel.OPENROUTER, tag = Tags.BACKEND_OPENROUTER) { model.chooseBackend(ToolsModel.OPENROUTER) }
                                Spacer(Modifier.width(ToolsMetrics.small))
                                Chip(stringResource(R.string.backend_local), selected = model.backend == ToolsModel.LOCAL, tag = Tags.BACKEND_LOCAL) { model.chooseBackend(ToolsModel.LOCAL) }
                                if (model.backend != ToolsModel.OPENROUTER && model.loadedNames().isEmpty()) {
                                    Spacer(Modifier.width(ToolsMetrics.small))
                                    Chip(stringResource(R.string.backend_load_in_store), icon = "tasks") { open(Decl.SECTION_STORE, Decl.PAGE_LOADED) }
                                }
                            })
                            Decl.SECTION_STORE -> StorePage(model, pageId ?: Decl.PAGE_INSTALLED)
                            Decl.SECTION_MLS -> MlsPage(model.mls, pageId ?: Decl.PAGE_ML_LITE, model.mlsNote)
                            Decl.SECTION_SENSORS -> SensorsPage(pageId ?: Decl.PAGE_SENSORS_INBUILT, model.sensorsJson)
                            Decl.SECTION_SETTINGS -> SettingsPage(model)
                            else -> {}
                        }
                    }
                }
                // The section's child pages in the mode it declares or the user chose in Settings (one row, two rows, side menu).
                if (sec != null && sec.pages.isNotEmpty()) {
                    NavSectionLayout(sec, pageId, onSelect = { model.page = it.id }, modifier = Modifier.fillMaxSize().testTag(Tags.SECOND_ROW), content = pageBody)
                } else pageBody()
            }
        }
    }
}

/** The AI page's note for the task in force and the Stronger chip (the medium model at once); the TASK itself is the shared composer's Task control. */
@Composable
private fun TaskRow(model: ToolsModel) {
    Column(Modifier.fillMaxWidth().padding(horizontal = ToolsMetrics.gap), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
        val note = model.models.chatTasks.firstOrNull { it.id == model.chatTask }?.note
        if (note != null) Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row { Chip(stringResource(R.string.chat_stronger), selected = model.stronger, tag = Tags.STRONGER) { model.stronger = !model.stronger } }
    }
}

/** The Chat page's look: the fleet AI default (Material colours) in this app's words. */
@Composable
fun toolsAiTheme(): AiTheme = materialAiTheme(AiStrings(
    title = stringResource(R.string.app_name),
    hello = stringResource(R.string.chat_empty_title), helloSub = stringResource(R.string.chat_empty_body),
    askAnything = stringResource(R.string.chat_hint), send = stringResource(R.string.chat_send),
    thinking = stringResource(R.string.chat_working), chatError = stringResource(R.string.chat_failed),
))

/** A small rounded chip: the data-dense control every page uses (task, model, cost, choices). */
@Composable
fun Chip(text: String, selected: Boolean = false, icon: String? = null, tag: String? = null, onClick: (() -> Unit)? = null) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    var m = Modifier.height(ToolsMetrics.chipHeight).clip(RoundedCornerShape(ToolsMetrics.corner)).background(bg)
        .border(ToolsMetrics.hairline, if (selected) Color.Transparent else MaterialTheme.colorScheme.outline, RoundedCornerShape(ToolsMetrics.corner))
    if (onClick != null) m = m.clickable(onClick = onClick)
    if (tag != null) m = m.testTag(tag)
    Row(m.padding(horizontal = ToolsMetrics.gap), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) {
            Icon(IconCatalog.vector(icon), contentDescription = null, tint = fg, modifier = Modifier.size(ToolsMetrics.icon))
            Spacer(Modifier.width(ToolsMetrics.small))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** The test tags the pages are driven by. */
object Tags {
    const val SHELL = "tools_shell"
    const val SECOND_ROW = "tools_second_row"
    const val CHAT = "tools_chat"
    const val APPS = "tools_apps"
    const val APPS_LIST = "tools_apps_list"
    const val APP_FILTERS = "tools_app_filters"
    fun appFilter(id: String) = "tools_app_filter_$id"
    const val MLS = "tools_mls"
    const val SENSORS_INBUILT = "tools_sensors_inbuilt"
    const val SENSORS_CONNECTED = "tools_sensors_connected"
    const val SENSOR_LIVE = "tools_sensor_live"
    fun sensorRow(id: Int) = "tools_sensor_row_$id"
    fun hardwareRow(id: String) = "tools_hardware_row_$id"
    fun profileRow(id: String) = "tools_profile_row_$id"
    fun profileScan(id: String) = "tools_profile_scan_$id"
    fun mlRow(id: String) = "tools_ml_row_$id"
    fun mlHeader(application: String) = "tools_ml_header_$application"
    const val APP_TOTALS = "tools_app_totals"
    const val APP_TEMPLATE = "tools_app_template"
    const val APP_INFO = "tools_app_info"
    const val APP_RUN = "tools_app_run"
    const val APP_RESULT = "tools_app_result"
    fun appInput(i: Int) = "tools_app_input_$i"
    fun stepStatus(i: Int) = "tools_app_step_status_$i"
    fun appRow(id: String) = "tools_app_row_$id"
    fun category(id: String) = "tools_app_category_$id"
    const val APP_DETAIL = "tools_app_detail"
    const val PHONE_BADGE = "tools_app_phone"
    const val TRY_IN_AI = "tools_app_try_in_ai"
    const val MACRO = "tools_models_macro"
    fun app(id: String) = "tools_app_$id"
    fun view(id: String) = "tools_models_view_$id"
    fun macro(id: String) = "tools_models_macro_$id"
    const val TABLE = "tools_models_table"
    const val GRID = "tools_models_grid"
    const val DETAIL = "tools_models_detail"
    const val OVERHEAD = "tools_models_overhead"
    const val STORE = "tools_store"
    const val STORE_REFRESH = "tools_store_refresh"
    const val STORE_ERROR = "tools_store_error"
    const val STORE_INSTALL = "tools_store_install"
    const val STORE_UNINSTALL = "tools_store_uninstall"
    const val STORE_LOAD = "tools_store_load"
    const val STORE_UNLOAD = "tools_store_unload"
    const val FITS = "tools_store_fits"
    const val BACKEND_AUTO = "tools_backend_auto"
    const val PIN_AUTO = "tools_pin_auto"
    fun pin(model: String) = "tools_pin_$model"
    const val STRONGER = "tools_chat_stronger"
    fun chatTask(id: String) = "tools_chat_task_$id"
    const val BACKEND_OPENROUTER = "tools_backend_openrouter"
    const val BACKEND_LOCAL = "tools_backend_local"
    fun stepBackend(i: Int) = "tools_app_step_backend_$i"
    fun location(id: String) = "tools_store_loc_$id"
    fun store(loc: String, model: String) = "tools_store_${loc}_$model"
    fun bits(b: Int) = "tools_store_bits_$b"
    fun header(id: String) = "tools_models_header_$id"
    fun row(model: String) = "tools_models_row_$model"
    fun card(model: String) = "tools_models_card_$model"
    const val TOKEN = "tools_token"
    const val TOKEN_SAVE = "tools_token_save"
    const val TOKEN_TEST = "tools_token_test"
    fun router(id: String) = "tools_router_$id"
}

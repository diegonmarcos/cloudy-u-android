package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudtoolssmallllms.Decl
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.data.AppDef
import com.diegonmarcos.cloudtoolssmallllms.data.ModelsData
import com.diegonmarcos.cloudtoolssmallllms.data.Step
import com.diegonmarcos.superapp.uiicons.UiIcons
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine

private fun gb(v: Double): String = java.lang.String.format(java.util.Locale.US, "%.1f", v)

/** Apps ([page] = grid or list): what the small and medium models can build; a tap opens the app's pipeline. */
@Composable
fun AppsPage(model: ToolsModel, page: String) {
    val data = model.models
    val open = data.apps.firstOrNull { it.id == model.appId }
    when {
        open != null -> { BackHandler { model.closeApp() }; AppDetail(model, data, open) }
        else -> Column(Modifier.fillMaxSize()) {
            AppFilters(model, data)
            val apps = data.appsIn(model.appFilter)
            if (page == Decl.PAGE_APPS_LIST) AppsList(model, data, apps) else AppsGrid(model, data, apps)
        }
    }
}

/** The top filter All | Hybrids | Models Only | ML Only (tools-models.json::app_filters): one chip each, the open one selected. */
@Composable
private fun AppFilters(model: ToolsModel, data: ModelsData) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(Tags.APP_FILTERS),
        horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small), verticalAlignment = Alignment.CenterVertically,
    ) {
        data.appFilters.forEach { f -> Chip(f.label, selected = model.appFilter == f.id, tag = Tags.appFilter(f.id)) { model.appFilter = f.id } }
    }
}

/** Compact totals of an app, all computed from the data: 4-bit RAM, 16-bit disk and the number of models. */
@Composable
private fun totalsCompact(data: ModelsData, a: AppDef): String = stringResource(R.string.app_totals_compact, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a)), data.appModels(a).size)

@Composable
private fun AppsGrid(model: ToolsModel, data: ModelsData, apps: List<AppDef>) {
    LazyVerticalGrid(
        GridCells.Adaptive(150.dp), Modifier.fillMaxSize().testTag(Tags.APPS), contentPadding = PaddingValues(ToolsMetrics.gutter),
        horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
    ) {
        items(apps, key = { it.id }) { a ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface)
                    .clickable { model.appId = a.id }.padding(ToolsMetrics.gutter).testTag(Tags.app(a.id)),
                verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(UiIcons.vector(a.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.weight(1f))
                    if (data.phoneCapable(a)) Text(stringResource(R.string.app_phone_icon))
                }
                Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(totalsCompact(data, a), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The pipeline as "model → model → model": "|" between the alternatives of a step, an up arrow on an escalation step. */
@Composable
private fun pipelineText(a: AppDef): String {
    val parts = ArrayList<String>()
    for (st in a.pipeline) {
        val t = stepText(st)
        parts += if (st.escalation) stringResource(R.string.app_escalation_mark, t) else t
    }
    return parts.joinToString(" → ")
}

private fun stepText(s: Step): String = if (s.models.isEmpty()) s.note.orEmpty().substringBefore(':') else s.models.joinToString(if (s.alternatives) " | " else " + ")

/** Dense list (ui-kit KitStoreRow, the Cloud Store's row): category headers, then per app its class glyph, name over its pipeline, and the compact totals at the end. */
@Composable
private fun AppsList(model: ToolsModel, data: ModelsData, apps: List<AppDef>) {
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.APPS_LIST)) {
        data.appCategories.forEach { c ->
            val inCategory = apps.filter { it.category == c.id }
            if (inCategory.isEmpty()) return@forEach
            item(key = "cat_" + c.id) {
                Row(Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(Tags.category(c.id)), verticalAlignment = Alignment.CenterVertically) {
                    Icon(UiIcons.vector(c.icon), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(ToolsMetrics.icon))
                    Spacer(Modifier.width(ToolsMetrics.gap))
                    KitStoreCaption(c.label, color = scheme.primary)
                }
            }
            items(inCategory, key = { it.id }) { a ->
                Column(Modifier.testTag(Tags.appRow(a.id))) {
                    KitStoreRowLine(onClick = { model.appId = a.id }) {
                        KitStoreDot(data.classGlyph(a), scheme.primary)
                        if (data.phoneCapable(a)) { Text(stringResource(R.string.app_phone_icon), fontSize = KitStoreRow.body); Spacer(Modifier.width(ToolsMetrics.small)) }
                        Column(Modifier.weight(1f)) {
                            KitStoreName(a.name, color = scheme.onSurface)
                            KitStoreCaption(pipelineText(a), color = scheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(ToolsMetrics.gap))
                        KitStoreCaption(totalsCompact(data, a), color = scheme.onSurfaceVariant, mono = true, modifier = Modifier.testTag(Tags.APP_TOTALS))
                    }
                    Box(Modifier.fillMaxWidth().height(ToolsMetrics.hairline).background(KitStoreRow.RULE))
                }
            }
        }
    }
}

/**
 * One app's own page: its UI template (the labels and the result view come from the data), a collapsible header with
 * the pipeline, RAM, disk and phone badge, the input, Run, the per-step status while it runs and the final result.
 */
@Composable
private fun AppDetail(model: ToolsModel, data: ModelsData, a: AppDef) {
    var info by rememberSaveable(a.id) { mutableStateOf(false) }
    val labels = a.ui.inputs.ifEmpty { listOf("") }
    val values = remember(a.id) { mutableStateListOf(*Array(labels.size) { "" }) }
    val run = model.appRun?.takeIf { it.appId == a.id }
    val tpl = data.templates.firstOrNull { it.id == a.ui.template }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.APP_DETAIL), contentPadding = PaddingValues(ToolsMetrics.gutter), verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(UiIcons.vector(a.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(ToolsMetrics.gap))
                Text(a.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { model.closeApp() }) { Text(stringResource(R.string.apps_back)) }
            }
            Text(a.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.app_template, tpl?.label.orEmpty(), tpl?.flow.orEmpty()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(Tags.APP_TEMPLATE))
            Row(Modifier.clickable { info = !info }.padding(vertical = ToolsMetrics.gap).testTag(Tags.APP_INFO), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (info) R.string.app_info_hide else R.string.app_info_show), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(ToolsMetrics.gap))
                Text(stringResource(R.string.app_totals, gb(data.appRamQ4(a)), gb(data.appDiskFp16(a)), data.appModels(a).size), style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag(Tags.APP_TOTALS))
                if (data.phoneCapable(a)) { Spacer(Modifier.width(ToolsMetrics.small)); Text(stringResource(R.string.app_phone_icon)) }
            }
        }
        if (info) {
            itemsSteps(a, data)
            item {
                Text(stringResource(R.string.app_models_used, data.appModels(a).joinToString(", ") { it.model }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chip(stringResource(if (data.phoneCapable(a)) R.string.app_phone_yes else R.string.app_phone_no), selected = data.phoneCapable(a), tag = Tags.PHONE_BADGE)
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap)) {
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = values[i], onValueChange = { values[i] = it },
                        label = { Text(label.ifBlank { a.ui.inputHint }) }, placeholder = { Text(a.ui.inputHint) },
                        modifier = Modifier.fillMaxWidth().testTag(Tags.appInput(i)),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                    Chip(stringResource(R.string.app_run), selected = true, tag = Tags.APP_RUN) {
                        model.runApp(a, if (labels.size == 1) values[0] else labels.indices.joinToString("\n\n") { labels[it] + ":\n" + values[it] })
                    }
                    val first = data.appModels(a).firstOrNull { it.chat }
                    if (first != null) TextButton(onClick = { model.tryInAi(first.model) }, modifier = Modifier.testTag(Tags.TRY_IN_AI)) {
                        Text(stringResource(R.string.app_try_in_ai, first.model))
                    }
                }
            }
        }
        if (run != null) {
            items(run.steps.withIndex().toList(), key = { "run" + it.index }) { (i, st) ->
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface).padding(ToolsMetrics.gutter).testTag(Tags.stepStatus(i)),
                    verticalArrangement = Arrangement.spacedBy(ToolsMetrics.tiny),
                ) {
                    Text("${i + 1}. ${data.macro(st.macro)?.label ?: st.macro} · ${st.model.orEmpty()}" + (st.backend?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelLarge)
                    Text(st.status, style = MaterialTheme.typography.labelSmall, color = if (st.status.startsWith("failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            run.output?.let { out -> item { Text(out, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surfaceVariant).padding(ToolsMetrics.gutter).testTag(Tags.APP_RESULT)) } }
        }
    }
}

private fun LazyListScope.itemsSteps(a: AppDef, data: ModelsData) {
    items(a.pipeline.withIndex().toList(), key = { it.index }) { (i, s) ->
        val m = data.macro(s.macro)
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface).padding(ToolsMetrics.gutter),
            verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m != null) Icon(UiIcons.vector(m.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ToolsMetrics.icon))
                Spacer(Modifier.width(ToolsMetrics.gap))
                Text("${i + 1}. ${m?.label ?: s.macro}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Chip(stringResource(if (data.backend(a, s) == ToolsModel.LOCAL) R.string.backend_local else R.string.backend_openrouter), tag = Tags.stepBackend(i))
            }
            Column(verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (s.alternatives) Text(stringResource(R.string.app_alternatives), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.escalation) Text(stringResource(R.string.app_escalation), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                s.models.forEach { id -> Chip(id + (data.row(id)?.let { " · " + gb(it.ramQ4) + " GB" } ?: "")) }
            }
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitSelectableTile
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitSwitchRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import helium314.keyboard.latin.AiEngine
import helium314.keyboard.latin.AiTask
import helium314.keyboard.latin.AiTaskModels
import helium314.keyboard.latin.AiTaskRouting
import helium314.keyboard.latin.Catalog
import helium314.keyboard.latin.CatalogModel
import helium314.keyboard.latin.EngineResolution
import helium314.keyboard.latin.OpenRouterCatalog
import helium314.keyboard.latin.R
import helium314.keyboard.latin.asStore
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

// Configs ▸ AI Routing: the OpenRouter model list (Refresh, search, free filter) and one selectable model
// table per task; plus the routing switch Translation and Grammar Check show, and the read-only model line
// Text Enhancement shows. The rows are libs:ui-kit's fleet rows (KitListRow, KitSelectableTile, KitSearchBar,
// KitSwitchRow), painted from this screen's Material colours so the keyboard's themes still apply.

/** Setting keys of the rows below. Rows store nothing under these: the data lives under the AiTaskModels / AiTaskRouting / OpenRouterCatalog keys. */
object AiRoutingRows {
    const val CATALOG = "ai_catalog_refresh"
    const val ENHANCE_MODEL = "ai_enhance_model_in_use"
    fun table(task: AiTask) = "ai_task_table_${task.id}"
}

/** The page's shared list state: one refresh, one search and one free filter drive all three tables. */
private object CatalogUi {
    var catalog by mutableStateOf<Catalog?>(null)
    var query by mutableStateOf("")
    var freeOnly by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var loaded = false
    /** Bumped on every selection, so every table and status line re-reads the stored choices. */
    var revision by mutableIntStateOf(0)
}

@Composable
private fun KitScope(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val palette = KitPalette(
        surface = cs.surface, surfaceSelected = cs.secondaryContainer, textPrimary = cs.onSurface,
        textSecondary = cs.onSurfaceVariant, accent = cs.primary, hairline = cs.outlineVariant, tileInk = cs.onPrimary,
    )
    CompositionLocalProvider(LocalKitPalette provides palette, content = content)
}

private fun stamp(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))

private fun price(v: Double?): String = v?.let { String.format(Locale.US, "%.3f", it) } ?: "?"

private fun contextLabel(n: Int?): String = when {
    n == null -> "?"
    n >= 1_000_000 -> "${n / 1_000_000}M"
    else -> "${n / 1000}K"
}

private fun loadOnce(ctx: Context) {
    if (CatalogUi.loaded) return
    CatalogUi.loaded = true
    CatalogUi.catalog = OpenRouterCatalog.load(ctx.prefs().asStore())
}

private suspend fun refresh(ctx: Context) {
    CatalogUi.busy = true
    val r = withContext(Dispatchers.IO) {
        OpenRouterCatalog.refresh(ctx.prefs().asStore(), System.currentTimeMillis()) { OpenRouterCatalog.httpGet() }
    }
    when (r) {
        is OpenRouterCatalog.RefreshResult.Ok -> {
            CatalogUi.catalog = r.catalog; CatalogUi.error = null
            Log.i("AiCatalog", "refreshed ${r.catalog.models.size} models")
        }
        is OpenRouterCatalog.RefreshResult.Offline -> {
            CatalogUi.error = ctx.getString(R.string.ai_catalog_offline); Log.w("AiCatalog", "offline: ${r.message}")
        }
        is OpenRouterCatalog.RefreshResult.Failed -> {
            CatalogUi.error = ctx.getString(R.string.ai_catalog_failed, r.message); Log.w("AiCatalog", "failed: ${r.message}")
        }
    }
    CatalogUi.busy = false
}

/** Refresh + "updated <time>" + search + free filter. Refreshes on its own the first time, when nothing is cached. */
@Composable
fun AiCatalogRow(setting: Setting) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    loadOnce(ctx)
    LaunchedEffect(Unit) { if (CatalogUi.catalog == null && !CatalogUi.busy) refresh(ctx) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    KitScope {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp)) {
            Text(setting.title, style = MaterialTheme.typography.bodyLarge)
            setting.description?.let { Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium) }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !CatalogUi.busy, onClick = { scope.launch { refresh(ctx) } }, modifier = Modifier.testTag("ai:catalog:refresh")) {
                    Text(stringResource(R.string.ai_catalog_refresh))
                }
                val c = CatalogUi.catalog
                Text(
                    when {
                        CatalogUi.busy -> stringResource(R.string.ai_catalog_refreshing)
                        c != null -> stringResource(R.string.ai_catalog_updated, stamp(c.fetchedAt), c.models.size)
                        else -> stringResource(R.string.ai_catalog_never)
                    },
                    Modifier.padding(start = 12.dp).testTag("ai:catalog:stamp"), color = dim, style = MaterialTheme.typography.bodySmall,
                )
            }
            CatalogUi.error?.let {
                Text(it, Modifier.padding(top = 6.dp).testTag("ai:catalog:error"), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            KitSearchBar(
                query = CatalogUi.query, onQueryChange = { CatalogUi.query = it }, onSubmit = {},
                modifier = Modifier.padding(top = 8.dp), placeholder = stringResource(R.string.ai_catalog_search),
            )
            KitSwitchRow(stringResource(R.string.ai_catalog_free_only), "", CatalogUi.freeOnly, { CatalogUi.freeOnly = it })
        }
    }
}

/**
 * One task's model table: Model (name, provider · context) | price in / out per 1M tokens | free badge.
 * A tap selects the row and stores its id for the task; the chosen row stays first even when the search hides it.
 */
@Composable
fun AiTaskTable(setting: Setting, task: AiTask) {
    val ctx = LocalContext.current
    loadOnce(ctx)
    val store = ctx.prefs().asStore()
    // Reading the revision makes this table recompose when any table's selection changes.
    val selected = CatalogUi.revision.let { AiTaskModels.get(store, task) }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val all = CatalogUi.catalog?.models.orEmpty()
    val rows = remember(all, CatalogUi.query, CatalogUi.freeOnly, selected) {
        val f = OpenRouterCatalog.filter(all, CatalogUi.query, CatalogUi.freeOnly)
        val pinned = all.firstOrNull { it.id == selected }
        if (pinned != null) listOf(pinned) + f.filter { it.id != selected } else f
    }
    KitScope {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp).testTag("ai:table:${task.id}")) {
            Text(setting.title, style = MaterialTheme.typography.bodyLarge)
            setting.description?.let { Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.ai_task_in_use, AiTaskRouting.modelInUse(ctx, task)) +
                if (selected == null) " " + stringResource(R.string.ai_task_default_note) else "",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(stringResource(R.string.ai_task_col_model), Modifier.weight(1f), color = dim, style = MaterialTheme.typography.labelMedium)
                Text(stringResource(R.string.ai_task_col_price), Modifier.width(120.dp), color = dim,
                    style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            }
            if (rows.isEmpty()) {
                Text(stringResource(if (all.isEmpty()) R.string.ai_task_empty_refresh else R.string.ai_task_empty_filter),
                    Modifier.padding(vertical = 12.dp), color = dim, style = MaterialTheme.typography.bodyMedium)
            } else LazyColumn(Modifier.fillMaxWidth().height(240.dp)) {
                items(rows, key = { it.id }) { m -> ModelRow(m, m.id == selected) {
                    AiTaskModels.set(store, task, m.id); CatalogUi.revision++
                } }
            }
        }
    }
}

@Composable
private fun ModelRow(m: CatalogModel, selected: Boolean, onSelect: () -> Unit) {
    val bg = if (selected) LocalKitPalette.current.surfaceSelected else LocalKitPalette.current.surface
    KitListRow(
        label = m.name,
        modifier = Modifier.background(bg).padding(horizontal = 4.dp),
        secondary = "${m.provider} · ${contextLabel(m.contextLength)} ctx",
        leading = if (selected) "●" else "○",
        pill = if (m.free) stringResource(R.string.ai_task_free) to KitState.OK else null,
        tag = "ai:row:${m.id}",
        onClick = onSelect,
    ) {
        Text("${price(m.inPerM)} / ${price(m.outPerM)}", Modifier.width(110.dp), textAlign = TextAlign.End,
            color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

/** Translation / Grammar Check: the explicit ML libs vs OpenRouter switch, what runs now, and the offline fallback. */
@Composable
fun AiRoutingSwitch(setting: Setting, task: AiTask) {
    val ctx = LocalContext.current
    val store = ctx.prefs().asStore()
    var rev by remember { mutableIntStateOf(0) }
    val chosen = rev.let { AiTaskRouting.chosen(store, task) }
    val res = AiTaskRouting.resolution(ctx, task)
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    fun name(e: AiEngine?) = when (e) {
        AiEngine.ML -> ctx.getString(R.string.ai_engine_ml)
        AiEngine.OPENROUTER -> ctx.getString(R.string.ai_engine_openrouter_model, AiTaskRouting.modelInUse(ctx, task))
        null -> ctx.getString(R.string.ai_engine_none)
    }
    KitScope {
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp).testTag("ai:routing:${task.id}")) {
            Text(setting.title, style = MaterialTheme.typography.bodyLarge)
            setting.description?.let { Text(it, Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodyMedium) }
            listOf(AiEngine.ML to R.string.ai_engine_ml, AiEngine.OPENROUTER to R.string.ai_engine_openrouter).forEach { (e, label) ->
                KitSelectableTile(stringResource(label), "", chosen == e,
                    onClick = { AiTaskRouting.choose(store, task, e); rev++ }, modifier = Modifier.padding(top = 4.dp).testTag("ai:routing:${task.id}:${e.id}"))
            }
            Text(stringResource(R.string.ai_routing_active, name(res.active)) +
                if (res.reason != EngineResolution.Reason.CHOSEN) " (" + reasonText(ctx, res.reason) + ")" else "",
                Modifier.padding(top = 8.dp).testTag("ai:routing:${task.id}:active"), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ai_routing_fallback, res.fallback?.let { name(it) } ?: ctx.getString(R.string.ai_engine_none)),
                Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ai_routing_open_tables), Modifier.padding(top = 6.dp)
                .clickable { SettingsDestination.navigateTo(SettingsDestination.AiRouting) },
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun reasonText(ctx: Context, r: EngineResolution.Reason): String = ctx.getString(when (r) {
    EngineResolution.Reason.CHOSEN -> R.string.ai_reason_chosen
    EngineResolution.Reason.OFFLINE -> R.string.ai_reason_offline
    EngineResolution.Reason.NO_KEY -> R.string.ai_reason_no_key
    EngineResolution.Reason.ML_UNAVAILABLE -> R.string.ai_reason_ml_unavailable
    EngineResolution.Reason.NOTHING_AVAILABLE -> R.string.ai_reason_nothing
})

/** Text Enhancement: the model in use, read-only, with the way to change it. */
@Composable
fun AiEnhanceModelRow(setting: Setting) {
    val ctx = LocalContext.current
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val picked = AiTaskModels.get(ctx.prefs().asStore(), AiTask.ENHANCE)
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 12.dp).testTag("ai:enhance:model")) {
        Text(setting.title, style = MaterialTheme.typography.bodyLarge)
        Text(AiTaskRouting.modelInUse(ctx, AiTask.ENHANCE), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(if (picked != null) R.string.ai_enhance_model_picked else R.string.ai_enhance_model_default),
            Modifier.padding(top = 2.dp), color = dim, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.ai_routing_open_tables), Modifier.padding(top = 6.dp)
            .clickable { SettingsDestination.navigateTo(SettingsDestination.AiRouting) },
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}

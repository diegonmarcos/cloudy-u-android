package com.diegonmarcos.cloudsuperintel.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.AiData
import com.diegonmarcos.cloudsuperintel.AiGroup
import com.diegonmarcos.cloudsuperintel.AiPageSpec
import com.diegonmarcos.cloudsuperintel.ApiRow
import com.diegonmarcos.cloudsuperintel.Apis
import com.diegonmarcos.cloudsuperintel.Cost
import com.diegonmarcos.cloudsuperintel.PrivateSource
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.superapp.notificationcenter.AiUsageModel
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The test tags the AIs and Cost pages are driven by. */
object AiTags {
    const val PAGE = "ai_page"
    const val ROWS = "ai_rows"
    const val SAMPLE = "ai_sample"
    const val COST = "ai_cost"
    const val COST_WEEK = "ai_cost_week_"
    const val COST_DAILY = "ai_cost_daily"
    const val COST_MONTHLY = "ai_cost_monthly"
}

/** One page of the AIs or Cost section: its screen is the declared kind (assets/ai/pages.json), its data the declared asset. */
@Composable
fun AiPage(spec: AiPageSpec) {
    val ctx = LocalContext.current.applicationContext
    val text = remember(spec.asset) { AiData.asset(ctx, spec.asset) }
    KitHost {
        Box(Modifier.fillMaxSize().testTag(AiTags.PAGE)) {
            when (spec.kind) {
                AiData.KIND_APIS -> ApisPage(text)
                AiData.KIND_COST -> CostPage(text)
                else -> RowsPage(remember(text) { AiData.groups(text) })
            }
        }
    }
}

/** The ui-kit theme over this app's Material colours, so the Store's dense rows draw in the app's own palette. */
@Composable
private fun KitHost(content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    CloudKitTheme(KitPalette(c.surface, c.surfaceVariant, c.onSurface, c.onSurfaceVariant, c.primary, c.outline, c.onPrimary), content)
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.dp).background(KitStoreRow.RULE))

@Composable
private fun Header(text: String) {
    val p = LocalKitPalette.current
    KitStoreCaption(text, Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.gap), color = p.accent)
}

@Composable
private fun RowsPage(groups: List<AiGroup>) {
    val p = LocalKitPalette.current
    if (groups.isEmpty()) {
        KitStoreCaption(stringResource(R.string.ai_empty), Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = p.textSecondary, align = TextAlign.Center)
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag(AiTags.ROWS)) {
        groups.forEach { g ->
            item { Header(g.title) }
            items(g.rows) { r ->
                KitStoreRowLine {
                    Column(Modifier.weight(1f)) {
                        KitStoreName(r.title, color = p.textPrimary)
                        if (r.caption.isNotEmpty()) KitStoreCaption(r.caption, color = p.textSecondary)
                    }
                    if (r.end.isNotEmpty()) KitStoreCaption(r.end, Modifier.padding(start = KitStoreRow.gap), color = p.textPrimary, mono = true)
                }
                Rule()
            }
        }
    }
}

@Composable
private fun ApisPage(text: String) {
    val ctx = LocalContext.current.applicationContext
    val p = LocalKitPalette.current
    val cfg = remember(text) { Apis.parse(text) }
    val src = remember(cfg) { PrivateSource.account(ctx, cfg.authority, cfg.method) }
    var rows by remember { mutableStateOf(cfg.rows) }
    LaunchedEffect(cfg) { rows = withContext(Dispatchers.IO) { Apis.rows(cfg, src) } }
    // A revealed token lives only in this map, only while the page is composed; a second tap hides it.
    val shown = remember { mutableStateMapOf<String, String>() }
    var tapped by remember { mutableStateOf<ApiRow?>(null) }
    LaunchedEffect(tapped) {
        val r = tapped ?: return@LaunchedEffect
        if (shown.remove(r.id) == null) shown[r.id] = withContext(Dispatchers.IO) { Apis.reveal(cfg, src, r) } ?: ctx.getString(R.string.ai_reveal_none)
        tapped = null
    }
    LazyColumn(Modifier.fillMaxSize().testTag(AiTags.ROWS)) {
        if (rows.any { it.sample }) item { KitStoreCaption(stringResource(R.string.ai_sample_note), Modifier.fillMaxWidth().padding(KitStoreRow.padH).testTag(AiTags.SAMPLE), color = p.textSecondary) }
        items(rows, key = { it.id }) { r ->
            KitStoreRowLine(onClick = { tapped = r }) {
                KitStoreDot(if (r.sample) "○" else "●", if (r.sample) p.textSecondary else KitStoreRow.UP)
                Column(Modifier.weight(1f)) {
                    KitStoreName(r.label + if (r.sample) " · ${cfg.sampleLabel}" else "", color = p.textPrimary)
                    KitStoreCaption(Apis.line(cfg.caption, r), color = p.textSecondary)
                    KitStoreCaption(Apis.line(cfg.caption2, r), color = p.textSecondary)
                    Row(Modifier.padding(top = KitStoreRow.tight)) {
                        r.links.forEach { l ->
                            KitStoreQuick(l.label, KitStoreRow.BLUE_FILL, Modifier.padding(end = KitStoreRow.gap)) {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                    }
                }
                KitStoreCaption(shown[r.id] ?: r.tokenMasked, Modifier.padding(start = KitStoreRow.gap), color = p.textPrimary, mono = true)
            }
            Rule()
        }
    }
}

@Composable
private fun CostPage(text: String) {
    val ctx = LocalContext.current.applicationContext
    val p = LocalKitPalette.current
    val cfg = remember(text) { Cost.parse(text) }
    var reload by remember { mutableIntStateOf(0) }
    var read by remember { mutableStateOf<Triple<AiUsageModel.Snapshot?, String, Long>?>(null) }
    LaunchedEffect(cfg, reload) {
        read = withContext(Dispatchers.IO) {
            val bearer = PrivateSource.account(ctx, cfg.authority, cfg.method).get(cfg.bearerKey).orEmpty()
            val (snap, note) = Cost.fetch(cfg, bearer)
            Triple(snap, note, System.currentTimeMillis())
        }
    }
    val r = read
    val table = remember(r) { r?.let { Cost.table(cfg, it.first, it.third) } }
    val week = remember(r) { r?.let { Cost.week(it.first, it.third) } }
    val tokens = r?.first?.tokens
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(AiTags.COST)) {
        // #1543 Weekly Now: the badge's own four lines (AiUsageModel.week), red where the week runs out before its reset.
        KitStoreCaption(stringResource(R.string.cost_week), Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = p.accent)
        if (week == null) KitStoreCaption(if (r == null) stringResource(R.string.cost_loading) else "-", Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH), color = p.textSecondary)
        else week.lines().forEachIndexed { i, l ->
            KitStoreCaption(l, Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH).testTag(AiTags.COST_WEEK + i),
                color = if (i in week.alert) KitStoreRow.BAD else p.textPrimary, mono = true)
        }
        Rule()
        TokenTable(stringResource(R.string.cost_daily), cfg.tokenColumns, tokens?.days.orEmpty(), r != null, AiTags.COST_DAILY)
        TokenTable(stringResource(R.string.cost_monthly), cfg.tokenColumns, tokens?.months.orEmpty(), r != null, AiTags.COST_MONTHLY)
        KitStoreRowLine {
            KitStoreCaption("", Modifier.weight(LABEL_WEIGHT))
            cfg.columns.forEach { KitStoreCaption(it, Modifier.weight(1f), color = p.accent) }
        }
        Rule()
        if (table == null) KitStoreCaption(stringResource(R.string.cost_loading), Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = p.textSecondary)
        else {
            table.rows.forEach { row ->
                KitStoreRowLine {
                    KitStoreName(row.label, Modifier.weight(LABEL_WEIGHT), color = p.textPrimary)
                    KitStoreCaption(row.claude, Modifier.weight(1f), color = p.textPrimary, mono = true)
                    KitStoreCaption(row.openrouter, Modifier.weight(1f), color = p.textPrimary, mono = true)
                }
                Rule()
            }
            val note = r?.second.orEmpty()
            if (r?.first == null) KitStoreCaption(stringResource(R.string.cost_failed, note), Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = KitStoreRow.BAD)
            else KitStoreCaption(stringResource(R.string.cost_source, note), Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = p.textSecondary)
            table.claudeAgeMs?.let { KitStoreCaption(stringResource(R.string.cost_age, AiUsageModel.ago(it)), Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH), color = p.textSecondary) }
            if (table.sampleOpenRouter) KitStoreCaption(stringResource(R.string.ai_sample_note), Modifier.fillMaxWidth().padding(KitStoreRow.padH).testTag(AiTags.SAMPLE), color = p.textSecondary)
        }
        KitStoreQuick(stringResource(R.string.cost_refresh), KitStoreRow.BLUE_FILL, Modifier.padding(KitStoreRow.padH)) { read = null; reload++ }
    }
}

private const val LABEL_WEIGHT = 0.6f

/** #1543 A Daily or Monthly table: a head row, then per period a bold row and its model-family rows, indented. */
@Composable
private fun TokenTable(title: String, columns: List<String>, periods: List<AiUsageModel.Period>, read: Boolean, tag: String) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxWidth().testTag(tag)) {
        KitStoreCaption(title, Modifier.fillMaxWidth().padding(KitStoreRow.padH), color = p.accent)
        KitStoreRowLine {
            KitStoreCaption("", Modifier.weight(TOK_LABEL_WEIGHT))
            columns.forEach { KitStoreCaption(it, Modifier.weight(1f), color = p.textSecondary, align = TextAlign.End) }
        }
        Rule()
        if (periods.isEmpty()) KitStoreCaption(if (read) stringResource(R.string.cost_no_tokens) else stringResource(R.string.cost_loading),
            Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH), color = p.textSecondary)
        Cost.tokenLines(periods).forEach { row ->
            KitStoreRowLine {
                if (row.child) KitStoreCaption("  " + row.label, Modifier.weight(TOK_LABEL_WEIGHT), color = p.textSecondary)
                else KitStoreName(row.label, Modifier.weight(TOK_LABEL_WEIGHT), color = p.textPrimary)
                row.cells.forEach { KitStoreCaption(it, Modifier.weight(1f), color = if (row.child) p.textSecondary else p.textPrimary, mono = true, align = TextAlign.End) }
            }
            if (!row.child) Rule()
        }
        Rule()
    }
}

private const val TOK_LABEL_WEIGHT = 1.3f

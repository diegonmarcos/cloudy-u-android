package com.diegonmarcos.cloudsuperintel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.OpenRouterModels
import com.diegonmarcos.cloudsuperintel.OpenRouterStore
import com.diegonmarcos.cloudsuperintel.OrRow
import com.diegonmarcos.cloudsuperintel.OrSnapshot
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiModelCatalogue
import com.diegonmarcos.superapp.ai.AiModelEntry
import com.diegonmarcos.superapp.ai.AiPublicFetch
import com.diegonmarcos.superapp.ai.AiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The test tags of the Models > Models table. */
object ModelsTags {
    const val STAMP = "or_stamp"
    const val REFRESH = "or_refresh"
    const val TABLE = "or_table"
    const val HEAD = "or_head"
    const val ROW = "or_row_"
}

/**
 * #1854 Models > Models: the model catalogue organised by Core (Frontier, Text, Search / Audio, Image, Video / Specialist, Tasks, Medium,
 * Small: libs:ai's AiModelCatalogue), and inside each Core a table of the FULL metadata OpenRouter's models list carries for every model of
 * that Core. The list is cached on disk and refreshed once a day when the page opens (OpenRouterStore), with a Refresh button and a
 * last-updated stamp.
 */
@Composable
fun ModelsPage(chat: AiChatState, theme: AiTheme, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current.applicationContext
    val store = remember { OpenRouterStore(File(ctx.filesDir, "openrouter"), { url -> AiPublicFetch.get(url) }) }
    var snap by remember { mutableStateOf<OrSnapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var kick by remember { mutableIntStateOf(0) }
    var forced by remember { mutableStateOf(false) }
    // On open: show the cache at once, then refresh when it is older than a day (or when Refresh was pressed).
    LaunchedEffect(kick) {
        busy = true
        if (snap == null) snap = withContext(Dispatchers.IO) { store.cached() }
        val force = forced
        val fresh = withContext(Dispatchers.IO) { if (force || store.stale()) store.refresh() else null }
        failed = fresh == null && (force || store.stale())
        if (fresh != null) snap = fresh
        forced = false
        busy = false
    }
    AiModelCatalogue(chat, theme, modifier = modifier, onClose = onClose) { tab, label, entries ->
        OrCoreTable(tab, label, entries, snap, busy, failed, theme) { forced = true; kick++ }
    }
}

/** One Core's block: the stamp and Refresh, then the table. */
@Composable
private fun OrCoreTable(tab: String, label: String, entries: List<AiModelEntry>, snap: OrSnapshot?, busy: Boolean, failed: Boolean, theme: AiTheme, onRefresh: () -> Unit) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val rows = remember(entries, snap) { OpenRouterModels.join(entries, snap?.models.orEmpty()) }
    Column(Modifier.fillMaxWidth().padding(bottom = m.gap).testTag(ModelsTags.TABLE + tab)) {
        Row(Modifier.fillMaxWidth().padding(bottom = m.small), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(m.gap)) {
            Text(
                stringResource(R.string.or_table_title, label, entries.size),
                color = c.text, style = t.style(t.chatInput, FontWeight.Bold), modifier = Modifier.weight(1f),
            )
            val shape = RoundedCornerShape(m.cardRadius)
            Text(
                if (busy) stringResource(R.string.or_refreshing) else stringResource(R.string.or_refresh),
                color = c.accent, style = t.style(t.small, FontWeight.Bold),
                modifier = Modifier.clip(shape).border(m.hairline, c.tileBorder, shape).clickable(enabled = !busy, onClick = onRefresh)
                    .padding(horizontal = m.cardPad, vertical = m.gap).testTag(ModelsTags.REFRESH),
            )
        }
        Text(
            when {
                snap == null && failed -> stringResource(R.string.or_none_failed)
                snap == null -> stringResource(R.string.or_none)
                else -> stringResource(R.string.or_stamp, stampOf(snap.at), snap.models.size) + if (failed) " · " + stringResource(R.string.or_failed_kept) else ""
            },
            color = if (failed) c.negative else c.text2, style = t.style(t.label), modifier = Modifier.padding(bottom = m.small).testTag(ModelsTags.STAMP),
        )
        if (entries.isNotEmpty()) MetaTable(rows, theme)
    }
}

private val STAMP_FORMAT = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", java.util.Locale.ROOT)

private fun stampOf(at: Long): String = java.time.Instant.ofEpochMilli(at).atOffset(java.time.ZoneOffset.UTC).format(STAMP_FORMAT)

/** Column widths, by field (dp): the narrow ones stay narrow, the long text gets room; the table scrolls sideways as one block. */
private fun widthOf(col: String): Dp = when (col) {
    "id" -> 190.dp
    "name" -> 160.dp
    "context_length", "max_completion_tokens" -> 70.dp
    "prompt", "completion", "request", "image" -> 64.dp
    "modality" -> 130.dp
    "tokenizer" -> 80.dp
    "is_moderated" -> 66.dp
    "supported_parameters" -> 260.dp
    "created" -> 84.dp
    "description" -> 360.dp
    else -> 90.dp
}

/** The head label of a column, as the table words it. */
@Composable
private fun headOf(col: String): String = when (col) {
    "id" -> stringResource(R.string.or_col_id)
    "name" -> stringResource(R.string.or_col_name)
    "context_length" -> stringResource(R.string.or_col_context)
    "prompt" -> stringResource(R.string.or_col_prompt)
    "completion" -> stringResource(R.string.or_col_completion)
    "request" -> stringResource(R.string.or_col_request)
    "image" -> stringResource(R.string.or_col_image)
    "modality" -> stringResource(R.string.or_col_modality)
    "tokenizer" -> stringResource(R.string.or_col_tokenizer)
    "max_completion_tokens" -> stringResource(R.string.or_col_max_out)
    "is_moderated" -> stringResource(R.string.or_col_moderated)
    "supported_parameters" -> stringResource(R.string.or_col_params)
    "created" -> stringResource(R.string.or_col_created)
    "description" -> stringResource(R.string.or_col_description)
    else -> col
}

/** The dense table: a sticky-looking head row and one row per model, one horizontal scroller for the whole block; a tap opens the full text of a row. */
@Composable
private fun MetaTable(rows: List<OrRow>, theme: AiTheme) {
    val c = theme.colors
    val t = theme.type
    val m = theme.metrics
    val cols = OpenRouterModels.COLUMNS
    var open by remember { mutableStateOf<String?>(null) }
    val shape = RoundedCornerShape(m.cardRadius)
    Box(Modifier.fillMaxWidth().clip(shape).border(m.hairline, c.tileBorder, shape)) {
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row(Modifier.background(c.chatBar).testTag(ModelsTags.HEAD)) {
                cols.forEach { col ->
                    Text(headOf(col), Modifier.width(widthOf(col)).padding(horizontal = m.gap, vertical = m.gap), color = c.accent, style = t.style(t.small, FontWeight.Bold), maxLines = 1)
                }
            }
            rows.forEachIndexed { i, r ->
                val expanded = open == r.model.id
                Row(
                    Modifier.background(if (i % 2 == 0) c.field.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { open = if (expanded) null else r.model.id }.testTag(ModelsTags.ROW + r.model.id),
                ) {
                    cols.forEach { col ->
                        val text = OpenRouterModels.cell(r.model, col)
                        val mono = col != "name" && col != "description" && col != "supported_parameters" && col != "modality"
                        Text(
                            text + if (col == "id" && !r.live) " ·" + stringResource(R.string.or_baked) else "",
                            Modifier.width(widthOf(col)).padding(horizontal = m.gap, vertical = m.small),
                            color = if (col == "id" || col == "name") c.text else c.text2,
                            style = t.style(t.small, if (col == "name") FontWeight.SemiBold else FontWeight.Normal).let { if (mono) it.copy(fontFamily = FontFamily.Monospace) else it },
                            maxLines = if (expanded) Int.MAX_VALUE else if (col == "description" || col == "supported_parameters") 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

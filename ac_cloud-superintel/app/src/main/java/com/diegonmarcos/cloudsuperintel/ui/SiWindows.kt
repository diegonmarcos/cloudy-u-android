package com.diegonmarcos.cloudsuperintel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.cloudsuperintel.AiPageSpec
import com.diegonmarcos.cloudsuperintel.CtxSegment
import com.diegonmarcos.cloudsuperintel.FileRow
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.cloudsuperintel.WindowsData
import com.diegonmarcos.superapp.ai.AiChatState
import com.diegonmarcos.superapp.ai.AiContext
import com.diegonmarcos.superapp.ai.ForumWindows

/** The test tags of the three top-right windows. */
object WindowTags {
    const val COST = "si_window_cost"
    const val CONTEXT = "si_window_context"
    const val FILES = "si_window_files"
    const val CLOSE = "si_window_close"
    const val CTX_BAR = "si_ctx_bar"
    const val CTX_ROW = "si_ctx_row_"
    const val FILE_ROW = "si_file_row_"
    const val FILE_FILTER = "si_file_filter_"
}

/**
 * #1854 The windows the top-right Cost, Context and Files icons open: a near-full-screen dialog with a title, a one-line subtitle and a
 * close button, over a dense body. Cost carries the whole former Cost page ([cost] is its pages.json spec: Claude and OpenRouter side by
 * side, the Daily and Monthly token tables, the weekly estimate); Context and Files hold sample data for now (labelled), plus the real
 * files of the open chat.
 */
fun siWindows(cost: AiPageSpec?): ForumWindows = ForumWindows(
    usage = { _, close -> SiWindow(stringResource(R.string.win_cost), stringResource(R.string.win_cost_sub), WindowTags.COST, close) { if (cost != null) AiPage(cost) } },
    context = { chat, close -> SiWindow(stringResource(R.string.win_context), stringResource(R.string.win_context_sub), WindowTags.CONTEXT, close) { ContextBody(chat) } },
    files = { chat, close -> SiWindow(stringResource(R.string.win_files), stringResource(R.string.win_files_sub), WindowTags.FILES, close) { FilesBody(chat) } },
)

/** The window frame: scrim, a rounded surface at 96% width and 88% height, the header, then [body]. */
@Composable
fun SiWindow(title: String, subtitle: String, tag: String, onClose: () -> Unit, body: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val s = MaterialTheme.colorScheme
        val shape = RoundedCornerShape(20.dp)
        Surface(
            Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f).widthIn(max = 920.dp).testTag(tag),
            shape = shape, color = s.surface, contentColor = s.onSurface, tonalElevation = 6.dp, shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().background(s.surfaceVariant).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = s.onSurface, maxLines = 1)
                        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClose).testTag(WindowTags.CLOSE), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.win_close), tint = s.onSurfaceVariant)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.outlineVariant))
                Box(Modifier.weight(1f).fillMaxWidth()) { body() }
            }
        }
    }
}

/** The slice colours, from the app's own scheme (no literal). */
@Composable
private fun sliceColor(id: String): Color {
    val s = MaterialTheme.colorScheme
    return when (id) {
        "system" -> s.primary
        "tools" -> s.tertiary
        "skills" -> s.secondary
        "memory" -> s.inversePrimary
        "messages" -> s.error
        "files" -> s.outline
        else -> s.surfaceVariant
    }
}

@Composable
private fun SampleTag(text: String) {
    val s = MaterialTheme.colorScheme
    Text(text, Modifier.clip(RoundedCornerShape(6.dp)).background(s.secondaryContainer).padding(horizontal = 6.dp, vertical = 2.dp), color = s.onSecondaryContainer, style = MaterialTheme.typography.labelSmall)
}

/** Context Breakdown: the window, one stacked bar of its slices, then a dense table (slice, tokens, share, what it is). */
@Composable
private fun ContextBody(chat: AiChatState) {
    val s = MaterialTheme.colorScheme
    val model = chat.models.firstOrNull { it.id == chat.model }
    val window = model?.context ?: 0L
    val segs = remember(window) { WindowsData.context(window) }
    val total = segs.sumOf { it.tokens }.coerceAtLeast(1)
    val used = segs.filter { it.id != "free" }.sumOf { it.tokens }
    val reported = AiContext.used(chat.session)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text((model?.name ?: chat.model).substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = s.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            SampleTag(stringResource(R.string.win_sample))
        }
        Text(
            stringResource(R.string.win_ctx_window, WindowsData.n(total), WindowsData.n(used), WindowsData.pct(used, total)),
            style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant, fontFamily = FontFamily.Monospace,
        )
        if (reported > 0) Text(stringResource(R.string.win_ctx_reported, WindowsData.n(reported)), style = MaterialTheme.typography.bodySmall, color = s.primary, fontFamily = FontFamily.Monospace)
        Row(Modifier.fillMaxWidth().height(18.dp).clip(RoundedCornerShape(9.dp)).background(s.surfaceVariant).testTag(WindowTags.CTX_BAR)) {
            segs.filter { it.tokens > 0 }.forEach { g -> Box(Modifier.weight(g.tokens.toFloat() / total).fillMaxHeight().background(sliceColor(g.id))) }
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, s.outlineVariant, RoundedCornerShape(12.dp))) {
            Row(Modifier.fillMaxWidth().background(s.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text(stringResource(R.string.win_ctx_slice), Modifier.weight(1.4f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = s.primary)
                Text(stringResource(R.string.win_ctx_tokens), Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = s.primary)
                Text(stringResource(R.string.win_ctx_share), Modifier.weight(0.6f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = s.primary)
            }
            segs.forEach { g -> ContextRow(g, total) }
        }
    }
}

@Composable
private fun ContextRow(g: CtxSegment, total: Long) {
    val s = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp).testTag(WindowTags.CTX_ROW + g.id), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1.4f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(sliceColor(g.id)))
            Column {
                Text(g.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = s.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(g.note, style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(WindowsData.n(g.tokens), Modifier.weight(0.8f), style = MaterialTheme.typography.bodySmall, color = s.onSurface, fontFamily = FontFamily.Monospace)
        Text(WindowsData.pct(g.tokens, total), Modifier.weight(0.6f), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant, fontFamily = FontFamily.Monospace)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(s.outlineVariant.copy(alpha = 0.5f)))
}

/** Files in this chat: filter chips, a one-line summary, then a dense list; the chat's own files come first, then the labelled sample. */
@Composable
private fun FilesBody(chat: AiChatState) {
    val s = MaterialTheme.colorScheme
    val real = remember(chat.session.messages.size, chat.attachments) {
        AiContext.files(chat.session, chat.attachments).map { FileRow(it.name, it.mime.substringAfter('/').ifBlank { "file" }, it.bytes, "attached", 0, "this chat", false) }
    }
    val all = real + WindowsData.files()
    var filter by remember { mutableStateOf("all") }
    val rows = WindowsData.filtered(all, filter)
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            WindowsData.FILTERS.forEach { f ->
                val on = f == filter
                Text(
                    filterLabel(f), Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) s.primaryContainer else s.surfaceVariant)
                        .clickable { filter = f }.padding(horizontal = 12.dp, vertical = 6.dp).testTag(WindowTags.FILE_FILTER + f),
                    style = MaterialTheme.typography.labelMedium, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, color = if (on) s.onPrimaryContainer else s.onSurfaceVariant,
                )
            }
            Box(Modifier.weight(1f))
            SampleTag(stringResource(R.string.win_sample))
        }
        Text(WindowsData.summary(rows), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant, fontFamily = FontFamily.Monospace)
        Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).border(1.dp, s.outlineVariant, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState())) {
            if (rows.isEmpty()) Text(stringResource(R.string.win_files_none), Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = s.onSurfaceVariant)
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag(WindowTags.FILE_ROW + r.name), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(r.kind.take(4).uppercase(), Modifier.width(44.dp).clip(RoundedCornerShape(6.dp)).background(s.secondaryContainer).padding(vertical = 4.dp), color = s.onSecondaryContainer, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = s.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.source + " · " + r.time, style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(WindowsData.size(r.bytes), style = MaterialTheme.typography.bodySmall, color = s.onSurface, fontFamily = FontFamily.Monospace)
                        if (r.tokens > 0) Text(WindowsData.n(r.tokens) + " tok", style = MaterialTheme.typography.labelSmall, color = s.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(s.outlineVariant.copy(alpha = 0.5f)))
            }
        }
    }
}

@Composable
private fun filterLabel(f: String): String = when (f) {
    "attached" -> stringResource(R.string.win_files_attached)
    "generated" -> stringResource(R.string.win_files_generated)
    "notebook" -> stringResource(R.string.win_files_notebook)
    else -> stringResource(R.string.win_files_all)
}

package com.diegonmarcos.superapp.fleetlogcat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.devtools.LogPipe
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigs
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Test tags of the Logcat tab (UiAutomator resource ids). */
public object FleetLogcatTags {
    public const val PAGE: String = "fleetcfg_page_logcat"
    public const val ERRORS_ONLY: String = "fleetlogcat_errors_only"
    public const val COPY_MD: String = "fleetlogcat_copy_md"
    public const val SAVE_HTML: String = "fleetlogcat_save_html"
    public const val SHARE: String = "fleetlogcat_share"
    public const val BODY: String = "fleetlogcat_body"
    /** #1828 the filters. */
    public fun level(l: LogLevelFilter): String = "fleetlogcat_level_${l.name.lowercase()}"
    public fun kind(k: LogKind): String = "fleetlogcat_kind_${k.name.lowercase()}"
    public const val SEARCH: String = "fleetlogcat_search"
    public const val KIND_ALL: String = "fleetlogcat_kind_all"
}

private const val TAIL_LINES = 600

/** #1828 how far back a filter looks: the filtered view still shows up to [TAIL_LINES] lines that pass. */
private const val FILTER_SCAN_LINES = 5000

/** The lines the page shows and exports: the stream's tail, then [filter]. */
internal fun filteredTail(uid: Int, errorsOnly: Boolean, filter: LogcatFilter): String {
    if (filter.isOpen) return LogPipe.tail(TAIL_LINES, uid, errorsOnly)
    val raw = LogPipe.tail(FILTER_SCAN_LINES, uid, errorsOnly)
    val kept = filter.apply(raw.split("\n"), TAIL_LINES)
    return if (kept.isEmpty()) "(no line matches the filter: ${filter.describe()})\n" else kept.joinToString("\n") + "\n"
}

/**
 * Configs ▸ Logcat: THIS app's own log stream (libs:devtools LogPipe, one reader per process, uid-filtered to this app) with an
 * errors-only toggle, #1828 filters (level, kind of log, search; [LogcatFilter]) that the exports apply too, and the same two-way export the X-Ray tab offers — Copy (token-optimized Markdown), Save (an HTML page) and
 * Share. The view refreshes every second while it is open.
 */
@Composable
public fun FleetLogcatPage(dark: Boolean) {
    val c = ThemeBackground.colors(dark)
    val ctx = LocalContext.current
    var errorsOnly by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(LogcatFilter()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
    val uid = remember { ctx.applicationInfo.uid }
    val text = remember(tick, errorsOnly, filter) { filteredTail(uid, errorsOnly, filter) }

    Column(Modifier.fillMaxSize().testTag(FleetLogcatTags.PAGE).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Errors only", Modifier.weight(1f), color = c.ink, fontSize = 13.sp)
            Switch(checked = errorsOnly, onCheckedChange = { errorsOnly = it }, modifier = Modifier.testTag(FleetLogcatTags.ERRORS_ONLY))
        }
        // #1828 filters: level (one), kind of log (any number; none = all), search over tag and message.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LogLevelFilter.entries.forEach { l ->
                FilterChip(l.label, filter.level == l, c, FleetLogcatTags.level(l)) { filter = filter.copy(level = l) }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip("All kinds", filter.kinds.isEmpty(), c, FleetLogcatTags.KIND_ALL) { filter = filter.copy(kinds = emptySet()) }
            LogKind.entries.forEach { k ->
                val on = k in filter.kinds
                FilterChip(k.label, on, c, FleetLogcatTags.kind(k)) { filter = filter.copy(kinds = if (on) filter.kinds - k else filter.kinds + k) }
            }
        }
        OutlinedTextField(
            value = filter.query,
            onValueChange = { filter = filter.copy(query = it) },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(FleetLogcatTags.SEARCH),
            singleLine = true,
            placeholder = { Text("Search tag or message", fontSize = 13.sp) },
            textStyle = androidx.compose.ui.text.TextStyle(color = c.ink, fontSize = 13.sp),
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogcatButton("Copy", c, FleetLogcatTags.COPY_MD) { copyMarkdown(ctx, errorsOnly, uid, filter) }
            LogcatButton("Save", c, FleetLogcatTags.SAVE_HTML) { saveHtml(ctx, errorsOnly, uid, filter) }
            LogcatButton("Share", c, FleetLogcatTags.SHARE) { shareMarkdown(ctx, errorsOnly, uid, filter) }
        }
        Text(
            text,
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .testTag(FleetLogcatTags.BODY),
            color = c.ink, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
        )
    }
}

@Composable
private fun LogcatButton(label: String, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Text(
        label,
        Modifier.clip(shape).background(c.tabSelected, shape).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag(tag),
        color = c.onTabSelected, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
    )
}

/** One filter chip in the page's own colours (selected = the tab-selected pair, as the buttons). */
@Composable
private fun FilterChip(label: String, on: Boolean, c: ThemeColors, tag: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        Modifier.clip(shape).background(if (on) c.tabSelected else c.island, shape).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
        color = if (on) c.onTabSelected else c.onIsland, fontSize = 12.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
    )
}

private fun snapshot(ctx: Context, errorsOnly: Boolean, uid: Int, filter: LogcatFilter = LogcatFilter()): FleetLogExport.Snapshot {
    val decl = FleetConfigs.current
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
    val body = filteredTail(uid, errorsOnly, filter)
    return FleetLogExport.Snapshot(
        app = decl.name.ifBlank { ctx.packageName },
        pkg = ctx.packageName,
        sha = decl.sha,
        capturedAt = stamp,
        health = LogPipe.health,
        errorsOnly = errorsOnly,
        lines = body.split("\n").filter { it.isNotBlank() },
        filter = filter.describe(),
    )
}

private fun copyMarkdown(ctx: Context, errorsOnly: Boolean, uid: Int, filter: LogcatFilter = LogcatFilter()) {
    runCatching {
        val md = FleetLogExport.markdown(snapshot(ctx, errorsOnly, uid, filter))
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("logcat", md))
        Toast.makeText(ctx, "Logcat copied as Markdown", Toast.LENGTH_SHORT).show()
    }
}

private fun shareMarkdown(ctx: Context, errorsOnly: Boolean, uid: Int, filter: LogcatFilter = LogcatFilter()) {
    runCatching {
        val md = FleetLogExport.markdown(snapshot(ctx, errorsOnly, uid, filter))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, md).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(Intent.createChooser(send, "Share logcat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun saveHtml(ctx: Context, errorsOnly: Boolean, uid: Int, filter: LogcatFilter = LogcatFilter()) {
    runCatching {
        val html = FleetLogExport.html(snapshot(ctx, errorsOnly, uid, filter))
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val out = File(dir, "logcat-" + System.currentTimeMillis() + ".html")
        out.writeText(html)
        Toast.makeText(ctx, "Saved " + out.absolutePath, Toast.LENGTH_LONG).show()
    }
}

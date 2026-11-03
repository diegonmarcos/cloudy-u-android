package com.diegonmarcos.superapp.scraper

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** The Scrapers section's two top-nav pages, by id (build.json ui.sections[scrapers].pages). */
object ScrapersPages {
    const val SITE_FILES = "site_files"
    const val SCRAP_TOOLS = "scrap_tools"
}

/** #1426 the Scrapers section: [page] is a ScrapersPages id; drawn by the app over its host. */
@Composable
fun ScrapersPage(page: String?, c: ThemeColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(c.top).testTag("scrapers:page:${page ?: ScrapersPages.SITE_FILES}")) {
        when (page) {
            ScrapersPages.SCRAP_TOOLS -> ScrapToolsPage(c)
            else -> SiteFilesPage(c)
        }
    }
}

internal fun human(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun date(ts: Long): String = if (ts <= 0) "never" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

@Composable
private fun Chip(label: String, selected: Boolean, c: ThemeColors, tag: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = c.ink, selectedLabelColor = c.onTabSelected, selectedContainerColor = c.tabSelected,
        ),
        modifier = Modifier.padding(end = 8.dp).testTag(tag),
    )
}

/** #1427 Site Files: sites as folders (tap to drop down the files), sorted by name, last visit or last updated. */
@Composable
fun SiteFilesPage(c: ThemeColors) {
    val ctx = LocalContext.current
    var groups by remember { mutableStateOf<List<SiteGroup>?>(null) }
    var sort by remember { mutableStateOf(SiteSort.LAST_VISIT) }
    val open = remember { mutableStateListOf<String>() }
    var viewing by remember { mutableStateOf<SiteFile?>(null) }
    LaunchedEffect(Unit) { groups = withContext(Dispatchers.IO) { runCatching { SiteFilesSource.load(ctx) }.getOrDefault(emptyList()) } }
    val v = viewing
    if (v != null) { FileViewer(v, c, onClose = { viewing = null }); return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Sort", color = c.inkDim, modifier = Modifier.padding(end = 8.dp))
            for (s in SiteSort.entries) Chip(s.label, sort == s, c, "scrapers:sort:${s.name}") { sort = s }
        }
        val gs = groups
        when {
            gs == null -> Text("Reading the cache…", color = c.inkDim, modifier = Modifier.padding(16.dp))
            gs.isEmpty() -> Text("No site has left files on the phone yet.", color = c.inkDim, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize().testTag("scrapers:sites")) {
                for (g in SiteFiles.sort(gs, sort)) {
                    val expanded = g.host in open
                    item(key = "site:" + g.host) {
                        Row(Modifier.fillMaxWidth().clickable { if (expanded) open.remove(g.host) else open.add(g.host) }
                            .padding(horizontal = 16.dp, vertical = 12.dp).testTag("scrapers:site:${g.host}"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(if (expanded) "▾" else "▸", color = c.accent, modifier = Modifier.width(22.dp))
                            Column(Modifier.weight(1f)) {
                                Text(g.host, color = c.ink, fontWeight = FontWeight.SemiBold)
                                Text("${g.files.size} files · ${human(g.bytes)} · visited ${date(g.lastVisit)}", color = c.inkDim,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (expanded) items(g.files, key = { "file:" + g.host + ":" + it.path + it.url }) { f ->
                        Row(Modifier.fillMaxWidth().clickable { viewing = f }.padding(start = 54.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                            .testTag("scrapers:file:${f.name}")) {
                            Column(Modifier.weight(1f)) {
                                Text(f.name, color = c.ink, maxLines = 1)
                                Text("${f.source.label} · ${human(f.bytes)} · ${date(f.modified)}", color = c.inkDim,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** #1428 Scrap Tools: one tool per row; the first is YouTube Download. */
@Composable
fun ScrapToolsPage(c: ThemeColors) {
    var openTool by remember { mutableStateOf("youtube") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().clickable { openTool = if (openTool == "youtube") "" else "youtube" }
            .background(c.island, RoundedCornerShape(12.dp)).padding(14.dp).testTag("scrapers:tool:youtube"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (openTool == "youtube") "▾" else "▸", color = c.accent, modifier = Modifier.width(22.dp))
            Text("YouTube Download", color = c.onIsland, fontWeight = FontWeight.SemiBold)
        }
        if (openTool == "youtube") YouTubeTool(c)
    }
}

@Composable
private fun YouTubeTool(c: ThemeColors) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var saveAs by remember { mutableStateOf(YtAs.VIDEO) }
    var quality by remember { mutableStateOf<Int?>(1080) }
    var target by remember { mutableStateOf(YtTarget.DOWNLOADS) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var prog by remember { mutableStateOf(-1f) }
    val req = YtRequest(url, saveAs, quality, target)
    val problems = YtTool.problems(req)

    fun start(chosen: Uri?) {
        busy = true; status = "Downloading…"; prog = -1f
        scope.launch {
            status = withContext(Dispatchers.IO) {
                runCatching { YtDownloader.run(ctx, req, chosen) { d, t -> prog = if (t > 0) d.toFloat() / t else -1f } }
                    .fold({ it }, { "Failed: ${it.message}" })
            }
            busy = false
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri -> if (uri != null) start(uri) }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
        Text(YtTool.NOTICE, color = c.ink, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().background(Color(0x33FFB300), RoundedCornerShape(8.dp)).padding(10.dp).testTag("scrapers:yt:notice"))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(url, { url = it }, label = { Text("YouTube link") }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedLabelColor = c.accent, unfocusedLabelColor = c.inkDim),
                modifier = Modifier.weight(1f).testTag("scrapers:yt:url"))
            TextButton(onClick = { clip.getText()?.text?.let { url = it.trim() } }, modifier = Modifier.testTag("scrapers:yt:paste")) { Text("Paste", color = c.accent) }
        }
        Spacer(Modifier.height(10.dp))
        Text("As", color = c.inkDim)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (a in YtAs.entries) Chip(a.label, saveAs == a, c, "scrapers:yt:as:${a.name}") {
                saveAs = a; quality = YtTool.qualities(a).let { q -> if (q.isEmpty()) null else if (a == YtAs.VIDEO) 1080 else 160 }
            }
        }
        val qs = YtTool.qualities(saveAs)
        if (qs.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (q in qs) Chip(if (saveAs == YtAs.VIDEO) "${q}p" else "$q kbps", quality == q, c, "scrapers:yt:q:$q") { quality = q }
        } else Text("Downloads a small video and takes its audio track out on the phone.", color = c.inkDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        Text("Download to", color = c.inkDim)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (t in YtTarget.entries) Chip(t.label, target == t, c, "scrapers:yt:to:${t.name}") { target = t }
        }
        Spacer(Modifier.height(12.dp))
        Button(onClick = { if (target == YtTarget.CHOOSE) picker.launch(YtTool.suggestedName(req)) else start(null) },
            enabled = !busy && problems.isEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = c.accent),
            modifier = Modifier.fillMaxWidth().testTag("scrapers:yt:download")) { Text("Download") }
        if (url.isNotBlank() && problems.isNotEmpty()) Text(problems.joinToString(" · "), color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 6.dp))
        if (busy) LinearProgressIndicator(progress = { if (prog < 0) 0f else prog }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        if (status.isNotBlank()) Text(status, color = c.ink, modifier = Modifier.padding(top = 8.dp).testTag("scrapers:yt:status"))
    }
}

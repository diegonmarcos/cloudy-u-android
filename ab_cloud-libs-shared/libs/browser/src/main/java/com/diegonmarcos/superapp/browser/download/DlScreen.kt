package com.diegonmarcos.superapp.browser.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitTextButton
import com.diegonmarcos.superapp.uikit.KitTonalButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.delay

/** What the Downloads page shows for one row, in words (pure, JVM-tested). */
object DlRowText {
    fun pill(j: DlJob): Pair<String, KitState> = when (j.state) {
        DlState.QUEUED -> "queued" to KitState.IDLE
        DlState.RUNNING -> (if (j.kind == DlJob.KIND_TORRENT && j.total > 0 && j.downloaded >= j.total) "seeding" else "running") to KitState.BUSY
        DlState.RETRYING -> "retrying" to KitState.WARN
        DlState.WAITING_NETWORK -> "waiting" to KitState.WARN
        DlState.PAUSED -> "paused" to KitState.IDLE
        DlState.FAILED -> "failed" to KitState.BAD
        DlState.COMPLETED -> "done" to KitState.OK
        DlState.CANCELLED -> "cancelled" to KitState.IDLE
    }

    fun secondary(j: DlJob, l: DlLive): String {
        val size = if (j.total > 0) "${DlFormat.bytes(j.downloaded)} / ${DlFormat.bytes(j.total)}" else DlFormat.bytes(j.downloaded)
        val parts = mutableListOf(size)
        if (j.state == DlState.RUNNING) {
            parts += DlFormat.speed(l.bytesPerSec)
            if (l.etaSec >= 0) parts += DlFormat.eta(l.etaSec) + " left"
        }
        if (j.kind == DlJob.KIND_TORRENT && (j.state == DlState.RUNNING || l.peers > 0))
            parts += "${l.peers} peers · ${l.seeds} seeds · ↑${DlFormat.speed(l.upPerSec)}"
        if (j.state == DlState.WAITING_NETWORK) parts += "waiting for an allowed network"
        if (j.segments.size > 1 && j.state == DlState.RUNNING) parts += "${j.segments.size} connections"
        j.error?.takeIf { it.isNotBlank() }?.let { parts += it }
        return parts.joinToString(" · ")
    }

    fun matches(j: DlJob, q: String): Boolean = q.isBlank() ||
        j.fileName.contains(q, ignoreCase = true) || j.url.contains(q, ignoreCase = true) || j.state.name.contains(q, ignoreCase = true)

    /** #1841 the page's filter (and an optional sort: "input", "created", "name") through the browser core; [matches] is the readable rule it is held to. */
    fun filter(jobs: List<DlJob>, q: String, sort: String = "input"): List<DlJob> {
        if (q.isBlank() && sort == "input") return jobs
        val a = org.json.JSONObject().put("query", q).put("sort", sort).put("jobs", org.json.JSONArray().also { arr ->
            jobs.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("fileName", it.fileName).put("url", it.url).put("state", it.state.name).put("createdAt", it.createdAt)) }
        })
        val idx = com.diegonmarcos.cloudlib.browsercore.BrowserCore.lists("dl_jobs_query", a).optJSONArray("indices") ?: return jobs.filter { matches(it, q) }
        return (0 until idx.length()).map { jobs[idx.getInt(it)] }
    }
}

/**
 * The Downloads page (dense ui-kit): every HTTP download and torrent with its progress, speed and
 * ETA; Pause / Resume / Retry / Cancel; Open / Share when done; Remove (keep or delete the file);
 * a search filter; and for torrents peers, seeds, rates, per-file progress, file selection,
 * sequential order and the seeding switch.
 */
@Composable
fun DownloadsScreen(
    q: DlQueue,
    onOpen: (DlJob) -> Unit,
    onShare: (DlJob) -> Unit,
    onClose: () -> Unit,
) {
    val p = LocalKitPalette.current
    var rev by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<String?>(null) }
    DisposableEffect(q) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val l: () -> Unit = { main.post { rev++ } }   // the queue reports from worker threads
        q.addListener(l)
        onDispose { q.removeListener(l) }
    }
    LaunchedEffect(q) { while (true) { delay(1_000); rev++ } }   // speeds and ETAs move without a state change
    val jobs = rev.let { DlRowText.filter(q.all(), filter) }
    Column(Modifier.fillMaxSize().background(p.surface).padding(KitDensity.large).testTag("browser:downloads")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KitTextButton(onClose) { Text("← Back") }
            Text("Downloads", color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val anyActive = jobs.any { it.state.active }
            KitTextButton({ if (anyActive) q.pauseAll() else q.resumeAll() }, Modifier.testTag("dl:all")) {
                Text(if (anyActive) "Pause all" else "Resume all")
            }
        }
        KitSearchBar(filter, { filter = it }, {}, Modifier.padding(vertical = KitDensity.small), placeholder = "Filter downloads")
        val pol = q.policy
        Text(
            "${pol.parallel} at a time" + (if (pol.wifiOnly) " · Wi-Fi only" else if (!pol.allowMetered) " · unmetered only" else "") +
                (if (pol.speedLimitKbps > 0) " · limit ${DlFormat.speed(pol.speedLimitKbps * 1024L)}" else "") +
                (if (q.networkAllowed()) "" else " · network not allowed now"),
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (jobs.isEmpty()) Text(if (filter.isBlank()) "Nothing downloaded yet." else "No download matches.",
            color = p.textSecondary, modifier = Modifier.padding(KitDensity.large))
        LazyColumn(Modifier.fillMaxWidth()) {
            items(jobs, key = { it.id }) { j ->
                val live = q.live(j.id)
                Column(Modifier.fillMaxWidth().testTag("dl:row:${j.id}")) {
                    KitListRow(
                        label = j.fileName,
                        secondary = DlRowText.secondary(j, live),
                        leading = if (j.kind == DlJob.KIND_TORRENT) "⇅" else "↓",
                        pill = DlRowText.pill(j),
                        tag = "dl:${j.id}",
                        onClick = { if (j.state == DlState.COMPLETED && j.kind == DlJob.KIND_HTTP) onOpen(j) else expanded = if (expanded == j.id) null else j.id },
                    )
                    if (j.state.active || j.state == DlState.PAUSED) {
                        if (j.total > 0) LinearProgressIndicator(progress = { j.progress }, modifier = Modifier.fillMaxWidth())
                        else if (j.state == DlState.RUNNING) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                        when (j.state) {
                            DlState.RUNNING, DlState.RETRYING, DlState.QUEUED, DlState.WAITING_NETWORK -> {
                                KitTonalButton({ q.pause(j.id) }, Modifier.testTag("dl:pause:${j.id}")) { Text("Pause") }
                                KitOutlinedButton({ q.cancel(j.id) }, Modifier.testTag("dl:cancel:${j.id}")) { Text("Cancel") }
                            }
                            DlState.PAUSED -> {
                                KitButton({ q.resume(j.id) }, Modifier.testTag("dl:resume:${j.id}")) { Text("Resume") }
                                KitOutlinedButton({ q.cancel(j.id) }, Modifier.testTag("dl:cancel:${j.id}")) { Text("Cancel") }
                            }
                            DlState.FAILED, DlState.CANCELLED -> {
                                KitButton({ q.retry(j.id) }, Modifier.testTag("dl:retry:${j.id}")) { Text("Retry") }
                                KitOutlinedButton({ confirmRemove = j.id }, Modifier.testTag("dl:remove:${j.id}")) { Text("Remove") }
                            }
                            DlState.COMPLETED -> {
                                KitButton({ onOpen(j) }, Modifier.testTag("dl:open:${j.id}")) { Text("Open") }
                                KitTonalButton({ onShare(j) }, Modifier.testTag("dl:share:${j.id}")) { Text("Share") }
                                KitOutlinedButton({ confirmRemove = j.id }, Modifier.testTag("dl:remove:${j.id}")) { Text("Remove") }
                            }
                        }
                    }
                    if (confirmRemove == j.id) Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                        Text("Remove from the list:", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
                        KitTonalButton({ q.remove(j.id, deleteFile = false); confirmRemove = null }, Modifier.testTag("dl:keep:${j.id}")) { Text("Keep file") }
                        KitOutlinedButton({ q.remove(j.id, deleteFile = true); confirmRemove = null }, Modifier.testTag("dl:delete:${j.id}")) { Text("Delete file") }
                    }
                    if (j.kind == DlJob.KIND_TORRENT && expanded == j.id) TorrentDetails(q, j, live)
                    HorizontalDivider(color = p.hairline)
                }
            }
        }
    }
}

/** A torrent's files (progress, pick which to download), sequential order and the seeding switch. */
@Composable
private fun TorrentDetails(q: DlQueue, j: DlJob, live: DlLive) {
    val p = LocalKitPalette.current
    val t = q.torrent ?: return
    val files = t.files(j.id)
    Column(Modifier.padding(start = KitDensity.large, bottom = KitDensity.small).testTag("dl:torrent:${j.id}")) {
        Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(j.sequential, { t.setSequential(j.id, it) }, Modifier.testTag("dl:seq:${j.id}"))
            Text("Sequential (preview media)", color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
            Checkbox(j.seed, { t.setSeed(j.id, it) }, Modifier.testTag("dl:seed:${j.id}"))
            Text("Seed", color = p.textPrimary, style = MaterialTheme.typography.bodySmall)
        }
        if (files.isEmpty()) Text("Fetching metadata…", color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        files.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f.selected, { on ->
                    val sel = files.filter { it.selected }.map { it.index }.toMutableSet()
                    if (on) sel += f.index else sel -= f.index
                    t.setFiles(j.id, sel)
                }, Modifier.testTag("dl:file:${j.id}:${f.index}"))
                Column(Modifier.weight(1f)) {
                    Text(f.path, color = p.textPrimary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    Text("${DlFormat.bytes(f.size)} · ${(f.progress * 100).toInt()}%", color = p.textSecondary, style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(progress = { f.progress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
